package com.localbridge.android.core.network.local

import com.localbridge.android.core.AppConstants
import com.localbridge.android.core.logging.LoggerService
import java.io.ByteArrayOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LocalHttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val bodyBytes: ByteArray,
    val remoteAddress: String? = null
) {
    val bodyText: String
        get() = bodyBytes.toString(Charsets.UTF_8)

    fun header(name: String): String? = headers[name.lowercase(Locale.US)]
}

data class LocalHttpResponse(
    val statusCode: Int,
    val reasonPhrase: String,
    val contentType: String,
    val bodyBytes: ByteArray
) {
    companion object {
        fun json(statusCode: Int, reasonPhrase: String, body: String): LocalHttpResponse {
            return LocalHttpResponse(
                statusCode = statusCode,
                reasonPhrase = reasonPhrase,
                contentType = "application/json; charset=utf-8",
                bodyBytes = body.toByteArray(Charsets.UTF_8)
            )
        }

        fun text(statusCode: Int, reasonPhrase: String, body: String): LocalHttpResponse {
            return LocalHttpResponse(
                statusCode = statusCode,
                reasonPhrase = reasonPhrase,
                contentType = "text/plain; charset=utf-8",
                bodyBytes = body.toByteArray(Charsets.UTF_8)
            )
        }
    }
}

