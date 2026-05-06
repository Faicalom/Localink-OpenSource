package com.localbridge.android.core.network

import com.localbridge.android.core.protocol.ProtocolEnvelope
import com.localbridge.android.core.protocol.decodeEnvelope
import com.localbridge.android.core.protocol.encodeEnvelope
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class HttpEnvelopeResponse<T>(
    val statusCode: Int,
    val envelope: ProtocolEnvelope<T>?,
    val rawBody: String
)

object ProtocolHttpClient {
    suspend inline fun <reified TResponse> getEnvelope(
        url: String,
        timeoutMillis: Int
    ): HttpEnvelopeResponse<TResponse> = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            setRequestProperty("Accept", "application/json")
        }

        try {
            val statusCode = connection.responseCode
            val body = readBody(connection)
            HttpEnvelopeResponse(
                statusCode = statusCode,
                envelope = body.takeIf { it.isNotBlank() }?.let { raw -> runCatching { decodeEnvelope<TResponse>(raw) }.getOrNull() },
                rawBody = body
            )
        } finally {
            connection.disconnect()
        }
    }

    suspend inline fun <reified TRequest, reified TResponse> postEnvelope(
        url: String,
        envelope: ProtocolEnvelope<TRequest>,
        timeoutMillis: Int
    ): HttpEnvelopeResponse<TResponse> = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        try {
            connection.outputStream.use { output ->
                output.write(encodeEnvelope(envelope).toByteArray(Charsets.UTF_8))
            }

            val statusCode = connection.responseCode
            val body = readBody(connection)
            HttpEnvelopeResponse(
                statusCode = statusCode,
                envelope = body.takeIf { it.isNotBlank() }?.let { raw -> runCatching { decodeEnvelope<TResponse>(raw) }.getOrNull() },
                rawBody = body
            )
        } finally {
            connection.disconnect()
        }
    }

    @PublishedApi
    internal fun readBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
        if (stream == null) {
            return ""
        }

        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            reader.readText()
        }
    }
}