class LocalHttpHostService(
    private val loggerService: LoggerService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val handlers = ConcurrentHashMap<String, suspend (LocalHttpRequest) -> LocalHttpResponse>()

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    fun start() {
        scope.launch {
            ensureStarted()
        }
    }

    fun register(
        method: String,
        path: String,
        handler: suspend (LocalHttpRequest) -> LocalHttpResponse
    ) {
        handlers[handlerKey(method, path)] = handler
        start()
    }

    suspend fun unregister(method: String, path: String) {
        handlers.remove(handlerKey(method, path))
    }

    private suspend fun ensureStarted() = lifecycleMutex.withLock {
        if (serverJob?.isActive == true) {
            return@withLock
        }

        val socket = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(
                    InetSocketAddress(AppConstants.defaultApiPort),
                    AppConstants.localHttpServerBacklog
                )
            }
        }.getOrElse { exception ->
            loggerService.error(
                "Android local HTTP host could not bind TCP ${AppConstants.defaultApiPort}: ${exception.message}",
                exception
            )
            return@withLock
        }

        serverSocket = socket
        serverJob = scope.launch {
            loggerService.info("Android local HTTP host listening on TCP ${AppConstants.defaultApiPort}.")
            acceptLoop(socket)
        }
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (scope.isActive) {
            try {
                val client = socket.accept()
                scope.launch {
                    client.use { handleClient(it) }
                }
            } catch (_: SocketException) {
                break
            } catch (exception: Exception) {
                loggerService.error("Android local HTTP host accept error: ${exception.message}", exception)
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        socket.soTimeout = AppConstants.transferRequestTimeoutMillis
        socket.tcpNoDelay = true
        socket.keepAlive = true
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())

        while (scope.isActive) {
            val request = try {
                readHttpRequest(input)?.copy(
                    remoteAddress = socket.inetAddress?.hostAddress
                )
            } catch (_: SocketTimeoutException) {
                break
            }

            if (request == null) {
                break
            }

            val handler = handlers[handlerKey(request.method, request.path)]
            val response = if (handler == null) {
                LocalHttpResponse.text(404, "Not Found", "Endpoint not found.")
            } else {
                try {
                    handler.invoke(request)
                } catch (exception: Exception) {
                    loggerService.error(
                        "Android local HTTP host handler failed for ${request.method} ${request.path}: ${exception.message}",
                        exception
                    )
                    LocalHttpResponse.text(500, "Internal Server Error", "Endpoint handler failed.")
                }
            }

            val keepAlive = !request.header("connection").equals("close", ignoreCase = true)
            writeResponse(output, response, keepAlive)
            if (!keepAlive) {
                break
            }
        }
    }

    private fun readHttpRequest(input: BufferedInputStream): LocalHttpRequest? {
        val headerBytes = readHeadersBytes(input) ?: return null
        val headerText = headerBytes.toString(Charsets.ISO_8859_1)
        val headerLines = headerText
            .replace("\r\n", "\n")
            .split('\n')
            .filter { line -> line.isNotEmpty() }

        if (headerLines.isEmpty()) {
            return null
        }

        val requestLineParts = headerLines.first().split(' ')
        if (requestLineParts.size < 2) {
            return null
        }

        val headers = linkedMapOf<String, String>()
        headerLines.drop(1).forEach { line ->
            val separatorIndex = line.indexOf(':')
            if (separatorIndex > 0) {
                val key = line.substring(0, separatorIndex).trim().lowercase(Locale.US)
                val value = line.substring(separatorIndex + 1).trim()
                headers[key] = value
            }
        }

        val rawPath = requestLineParts[1]
        val bodyBytes = try {
            readBodyBytes(input, headers)
        } catch (exception: Exception) {
            loggerService.warning(
                "Android local HTTP host could not read ${requestLineParts[0]} ${rawPath.substringBefore('?')} body: ${exception.message}"
            )
            return null
        }

        return LocalHttpRequest(
            method = requestLineParts[0],
            path = rawPath.substringBefore('?'),
            headers = headers,
            bodyBytes = bodyBytes
        )
    }

    private fun readBodyBytes(
        input: BufferedInputStream,
        headers: Map<String, String>
    ): ByteArray {
        val transferEncoding = headers["transfer-encoding"]
            ?.lowercase(Locale.US)
            .orEmpty()

        return if (transferEncoding.contains("chunked")) {
            readChunkedBody(input)
        } else {
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength <= 0) {
                ByteArray(0)
            } else {
                val bodyBytes = ByteArray(contentLength)
                readExactly(input, bodyBytes, contentLength)
                bodyBytes
            }
        }
    }

    private fun readChunkedBody(input: BufferedInputStream): ByteArray {
        val output = ByteArrayOutputStream()

        while (true) {
            val sizeLine = readAsciiLine(input) ?: throw EOFException("Missing chunk header.")
            val normalizedSizeLine = sizeLine.trim()
            if (normalizedSizeLine.isEmpty()) {
                continue
            }

            val chunkSize = normalizedSizeLine.substringBefore(';').trim().toInt(16)
            if (chunkSize == 0) {
                consumeTrailerHeaders(input)
                break
            }

            val chunkBytes = ByteArray(chunkSize)
            readExactly(input, chunkBytes, chunkSize)
            output.write(chunkBytes)
            consumeChunkTerminator(input)
        }

        return output.toByteArray()
    }

    private fun readExactly(
        input: BufferedInputStream,
        buffer: ByteArray,
        expectedLength: Int
    ) {
        var totalRead = 0
        while (totalRead < expectedLength) {
            val read = input.read(buffer, totalRead, expectedLength - totalRead)
            if (read <= 0) {
                throw EOFException("Unexpected end of stream after $totalRead of $expectedLength byte(s).")
            }
            totalRead += read
        }
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val output = ByteArrayOutputStream()

        while (true) {
            val next = input.read()
            if (next == -1) {
                return if (output.size() == 0) null else output.toString(Charsets.ISO_8859_1.name())
            }

            if (next == '\n'.code) {
                break
            }

            if (next != '\r'.code) {
                output.write(next)
            }
        }

        return output.toString(Charsets.ISO_8859_1.name())
    }

    private fun consumeChunkTerminator(input: BufferedInputStream) {
        val first = input.read()
        if (first == -1) {
            throw EOFException("Unexpected end of stream while consuming chunk terminator.")
        }

        if (first == '\r'.code) {
            val second = input.read()
            if (second != '\n'.code) {
                throw EOFException("Invalid CRLF chunk terminator.")
            }
            return
        }

        if (first != '\n'.code) {
            throw EOFException("Invalid chunk terminator byte: $first.")
        }
    }

    private fun consumeTrailerHeaders(input: BufferedInputStream) {
        while (true) {
            val line = readAsciiLine(input) ?: return
            if (line.isBlank()) {
                return
            }
        }
    }

    private fun readHeadersBytes(input: BufferedInputStream): ByteArray? {
        val bytes = ArrayList<Byte>(1024)
        var matched = 0

        while (bytes.size < 64 * 1024) {
            val next = input.read()
            if (next == -1) {
                break
            }

            bytes.add(next.toByte())
            matched = when {
                matched == 0 && next == '\r'.code -> 1
                matched == 1 && next == '\n'.code -> 2
                matched == 2 && next == '\r'.code -> 3
                matched == 3 && next == '\n'.code -> 4
                matched == 0 && next == '\n'.code -> 5
                matched == 5 && next == '\n'.code -> 6
                else -> 0
            }

            if (matched == 4) {
                return bytes.dropLast(4).toByteArray()
            }
            if (matched == 6) {
                return bytes.dropLast(2).toByteArray()
            }
        }

        return null
    }

    private fun writeResponse(output: BufferedOutputStream, response: LocalHttpResponse, keepAlive: Boolean) {
        output.write(
            buildString {
                append("HTTP/1.1 ${response.statusCode} ${response.reasonPhrase}\r\n")
                append("Content-Type: ${response.contentType}\r\n")
                append("Content-Length: ${response.bodyBytes.size}\r\n")
                append("Connection: ")
                append(if (keepAlive) "keep-alive" else "close")
                append("\r\n\r\n")
            }.toByteArray(Charsets.UTF_8)
        )
        output.write(response.bodyBytes)
        output.flush()
    }

    private fun handlerKey(method: String, path: String): String {
        return "${method.uppercase(Locale.US)} $path"
    }
}
