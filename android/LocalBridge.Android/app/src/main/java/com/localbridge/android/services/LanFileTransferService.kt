package com.localbridge.android.services

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import androidx.core.net.toUri
import com.localbridge.android.core.AppConstants
import com.localbridge.android.core.logging.LoggerService
import com.localbridge.android.core.network.ProtocolHttpClient
import com.localbridge.android.core.network.local.LocalHttpHostService
import com.localbridge.android.core.network.local.LocalHttpRequest
import com.localbridge.android.core.network.local.LocalHttpResponse
import com.localbridge.android.core.protocol.FileTransferCancelRequestDto
import com.localbridge.android.core.protocol.FileTransferCancelResponseDto
import com.localbridge.android.core.protocol.FileTransferChunkDescriptorDto
import com.localbridge.android.core.protocol.FileTransferChunkResponseDto
import com.localbridge.android.core.protocol.FileTransferCompleteRequestDto
import com.localbridge.android.core.protocol.FileTransferCompleteResponseDto
import com.localbridge.android.core.protocol.FileTransferPrepareRequestDto
import com.localbridge.android.core.protocol.FileTransferPrepareResponseDto
import com.localbridge.android.core.protocol.ProtocolConstants
import com.localbridge.android.core.protocol.ProtocolEnvelope
import com.localbridge.android.core.protocol.ProtocolEnvelopeFactory
import com.localbridge.android.core.protocol.ProtocolEnvelopeValidator
import com.localbridge.android.core.protocol.ProtocolError
import com.localbridge.android.core.protocol.ProtocolErrorCodes
import com.localbridge.android.core.protocol.ProtocolJson
import com.localbridge.android.core.protocol.ProtocolMetadata
import com.localbridge.android.core.protocol.ProtocolPacketTypes
import com.localbridge.android.core.protocol.decodeEnvelope
import com.localbridge.android.core.storage.StorageDirectories
import com.localbridge.android.models.ConnectionLifecycleState
import com.localbridge.android.models.DevicePeer
import com.localbridge.android.models.LocalDeviceProfile
import com.localbridge.android.models.TransferDirection
import com.localbridge.android.models.TransferItem
import com.localbridge.android.models.TransferState
import com.localbridge.android.repositories.LocalDeviceProfileRepository
import com.localbridge.android.repositories.SettingsRepository
import com.localbridge.android.repositories.TransferRepository
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class LanFileTransferService(
    context: Context,
    private val storageDirectories: StorageDirectories,
    private val transferRepository: TransferRepository,
    private val connectionService: ConnectionService,
    private val bluetoothConnectionService: BluetoothConnectionService,
    private val localDeviceProfileRepository: LocalDeviceProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val localHttpHostService: LocalHttpHostService,
    private val loggerService: LoggerService
) : FileTransferService, BluetoothTransferEndpointHandler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver
    private val outgoingRuntimes = mutableMapOf<String, OutgoingTransferRuntime>()
    private val incomingRuntimes = mutableMapOf<String, IncomingTransferRuntime>()
    private val outgoingMapMutex = Mutex()
    private val incomingMapMutex = Mutex()
    private val incomingTempDirectory = File(storageDirectories.transfersDirectory, "incoming-temp")

    private var isStarted = false
    private var foregroundMonitorJob: Job? = null

    override val transfers: StateFlow<List<TransferItem>> = transferRepository.transfers

    override fun start() {
        if (isStarted) {
            return
        }

        isStarted = true
        scope.launch {
            storageDirectories.ensureAll()
            incomingTempDirectory.mkdirs()
            cleanupStaleTempFiles()
            val loaded = transferRepository.load()
            val normalized = loaded.map { normalizeRestoredTransfer(it) }
            if (normalized != loaded) {
                transferRepository.replaceAll(normalized)
            }

            localHttpHostService.register("POST", AppConstants.transferPreparePath, ::handlePrepareRequest)
            localHttpHostService.register("POST", AppConstants.transferChunkPath, ::handleChunkRequest)
            localHttpHostService.register("POST", AppConstants.transferCompletePath, ::handleCompleteRequest)
            localHttpHostService.register("POST", AppConstants.transferCancelPath, ::handleCancelRequest)

            val activeReceiveLocation = describeActiveReceiveLocation()
            loggerService.info(
                "[TRANSFER] Android LAN file transfer service started with ${transferRepository.transfers.value.size} stored transfer item(s). " +
                    "Incoming files will be saved to $activeReceiveLocation."
            )

            foregroundMonitorJob = scope.launch {
                transfers.collectLatest { items ->
                    syncForegroundTransferProtection(items)
                }
            }
        }
    }

    override fun queueFiles(uris: List<Uri>) {
        scope.launch {
            val session = getConnectedSession()
            if (session == null) {
                loggerService.warning("Android file selection ignored because no Windows peer is connected.")
                return@launch
            }

            uris.distinct().forEach { uri ->
                val selectedFile = resolveSelectedFile(uri)
                if (selectedFile == null) {
                    loggerService.warning("Skipped Android file selection because metadata could not be resolved for $uri.")
                    return@forEach
                }

                if (selectedFile.size <= 0L) {
                    loggerService.warning("Skipped empty Android transfer for ${selectedFile.fileName}.")
                    return@forEach
                }

                val maxFileSize = if (session.peer.transportMode == com.localbridge.android.models.AppConnectionMode.BluetoothFallback) {
                    AppConstants.bluetoothSmallFileTransferLimitBytes.toLong()
                } else {
                    AppConstants.transferMaxFileSizeBytes
                }

                if (selectedFile.size > maxFileSize) {
                    loggerService.warning(
                        "Skipped ${selectedFile.fileName} because it exceeds the ${if (session.peer.transportMode == com.localbridge.android.models.AppConnectionMode.BluetoothFallback) "300 MB Bluetooth" else "20 GB hotspot/LAN"} limit."
                    )
                    return@forEach
                }

                val chunkSize = if (session.peer.transportMode == com.localbridge.android.models.AppConnectionMode.BluetoothFallback) {
                    AppConstants.bluetoothTransferChunkSizeBytes
                } else {
                    AppConstants.transferChunkSizeBytes
                }

                val transfer = TransferItem(
                    id = UUID.randomUUID().toString().replace("-", ""),
                    fileName = selectedFile.fileName,
                    peerId = session.peer.id,
                    peerName = session.peer.displayName,
                    direction = TransferDirection.Outgoing,
                    kind = selectedFile.kind,
                    mimeType = selectedFile.mimeType,
                    totalBytes = selectedFile.size,
                    transferredBytes = 0,
                    status = TransferState.Queued,
                    createdAtUtc = Instant.now().toString(),
                    fileCreatedAtUtc = selectedFile.fileCreatedAtUtc,
                    sourcePath = selectedFile.uri.toString(),
                    savedPath = null,
                    lastError = "",
                    chunkSize = chunkSize,
                    totalChunks = calculateTotalChunks(selectedFile.size, chunkSize),
                    processedChunks = 0
                )

                transferRepository.append(transfer)
                val runtime = OutgoingTransferRuntime(transferId = transfer.id)
                outgoingMapMutex.withLock {
                    outgoingRuntimes[transfer.id] = runtime
                }

                loggerService.info("[TRANSFER] Queued Android outgoing transfer ${transfer.fileName} (${transfer.totalBytes} bytes) to ${transfer.peerName}.")
                startOutgoingWorker(runtime)
            }
        }
    }

    override fun pause(transferId: String) {
        scope.launch {
            val runtime = outgoingMapMutex.withLock { outgoingRuntimes[transferId] }
            if (runtime != null) {
                runtime.pauseRequested = true
                loggerService.info("[TRANSFER] Pause requested for Android transfer $transferId.")
            } else {
                updateTransfer(transferId) { transfer ->
                    if (transfer.canPause) {
                        transfer.copy(
                            status = TransferState.Paused,
                            speedBytesPerSecond = 0,
                            estimatedSecondsRemaining = null
                        )
                    } else {
                        transfer
                    }
                }
            }
        }
    }

    override fun resume(transferId: String) {
        scope.launch {
            val transfer = findTransfer(transferId) ?: return@launch
            if (transfer.direction != TransferDirection.Outgoing) {
                return@launch
            }

            val runtime = outgoingMapMutex.withLock {
                outgoingRuntimes.getOrPut(transferId) { OutgoingTransferRuntime(transferId = transferId) }
            }
            runtime.pauseRequested = false
            runtime.cancelRequested = false

            updateTransfer(transferId) { current ->
                current.copy(
                    status = TransferState.Queued,
                    lastError = "",
                    speedBytesPerSecond = 0,
                    estimatedSecondsRemaining = null
                )
            }

            loggerService.info("[TRANSFER] Resume requested for Android transfer ${transfer.fileName}.")
            startOutgoingWorker(runtime)
        }
    }

    override fun cancel(transferId: String) {
        scope.launch {
            val transfer = findTransfer(transferId) ?: return@launch
            when (transfer.direction) {
                TransferDirection.Outgoing -> cancelOutgoingTransfer(transfer)
                TransferDirection.Incoming -> cancelIncomingTransfer(transfer)
            }
        }
    }

    override fun clearHistory() {
        scope.launch {
            val currentItems = transfers.value
            val activeItems = currentItems.filter { transfer ->
                transfer.status in setOf(
                    TransferState.Queued,
                    TransferState.Preparing,
                    TransferState.Sending,
                    TransferState.Receiving,
                    TransferState.Paused
                )
            }
            val removedCount = currentItems.size - activeItems.size
            transferRepository.replaceAll(activeItems)
            loggerService.info("[HISTORY] Android transfer history cleared ($removedCount record(s) removed, ${activeItems.size} active item(s) kept).")
        }
    }

    private fun startOutgoingWorker(runtime: OutgoingTransferRuntime) {
        if (runtime.workerJob?.isActive == true) {
            return
        }

        runtime.workerJob = scope.launch {
            processOutgoingTransfer(runtime)
        }
    }

    private suspend fun processOutgoingTransfer(runtime: OutgoingTransferRuntime) {
        var restartRequested = false
        try {
            runtime.gate.withLock {
                val transfer = findTransfer(runtime.transferId) ?: return
                if (runtime.cancelRequested) {
                    finalizeOutgoingCancellation(runtime, transfer, "Canceled locally.")
                    return
                }

                if (runtime.pauseRequested) {
                    updateTransfer(runtime.transferId) { current ->
                        current.copy(
                            status = TransferState.Paused,
                            speedBytesPerSecond = 0,
                            estimatedSecondsRemaining = null
                        )
                    }
                    return
                }

                val session = getConnectedSessionForPeer(transfer.peerId)
                if (session == null) {
                    markOutgoingFailed(runtime.transferId, "peer_not_connected")
                    return
                }

                val sourceUri = transfer.sourcePath?.let(Uri::parse)
                if (sourceUri == null) {
                    markOutgoingFailed(runtime.transferId, "source_file_missing")
                    return
                }

                val selectedFile = resolveSelectedFile(sourceUri, fallbackName = transfer.fileName, fallbackSize = transfer.totalBytes)
                if (selectedFile == null) {
                    markOutgoingFailed(runtime.transferId, "source_file_missing")
                    return
                }

                val localDevice = localDeviceProfileRepository.getOrCreate()
                updateTransfer(runtime.transferId) { current ->
                    current.copy(
                        peerId = session.peer.id,
                        peerName = session.peer.displayName,
                        totalBytes = selectedFile.size,
                        status = TransferState.Preparing,
                        lastError = "",
                        startedAtUtc = current.startedAtUtc ?: Instant.now().toString(),
                        fileCreatedAtUtc = selectedFile.fileCreatedAtUtc,
                        chunkSize = if (current.chunkSize > 0) current.chunkSize else AppConstants.transferChunkSizeBytes,
                        totalChunks = calculateTotalChunks(
                            selectedFile.size,
                            if (current.chunkSize > 0) current.chunkSize else AppConstants.transferChunkSizeBytes
                        )
                    )
                }

                loggerService.info("[TRANSFER] Preparing Android transfer ${transfer.fileName} for ${session.peer.displayName}.")
                val currentTransfer = findTransfer(runtime.transferId) ?: return
                val prepareResponse = sendPrepare(currentTransfer, session, localDevice)
                if (!prepareResponse.accepted) {
                    val recovered = tryRecoverOutgoingTransfer(runtime, prepareResponse.failureReason ?: "prepare_failed")
                    if (!recovered) {
                        markOutgoingFailed(runtime.transferId, prepareResponse.failureReason ?: "prepare_failed")
                        return
                    }
                    restartRequested = true
                    return
                }

                var nextChunkIndex = findTransfer(runtime.transferId)?.processedChunks?.takeIf { it > 0 }
                    ?: prepareResponse.nextExpectedChunkIndex.coerceIn(0, currentTransfer.totalChunks)
                var transferredBytes = findTransfer(runtime.transferId)?.transferredBytes?.takeIf { it > 0L }
                    ?: prepareResponse.receivedBytes.coerceIn(0L, currentTransfer.totalBytes)
                runtime.restartMetrics(transferredBytes)
                runtime.recoveryAttempts = 0

                updateTransfer(runtime.transferId) { current ->
                    current.copy(
                        status = TransferState.Sending,
                        transferredBytes = transferredBytes,
                        processedChunks = nextChunkIndex,
                        speedBytesPerSecond = 0,
                        estimatedSecondsRemaining = estimateEta(current.totalBytes, transferredBytes, 0)
                    )
                }

                val inputHandle = openOutgoingInput(sourceUri, transferredBytes)
                if (inputHandle == null) {
                    val openFailureReason = if (transferredBytes > 0L) "unexpected_local_file_length" else "source_file_missing"
                    val recovered = tryRecoverOutgoingTransfer(runtime, openFailureReason)
                    if (!recovered) {
                        markOutgoingFailed(runtime.transferId, openFailureReason)
                    } else {
                        restartRequested = true
                    }
                    return
                }

                inputHandle.use { handle ->
                    val input = handle.input
                    while (nextChunkIndex < currentTransfer.totalChunks) {
                        if (runtime.cancelRequested) {
                            finalizeOutgoingCancellation(runtime, findTransfer(runtime.transferId) ?: currentTransfer, "Canceled locally.")
                            return
                        }

                        if (runtime.pauseRequested) {
                            updateTransfer(runtime.transferId) { current ->
                                current.copy(
                                    status = TransferState.Paused,
                                    speedBytesPerSecond = 0,
                                    estimatedSecondsRemaining = null
                                )
                            }
                            return
                        }

                        val latestTransfer = findTransfer(runtime.transferId) ?: return
                        val liveSession = getConnectedSessionForPeer(latestTransfer.peerId)
                        if (liveSession == null) {
                            markOutgoingFailed(runtime.transferId, "peer_not_connected")
                            return
                        }

                        val remaining = (latestTransfer.totalBytes - transferredBytes).coerceAtLeast(0L)
                        val chunkLength = minOf(latestTransfer.chunkSize.toLong(), remaining).toInt()
                        val chunkBytes = readChunk(input, chunkLength)
                        if (chunkBytes == null || chunkBytes.size != chunkLength) {
                            loggerService.warning(
                                "[TRANSFER] Android source stream ended early for ${latestTransfer.fileName} " +
                                    "at offset $transferredBytes/${latestTransfer.totalBytes} (wanted $chunkLength bytes)."
                            )
                            val recovered = tryRecoverOutgoingTransfer(runtime, "unexpected_local_file_length")
                            if (!recovered) {
                                markOutgoingFailed(runtime.transferId, "unexpected_local_file_length")
                            } else {
                                restartRequested = true
                            }
                            return
                        }

                        val chunkResponse = sendChunk(
                            transfer = latestTransfer,
                            session = liveSession,
                            localDevice = localDevice,
                            chunkIndex = nextChunkIndex,
                            chunkOffset = transferredBytes,
                            chunkBytes = chunkBytes
                        )

                        if (!chunkResponse.accepted) {
                            val recovered = tryRecoverOutgoingTransfer(
                                runtime,
                                chunkResponse.failureReason ?: "chunk_failed"
                            )
                            if (!recovered) {
                                markOutgoingFailed(runtime.transferId, chunkResponse.failureReason ?: "chunk_failed")
                                return
                            }
                            restartRequested = true
                            return
                        }

                        transferredBytes = chunkResponse.receivedBytes.coerceIn(0L, latestTransfer.totalBytes)
                        nextChunkIndex = chunkResponse.nextExpectedChunkIndex.coerceIn(0, latestTransfer.totalChunks)
                        val metrics = runtime.captureMetrics(transferredBytes, latestTransfer.totalBytes)
                        runtime.recoveryAttempts = 0

                        updateTransfer(runtime.transferId) { current ->
                            current.copy(
                                status = TransferState.Sending,
                                transferredBytes = transferredBytes,
                                processedChunks = nextChunkIndex,
                                speedBytesPerSecond = metrics.speedBytesPerSecond,
                                estimatedSecondsRemaining = metrics.estimatedSecondsRemaining
                            )
                        }
                    }
                }

                val completeResponse = sendComplete(findTransfer(runtime.transferId) ?: return, session, localDevice)
                if (!completeResponse.accepted) {
                    val recovered = tryRecoverOutgoingTransfer(
                        runtime,
                        completeResponse.failureReason ?: "complete_failed"
                    )
                    if (!recovered) {
                        markOutgoingFailed(runtime.transferId, completeResponse.failureReason ?: "complete_failed")
                        return
                    }
                    restartRequested = true
                    return
                }

                updateTransfer(runtime.transferId) { current ->
                    current.copy(
                        status = TransferState.Completed,
                        transferredBytes = current.totalBytes,
                        processedChunks = current.totalChunks,
                        completedAtUtc = Instant.now().toString(),
                        speedBytesPerSecond = 0,
                        estimatedSecondsRemaining = 0,
                        lastError = ""
                    )
                }

                loggerService.info("[TRANSFER] Completed Android outgoing transfer ${transfer.fileName}.")
                outgoingMapMutex.withLock {
                    outgoingRuntimes.remove(runtime.transferId)
                }
            }
        } catch (exception: Exception) {
            val recovered = tryRecoverOutgoingTransfer(runtime, exception.message ?: "transfer_failed")
            if (!recovered) {
                markOutgoingFailed(runtime.transferId, exception.message ?: "transfer_failed")
            } else {
                restartRequested = true
            }
        } finally {
            if (restartRequested && !runtime.cancelRequested && !runtime.pauseRequested) {
                runtime.workerJob = null
                startOutgoingWorker(runtime)
            }
        }
    }

    private suspend fun cancelOutgoingTransfer(transfer: TransferItem) {
        val runtime = outgoingMapMutex.withLock {
            outgoingRuntimes.getOrPut(transfer.id) { OutgoingTransferRuntime(transferId = transfer.id) }
        }
        runtime.pauseRequested = false
        runtime.cancelRequested = true

        if (runtime.workerJob?.isActive != true) {
            finalizeOutgoingCancellation(runtime, transfer, "Canceled locally.")
        }
    }

    private suspend fun finalizeOutgoingCancellation(
        runtime: OutgoingTransferRuntime,
        transfer: TransferItem,
        reason: String
    ) {
        updateTransfer(runtime.transferId) { current ->
            current.copy(
                status = TransferState.Canceled,
                completedAtUtc = Instant.now().toString(),
                speedBytesPerSecond = 0,
                estimatedSecondsRemaining = 0,
                lastError = reason
            )
        }

        val response = runCatching {
            sendCancel(
                transferId = transfer.id,
                peerId = transfer.peerId,
                reason = "sender_canceled"
            )
        }.getOrElse { exception ->
            loggerService.warning("[TRANSFER] Remote cancel notification failed for ${transfer.fileName}: ${exception.message}")
            createCancelFailureResponse(transfer.id, exception.message ?: "cancel_failed")
        }
        if (!response.accepted && !response.failureReason.equals("peer_not_connected", ignoreCase = true)) {
            loggerService.warning("[TRANSFER] Remote cancel notification failed for ${transfer.fileName}: ${response.failureReason}")
        }

        outgoingMapMutex.withLock {
            outgoingRuntimes.remove(runtime.transferId)
        }
    }

    private suspend fun markOutgoingFailed(transferId: String, reason: String) {
        updateTransfer(transferId) { current ->
            current.copy(
                status = TransferState.Failed,
                speedBytesPerSecond = 0,
                estimatedSecondsRemaining = null,
                lastError = reason
            )
        }
        loggerService.warning("[TRANSFER] Android transfer $transferId failed: $reason.")
    }

    private suspend fun tryRecoverOutgoingTransfer(
        runtime: OutgoingTransferRuntime,
        reason: String
    ): Boolean {
        if (!isRecoverableTransferFailure(reason) ||
            runtime.recoveryAttempts >= AppConstants.transferRecoveryRetryLimit ||
            runtime.cancelRequested ||
            runtime.pauseRequested
        ) {
            return false
        }

        runtime.recoveryAttempts += 1
        loggerService.warning(
            "[TRANSFER] Android recovery ${runtime.recoveryAttempts}/${AppConstants.transferRecoveryRetryLimit} " +
                "for ${runtime.transferId}: $reason."
        )

        val transfer = findTransfer(runtime.transferId) ?: return false
        val session = getConnectedSessionForPeer(transfer.peerId) ?: return false
        val localDevice = localDeviceProfileRepository.getOrCreate()
        val prepareResponse = sendPrepare(transfer, session, localDevice)
        if (!prepareResponse.accepted) {
            loggerService.warning(
                "[TRANSFER] Android recovery prepare failed for ${transfer.fileName}: " +
                    (prepareResponse.failureReason ?: "prepare_failed")
            )
            return false
        }

        val resumedBytes = prepareResponse.receivedBytes.coerceIn(0L, transfer.totalBytes)
        val resumedChunks = prepareResponse.nextExpectedChunkIndex.coerceIn(0, transfer.totalChunks)
        runtime.restartMetrics(resumedBytes)

        updateTransfer(runtime.transferId) { current ->
            current.copy(
                status = TransferState.Sending,
                transferredBytes = resumedBytes,
                processedChunks = resumedChunks,
                speedBytesPerSecond = 0,
                estimatedSecondsRemaining = estimateEta(current.totalBytes, resumedBytes, 0),
                lastError = ""
            )
        }

        return true
    }

    private fun isRecoverableTransferFailure(reason: String?): Boolean {
        if (reason.isNullOrBlank()) {
            return true
        }

        return reason.startsWith("chunk_http_", ignoreCase = true) ||
            reason.startsWith("prepare_http_", ignoreCase = true) ||
            reason.startsWith("complete_http_", ignoreCase = true) ||
            reason.contains("timeout", ignoreCase = true) ||
            reason.contains("timed out", ignoreCase = true) ||
            reason.contains("canceled", ignoreCase = true) ||
            reason == "unexpected_local_file_length" ||
            reason == "transfer_not_found" ||
            reason == "unexpected_chunk_position" ||
            reason == "peer_not_connected"
    }

    private suspend fun cancelIncomingTransfer(transfer: TransferItem) {
        val runtime = incomingMapMutex.withLock { incomingRuntimes[transfer.id] }
        if (runtime == null) {
            updateTransfer(transfer.id) { current ->
                current.copy(
                    status = TransferState.Canceled,
                    completedAtUtc = Instant.now().toString(),
                    speedBytesPerSecond = 0,
                    estimatedSecondsRemaining = 0,
                    lastError = "Canceled locally."
                )
            }
            return
        }

        cancelIncomingRuntime(runtime, "Canceled locally.", notifyRemote = true)
    }

    private suspend fun handlePrepareRequest(request: LocalHttpRequest): LocalHttpResponse {
        val localDevice = localDeviceProfileRepository.getOrCreate()
        val envelope = runCatching {
            ProtocolJson.format.decodeFromString(
                ProtocolEnvelope.serializer(FileTransferPrepareRequestDto.serializer()),
                request.bodyText
            )
        }.getOrNull()
        val payload = envelope?.payload
        val correlationId = envelope?.meta?.messageId
        val validation = ProtocolEnvelopeValidator.validate(
            envelope,
            expectedPacketTypes = *arrayOf(ProtocolPacketTypes.transferPrepareRequest)
        )

        if (!validation.isValid) {
            return protocolErrorResponse(
                statusCode = if (validation.errorCode == ProtocolErrorCodes.protocolMismatch) 426 else 400,
                packetType = ProtocolPacketTypes.transferPrepareResponse,
                payload = createPrepareFailureResponse(
                    payload?.transferId.orEmpty(),
                    validation.errorCode ?: ProtocolErrorCodes.invalidTransferPrepare,
                    localDevice
                ),
                payloadSerializer = FileTransferPrepareResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = payload?.senderId,
                sessionId = payload?.sessionId,
                correlationId = correlationId,
                errorCode = validation.errorCode ?: ProtocolErrorCodes.invalidTransferPrepare,
                errorMessage = validation.errorMessage ?: "Transfer prepare request is malformed."
            )
        }

        val packet = envelope!!.payload!!
        if (packet.transferId.isBlank() ||
            packet.sessionId.isBlank() ||
            packet.senderId.isBlank() ||
            packet.fileName.isBlank() ||
            packet.fileSize <= 0L ||
            packet.chunkSize <= 0 ||
            packet.totalChunks <= 0
        ) {
            return protocolErrorResponse(
                statusCode = 400,
                packetType = ProtocolPacketTypes.transferPrepareResponse,
                payload = createPrepareFailureResponse(packet.transferId, ProtocolErrorCodes.invalidTransferPrepare, localDevice),
                payloadSerializer = FileTransferPrepareResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = packet.senderId,
                sessionId = packet.sessionId,
                correlationId = correlationId,
                errorCode = ProtocolErrorCodes.invalidTransferPrepare,
                errorMessage = "Transfer prepare request is missing required metadata."
            )
        }

        val session = getConnectedSessionForIncoming(packet.sessionId, packet.senderId)
        if (session == null) {
            return protocolErrorResponse(
                statusCode = 404,
                packetType = ProtocolPacketTypes.transferPrepareResponse,
                payload = createPrepareFailureResponse(packet.transferId, ProtocolErrorCodes.sessionNotFound, localDevice),
                payloadSerializer = FileTransferPrepareResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = packet.senderId,
                sessionId = packet.sessionId,
                correlationId = correlationId,
                errorCode = ProtocolErrorCodes.sessionNotFound,
                errorMessage = "The transfer session is not active on this host."
            )
        }

        val response = try {
            prepareIncomingTransfer(packet, session, localDevice)
        } catch (exception: Exception) {
            loggerService.warning("[TRANSFER] Android transfer prepare failed: ${exception.message}")
            createPrepareFailureResponse(packet.transferId, exception.message ?: "prepare_failed", localDevice)
        }

        return if (response.accepted) {
            protocolSuccessResponse(
                statusCode = 200,
                packetType = ProtocolPacketTypes.transferPrepareResponse,
                payload = response,
                payloadSerializer = FileTransferPrepareResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = packet.senderId,
                sessionId = packet.sessionId,
                correlationId = correlationId
            )
        } else {
            protocolErrorResponse(
                statusCode = 409,
                packetType = ProtocolPacketTypes.transferPrepareResponse,
                payload = response,
                payloadSerializer = FileTransferPrepareResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = packet.senderId,
                sessionId = packet.sessionId,
                correlationId = correlationId,
                errorCode = response.failureReason ?: ProtocolErrorCodes.invalidTransferPrepare,
                errorMessage = response.failureReason ?: "Transfer prepare failed."
            )
        }
    }

    private suspend fun handleChunkRequest(request: LocalHttpRequest): LocalHttpResponse {
        val localDevice = localDeviceProfileRepository.getOrCreate()
        val multipart = parseMultipartRequest(request)
        if (multipart == null) {
            return protocolErrorResponse(
                statusCode = 400,
                packetType = ProtocolPacketTypes.transferChunkResponse,
                payload = createChunkFailureResponse("", -1, ProtocolErrorCodes.invalidTransferChunk),
                payloadSerializer = FileTransferChunkResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = null,
                sessionId = null,
                correlationId = null,
                errorCode = ProtocolErrorCodes.invalidTransferChunk,
                errorMessage = "Transfer chunk requests must use multipart/form-data."
            )
        }

        val descriptorEnvelope = runCatching {
            ProtocolJson.format.decodeFromString(
                ProtocolEnvelope.serializer(FileTransferChunkDescriptorDto.serializer()),
                multipart.metadataJson
            )
        }.getOrNull()
        val descriptor = descriptorEnvelope?.payload
        val correlationId = descriptorEnvelope?.meta?.messageId
        val validation = ProtocolEnvelopeValidator.validate(
            descriptorEnvelope,
            expectedPacketTypes = *arrayOf(ProtocolPacketTypes.transferChunkRequest)
        )
        if (!validation.isValid) {
            return protocolErrorResponse(
                statusCode = if (validation.errorCode == ProtocolErrorCodes.protocolMismatch) 426 else 400,
                packetType = ProtocolPacketTypes.transferChunkResponse,
                payload = createChunkFailureResponse(
                    descriptor?.transferId.orEmpty(),
                    descriptor?.chunkIndex ?: -1,
                    validation.errorCode ?: ProtocolErrorCodes.invalidTransferChunk
                ),
                payloadSerializer = FileTransferChunkResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = descriptor?.senderId,
                sessionId = descriptor?.sessionId,
                correlationId = correlationId,
                errorCode = validation.errorCode ?: ProtocolErrorCodes.invalidTransferChunk,
                errorMessage = validation.errorMessage ?: "Chunk metadata is malformed."
            )
        }

        val packet = descriptorEnvelope!!.payload!!
        if (packet.transferId.isBlank() ||
            packet.sessionId.isBlank() ||
            packet.senderId.isBlank() ||
            packet.chunkIndex < 0 ||
            packet.chunkOffset < 0L ||
            packet.chunkLength <= 0 ||
            multipart.chunkBytes.isEmpty()
        ) {
            return protocolErrorResponse(
                statusCode = 400,
                packetType = ProtocolPacketTypes.transferChunkResponse,
                payload = createChunkFailureResponse(packet.transferId, packet.chunkIndex, ProtocolErrorCodes.invalidTransferChunk),
                payloadSerializer = FileTransferChunkResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = packet.senderId,
                sessionId = packet.sessionId,
                correlationId = correlationId,
                errorCode = ProtocolErrorCodes.invalidTransferChunk,
                errorMessage = "Chunk metadata or binary content is missing."
            )
        }

        val session = getConnectedSessionForIncoming(packet.sessionId, packet.senderId)
        if (session == null) {
            return protocolErrorResponse(
                statusCode = 404,
                packetType = ProtocolPacketTypes.transferChunkResponse,
                payload = createChunkFailureResponse(packet.transferId, packet.chunkIndex, ProtocolErrorCodes.sessionNotFound),
                payloadSerializer = FileTransferChunkResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = packet.senderId,
                sessionId = packet.sessionId,
                correlationId = correlationId,
                errorCode = ProtocolErrorCodes.sessionNotFound,
                errorMessage = "The transfer session is not active on this host."
            )
        }

        val response = try {
            receiveChunk(packet, multipart.chunkBytes, session)
        } catch (exception: Exception) {
            loggerService.warning("[TRANSFER] Android transfer chunk failed: ${exception.message}")
            createChunkFailureResponse(packet.transferId, packet.chunkIndex, exception.message ?: "chunk_failed")
        }

        return if (response.accepted) {
            protocolSuccessResponse(
                statusCode = 200,
                packetType = ProtocolPacketTypes.transferChunkResponse,
                payload = response,
                payloadSerializer = FileTransferChunkResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = packet.senderId,
                sessionId = packet.sessionId,
                correlationId = correlationId
            )
        } else {
            protocolErrorResponse(
                statusCode = 409,
                packetType = ProtocolPacketTypes.transferChunkResponse,
                payload = response,
                payloadSerializer = FileTransferChunkResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = packet.senderId,
                sessionId = packet.sessionId,
                correlationId = correlationId,
                errorCode = response.failureReason ?: ProtocolErrorCodes.invalidTransferChunk,
                errorMessage = response.failureReason ?: "Transfer chunk was rejected."
            )
        }
    }

    private suspend fun handleCompleteRequest(request: LocalHttpRequest): LocalHttpResponse {
        val localDevice = localDeviceProfileRepository.getOrCreate()
        val envelope = runCatching {
            ProtocolJson.format.decodeFromString(
                ProtocolEnvelope.serializer(FileTransferCompleteRequestDto.serializer()),
                request.bodyText
            )
        }.getOrNull()
        val payload = envelope?.payload
        val correlationId = envelope?.meta?.messageId
        val validation = ProtocolEnvelopeValidator.validate(
            envelope,
            expectedPacketTypes = *arrayOf(ProtocolPacketTypes.transferCompleteRequest)
        )

        if (!validation.isValid) {
            return protocolErrorResponse(
                statusCode = if (validation.errorCode == ProtocolErrorCodes.protocolMismatch) 426 else 400,
                packetType = ProtocolPacketTypes.transferCompleteResponse,
                payload = createCompleteFailureResponse(payload?.transferId.orEmpty(), validation.errorCode ?: ProtocolErrorCodes.invalidTransferComplete),
                payloadSerializer = FileTransferCompleteResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = payload?.senderId,
                sessionId = payload?.sessionId,
                correlationId = correlationId,
                errorCode = validation.errorCode ?: ProtocolErrorCodes.invalidTransferComplete,
                errorMessage = validation.errorMessage ?: "Transfer completion request is malformed."
            )
        }

        val packet = envelope!!.payload!!
        if (packet.transferId.isBlank() || packet.sessionId.isBlank() || packet.senderId.isBlank()) {
            return protocolErrorResponse(
                statusCode = 400,
                packetType = ProtocolPacketTypes.transferCompleteResponse,
                payload = createCompleteFailureResponse(packet.transferId, ProtocolErrorCodes.invalidTransferComplete),
                payloadSerializer = FileTransferCompleteResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = packet.senderId,
                sessionId = packet.sessionId,
                correlationId = correlationId,
                errorCode = ProtocolErrorCodes.invalidTransferComplete,
                errorMessage = "Transfer completion request is missing required metadata."
            )
        }

        val session = getConnectedSessionForIncoming(packet.sessionId, packet.senderId)
        if (session == null) {
            return protocolErrorResponse(
                statusCode = 404,
                packetType = ProtocolPacketTypes.transferCompleteResponse,
                payload = createCompleteFailureResponse(packet.transferId, ProtocolErrorCodes.sessionNotFound),
                payloadSerializer = FileTransferCompleteResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = packet.senderId,
                sessionId = packet.sessionId,
                correlationId = correlationId,
                errorCode = ProtocolErrorCodes.sessionNotFound,
                errorMessage = "The transfer session is not active on this host."
            )
        }

        val response = try {
            completeIncomingTransfer(packet)
        } catch (exception: Exception) {
            loggerService.warning("[TRANSFER] Android transfer completion failed: ${exception.message}")
            createCompleteFailureResponse(packet.transferId, exception.message ?: "complete_failed")
        }

        return if (response.accepted) {
            protocolSuccessResponse(
                statusCode = 200,
                packetType = ProtocolPacketTypes.transferCompleteResponse,
                payload = response,
                payloadSerializer = FileTransferCompleteResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = packet.senderId,
                sessionId = packet.sessionId,
                correlationId = correlationId
            )
        } else {
            protocolErrorResponse(
                statusCode = 409,
                packetType = ProtocolPacketTypes.transferCompleteResponse,
                payload = response,
                payloadSerializer = FileTransferCompleteResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = packet.senderId,
                sessionId = packet.sessionId,
                correlationId = correlationId,
                errorCode = response.failureReason ?: ProtocolErrorCodes.invalidTransferComplete,
                errorMessage = response.failureReason ?: "Transfer completion failed."
            )
        }
    }

    private suspend fun handleCancelRequest(request: LocalHttpRequest): LocalHttpResponse {
        val localDevice = localDeviceProfileRepository.getOrCreate()
        val envelope = runCatching {
            ProtocolJson.format.decodeFromString(
                ProtocolEnvelope.serializer(FileTransferCancelRequestDto.serializer()),
                request.bodyText
            )
        }.getOrNull()
        val payload = envelope?.payload
        val correlationId = envelope?.meta?.messageId
        val validation = ProtocolEnvelopeValidator.validate(
            envelope,
            expectedPacketTypes = *arrayOf(ProtocolPacketTypes.transferCancelRequest)
        )

        if (!validation.isValid) {
            return protocolErrorResponse(
                statusCode = if (validation.errorCode == ProtocolErrorCodes.protocolMismatch) 426 else 400,
                packetType = ProtocolPacketTypes.transferCancelResponse,
                payload = createCancelFailureResponse(payload?.transferId.orEmpty(), validation.errorCode ?: ProtocolErrorCodes.invalidTransferCancel),
                payloadSerializer = FileTransferCancelResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = payload?.senderId,
                sessionId = payload?.sessionId,
                correlationId = correlationId,
                errorCode = validation.errorCode ?: ProtocolErrorCodes.invalidTransferCancel,
                errorMessage = validation.errorMessage ?: "Transfer cancel request is malformed."
            )
        }

        val packet = envelope!!.payload!!
        if (packet.transferId.isBlank() || packet.sessionId.isBlank() || packet.senderId.isBlank()) {
            return protocolErrorResponse(
                statusCode = 400,
                packetType = ProtocolPacketTypes.transferCancelResponse,
                payload = createCancelFailureResponse(packet.transferId, ProtocolErrorCodes.invalidTransferCancel),
                payloadSerializer = FileTransferCancelResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = packet.senderId,
                sessionId = packet.sessionId,
                correlationId = correlationId,
                errorCode = ProtocolErrorCodes.invalidTransferCancel,
                errorMessage = "Transfer cancel request is missing required metadata."
            )
        }

        val session = getConnectedSessionForIncoming(packet.sessionId, packet.senderId)
        if (session == null) {
            return protocolErrorResponse(
                statusCode = 404,
                packetType = ProtocolPacketTypes.transferCancelResponse,
                payload = createCancelFailureResponse(packet.transferId, ProtocolErrorCodes.sessionNotFound),
                payloadSerializer = FileTransferCancelResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = packet.senderId,
                sessionId = packet.sessionId,
                correlationId = correlationId,
                errorCode = ProtocolErrorCodes.sessionNotFound,
                errorMessage = "The transfer session is not active on this host."
            )
        }

        val response = try {
            cancelIncomingTransferFromRemote(packet)
        } catch (exception: Exception) {
            loggerService.warning("[TRANSFER] Android transfer cancel failed: ${exception.message}")
            createCancelFailureResponse(packet.transferId, exception.message ?: "cancel_failed")
        }

        return if (response.accepted) {
            protocolSuccessResponse(
                statusCode = 200,
                packetType = ProtocolPacketTypes.transferCancelResponse,
                payload = response,
                payloadSerializer = FileTransferCancelResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = packet.senderId,
                sessionId = packet.sessionId,
                correlationId = correlationId
            )
        } else {
            protocolErrorResponse(
                statusCode = 409,
                packetType = ProtocolPacketTypes.transferCancelResponse,
                payload = response,
                payloadSerializer = FileTransferCancelResponseDto.serializer(),
                localDevice = localDevice,
                receiverDeviceId = packet.senderId,
                sessionId = packet.sessionId,
                correlationId = correlationId,
                errorCode = response.failureReason ?: ProtocolErrorCodes.invalidTransferCancel,
                errorMessage = response.failureReason ?: "Transfer cancel failed."
            )
        }
    }

    private suspend fun prepareIncomingTransfer(
        request: FileTransferPrepareRequestDto,
        session: ConnectedSession,
        localDevice: LocalDeviceProfile
    ): FileTransferPrepareResponseDto {
        if (request.fileSize > AppConstants.transferMaxFileSizeBytes) {
            return createPrepareFailureResponse(request.transferId, "file_size_not_supported", localDevice)
        }

        val existingRuntime = incomingMapMutex.withLock { incomingRuntimes[request.transferId] }
        if (existingRuntime != null) {
            return existingRuntime.gate.withLock {
                if (!existingRuntime.senderId.equals(request.senderId, ignoreCase = true)) {
                    return@withLock createPrepareFailureResponse(request.transferId, "transfer_sender_mismatch", localDevice)
                }

                val current = findTransfer(request.transferId)
                FileTransferPrepareResponseDto(
                    accepted = true,
                    transferId = request.transferId,
                    status = mapStateToProtocol(current?.status ?: TransferState.Receiving),
                    failureReason = null,
                    nextExpectedChunkIndex = existingRuntime.nextExpectedChunkIndex,
                    receivedBytes = current?.transferredBytes ?: 0L,
                    receiverDeviceId = localDevice.deviceId,
                    receiverDeviceName = localDevice.deviceName,
                    suggestedFilePath = existingRuntime.finalFile.absolutePath,
                    respondedAtUtc = Instant.now().toString()
                )
            }
        }

        val finalFile = ensureUniqueFile(File(resolveReceiveDirectory(), sanitizeFileName(request.fileName)))
        val tempFile = File(incomingTempDirectory, "${request.transferId}.part")
        val accessFile = RandomAccessFile(tempFile, "rw")

        val transfer = TransferItem(
            id = request.transferId,
            fileName = request.fileName,
            peerId = session.peer.id,
            peerName = session.peer.displayName,
            direction = TransferDirection.Incoming,
            kind = request.kind,
            mimeType = request.mimeType,
            totalBytes = request.fileSize,
            transferredBytes = 0,
            status = TransferState.Receiving,
            createdAtUtc = Instant.now().toString(),
            fileCreatedAtUtc = request.fileCreatedAtUtc,
            startedAtUtc = Instant.now().toString(),
            chunkSize = request.chunkSize,
            totalChunks = request.totalChunks,
            processedChunks = 0
        )

        val runtime = IncomingTransferRuntime(
            transferId = request.transferId,
            sessionId = request.sessionId,
            senderId = request.senderId,
            peerName = session.peer.displayName,
            tempFile = tempFile,
            finalFile = finalFile,
            accessFile = accessFile
        )
        runtime.restartMetrics(0)

        incomingMapMutex.withLock {
            incomingRuntimes[request.transferId] = runtime
        }
        transferRepository.append(transfer)
        loggerService.info(
            "[TRANSFER] Android receiver created runtime for ${request.fileName} " +
                "(transfer=${request.transferId}, session=${request.sessionId}, chunks=${request.totalChunks}, bytes=${request.fileSize})."
        )

        loggerService.info(
            "[TRANSFER] Accepted Android incoming transfer ${request.fileName} from ${session.peer.displayName}. " +
                "Target path: ${finalFile.absolutePath}."
        )
        return FileTransferPrepareResponseDto(
            accepted = true,
            transferId = request.transferId,
            status = ProtocolConstants.transferStateReceiving,
            failureReason = null,
            nextExpectedChunkIndex = 0,
            receivedBytes = 0L,
            receiverDeviceId = localDevice.deviceId,
            receiverDeviceName = localDevice.deviceName,
            suggestedFilePath = finalFile.absolutePath,
            respondedAtUtc = Instant.now().toString()
        )
    }

    private suspend fun receiveChunk(
        descriptor: FileTransferChunkDescriptorDto,
        chunkBytes: ByteArray,
        session: ConnectedSession
    ): FileTransferChunkResponseDto {
        val runtime = incomingMapMutex.withLock { incomingRuntimes[descriptor.transferId] }
            ?: run {
                loggerService.warning(
                    "[TRANSFER] Android receiver lost runtime before chunk ${descriptor.chunkIndex} " +
                        "for transfer ${descriptor.transferId} (session=${descriptor.sessionId}, sender=${descriptor.senderId})."
                )
                return createChunkFailureResponse(descriptor.transferId, descriptor.chunkIndex, "transfer_not_found")
            }

        return runtime.gate.withLock {
            val transfer = findTransfer(descriptor.transferId)
                ?: run {
                    loggerService.warning(
                        "[TRANSFER] Android receiver could not find persisted transfer item for ${descriptor.transferId} " +
                            "while processing chunk ${descriptor.chunkIndex}."
                    )
                    return@withLock createChunkFailureResponse(descriptor.transferId, descriptor.chunkIndex, "transfer_not_found")
                }

            if (!runtime.senderId.equals(descriptor.senderId, ignoreCase = true)) {
                return@withLock createChunkFailureResponse(descriptor.transferId, descriptor.chunkIndex, "transfer_sender_mismatch")
            }

            if (descriptor.chunkIndex != runtime.nextExpectedChunkIndex || descriptor.chunkOffset != transfer.transferredBytes) {
                return@withLock FileTransferChunkResponseDto(
                    accepted = false,
                    transferId = descriptor.transferId,
                    chunkIndex = descriptor.chunkIndex,
                    status = mapStateToProtocol(transfer.status),
                    failureReason = "unexpected_chunk_position",
                    nextExpectedChunkIndex = runtime.nextExpectedChunkIndex,
                    receivedBytes = transfer.transferredBytes,
                    respondedAtUtc = Instant.now().toString()
                )
            }

            if (chunkBytes.size != descriptor.chunkLength) {
                return@withLock createChunkFailureResponse(descriptor.transferId, descriptor.chunkIndex, "incomplete_chunk_payload")
            }

            runtime.accessFile.seek(descriptor.chunkOffset)
            runtime.accessFile.write(chunkBytes)
            runtime.nextExpectedChunkIndex += 1
            if (runtime.nextExpectedChunkIndex % AppConstants.transferIncomingSyncChunkInterval == 0) {
                runtime.accessFile.fd.sync()
            }

            val newTransferredBytes = (transfer.transferredBytes + chunkBytes.size).coerceAtMost(transfer.totalBytes)
            val metrics = runtime.captureMetrics(newTransferredBytes, transfer.totalBytes)
            updateTransfer(descriptor.transferId) { current ->
                current.copy(
                    peerId = session.peer.id,
                    peerName = session.peer.displayName,
                    status = TransferState.Receiving,
                    transferredBytes = newTransferredBytes,
                    processedChunks = runtime.nextExpectedChunkIndex,
                    speedBytesPerSecond = metrics.speedBytesPerSecond,
                    estimatedSecondsRemaining = metrics.estimatedSecondsRemaining
                )
            }

            FileTransferChunkResponseDto(
                accepted = true,
                transferId = descriptor.transferId,
                chunkIndex = descriptor.chunkIndex,
                status = ProtocolConstants.transferStateReceiving,
                failureReason = null,
                nextExpectedChunkIndex = runtime.nextExpectedChunkIndex,
                receivedBytes = newTransferredBytes,
                respondedAtUtc = Instant.now().toString()
            )
        }
    }

    private suspend fun completeIncomingTransfer(request: FileTransferCompleteRequestDto): FileTransferCompleteResponseDto {
        val runtime = incomingMapMutex.withLock { incomingRuntimes[request.transferId] }
            ?: run {
                loggerService.warning(
                    "[TRANSFER] Android receiver could not complete ${request.transferId} because the runtime was already missing."
                )
                return createCompleteFailureResponse(request.transferId, "transfer_not_found")
            }

        return runtime.gate.withLock {
            val transfer = findTransfer(request.transferId)
                ?: run {
                    loggerService.warning(
                        "[TRANSFER] Android receiver could not complete ${request.transferId} because the transfer record was already missing."
                    )
                    return@withLock createCompleteFailureResponse(request.transferId, "transfer_not_found")
                }

            if (!runtime.senderId.equals(request.senderId, ignoreCase = true)) {
                return@withLock createCompleteFailureResponse(request.transferId, "transfer_sender_mismatch")
            }

            if (request.totalBytes != transfer.totalBytes ||
                request.totalChunks != transfer.totalChunks ||
                transfer.transferredBytes != transfer.totalBytes
            ) {
                return@withLock createCompleteFailureResponse(request.transferId, "transfer_totals_mismatch")
            }

            runtime.accessFile.fd.sync()
            runtime.accessFile.close()
            val savedDestination = persistIncomingTempToConfiguredLocation(
                transfer = transfer,
                runtime = runtime
            )
            runtime.tempFile.delete()

            updateTransfer(request.transferId) { current ->
                current.copy(
                    status = TransferState.Completed,
                    completedAtUtc = Instant.now().toString(),
                    savedPath = savedDestination.savedPath,
                    speedBytesPerSecond = 0,
                    estimatedSecondsRemaining = 0,
                    lastError = ""
                )
            }

            incomingMapMutex.withLock {
                incomingRuntimes.remove(request.transferId)
            }
            loggerService.info(
                "[TRANSFER] Completed Android incoming transfer ${transfer.fileName} from ${transfer.peerName}. " +
                    "Saved to ${savedDestination.logLabel}."
            )

            FileTransferCompleteResponseDto(
                accepted = true,
                transferId = request.transferId,
                status = ProtocolConstants.transferStateCompleted,
                failureReason = null,
                savedFilePath = savedDestination.savedPath,
                completedAtUtc = Instant.now().toString()
            )
        }
    }

    private suspend fun cancelIncomingTransferFromRemote(request: FileTransferCancelRequestDto): FileTransferCancelResponseDto {
        val runtime = incomingMapMutex.withLock { incomingRuntimes[request.transferId] }
            ?: run {
                loggerService.warning(
                    "[TRANSFER] Android receiver got remote cancel for ${request.transferId}, but no runtime was active."
                )
                return createCancelFailureResponse(request.transferId, "transfer_not_found")
            }

        if (!runtime.senderId.equals(request.senderId, ignoreCase = true)) {
            return createCancelFailureResponse(request.transferId, "transfer_sender_mismatch")
        }

        cancelIncomingRuntime(runtime, request.reason, notifyRemote = false)
        return FileTransferCancelResponseDto(
            accepted = true,
            transferId = request.transferId,
            status = ProtocolConstants.transferStateCanceled,
            failureReason = null,
            canceledAtUtc = Instant.now().toString()
        )
    }

    private suspend fun cancelIncomingRuntime(
        runtime: IncomingTransferRuntime,
        reason: String,
        notifyRemote: Boolean
    ) {
        runtime.gate.withLock {
            runCatching { runtime.accessFile.close() }
            if (runtime.tempFile.exists()) {
                runtime.tempFile.delete()
            }
            updateTransfer(runtime.transferId) { current ->
                current.copy(
                    status = TransferState.Canceled,
                    completedAtUtc = Instant.now().toString(),
                    speedBytesPerSecond = 0,
                    estimatedSecondsRemaining = 0,
                    lastError = reason
                )
            }
        }

        incomingMapMutex.withLock {
            incomingRuntimes.remove(runtime.transferId)
        }

        if (notifyRemote) {
            val transfer = findTransfer(runtime.transferId)
            if (transfer != null) {
                val response = runCatching {
                    sendCancel(runtime.transferId, transfer.peerId, "receiver_canceled")
                }.getOrElse { exception ->
                    loggerService.warning("Could not notify remote peer that transfer ${transfer.fileName} was canceled: ${exception.message}")
                    createCancelFailureResponse(runtime.transferId, exception.message ?: "cancel_failed")
                }
                if (!response.accepted) {
                    loggerService.warning("Could not notify remote peer that transfer ${transfer.fileName} was canceled: ${response.failureReason}")
                }
            }
        }
    }

    private suspend fun sendPrepare(
        transfer: TransferItem,
        session: ConnectedSession,
        localDevice: LocalDeviceProfile
    ): FileTransferPrepareResponseDto {
        val requestDto = FileTransferPrepareRequestDto(
            transferId = transfer.id,
            sessionId = session.sessionId,
            senderId = localDevice.deviceId,
            senderName = localDevice.deviceName,
            receiverId = session.peer.id,
            fileName = transfer.fileName,
            fileSize = transfer.totalBytes,
            mimeType = transfer.mimeType,
            kind = transfer.kind,
            fileCreatedAtUtc = transfer.fileCreatedAtUtc,
            chunkSize = transfer.chunkSize,
            totalChunks = transfer.totalChunks,
            requestedAtUtc = Instant.now().toString()
        )

        if (session.peer.transportMode == com.localbridge.android.models.AppConnectionMode.BluetoothFallback) {
            return bluetoothConnectionService.sendTransferPrepare(requestDto)
        }

        val envelope = ProtocolEnvelopeFactory.create(
            packetType = ProtocolPacketTypes.transferPrepareRequest,
            payload = requestDto,
            senderDeviceId = localDevice.deviceId,
            receiverDeviceId = session.peer.id,
            sessionId = session.sessionId,
            messageId = transfer.id,
            sentAtUtc = Instant.now().toString()
        )

        val response = ProtocolHttpClient.postEnvelope<FileTransferPrepareRequestDto, FileTransferPrepareResponseDto>(
            url = buildPeerUrl(session.peer, AppConstants.transferPreparePath),
            envelope = envelope,
            timeoutMillis = AppConstants.transferRequestTimeoutMillis
        )
        val validation = ProtocolEnvelopeValidator.validate(response.envelope, expectedPacketTypes = *arrayOf(ProtocolPacketTypes.transferPrepareResponse))
        return if (!validation.isValid || response.statusCode !in 200..299 || response.envelope?.payload == null) {
            createPrepareFailureResponse(
                transfer.id,
                response.envelope?.error?.code ?: validation.errorCode ?: "prepare_http_${response.statusCode}",
                localDevice
            )
        } else {
            response.envelope.payload
        }
    }

    private suspend fun sendChunk(
        transfer: TransferItem,
        session: ConnectedSession,
        localDevice: LocalDeviceProfile,
        chunkIndex: Int,
        chunkOffset: Long,
        chunkBytes: ByteArray
    ): FileTransferChunkResponseDto {
        if (session.peer.transportMode == com.localbridge.android.models.AppConnectionMode.BluetoothFallback) {
            return bluetoothConnectionService.sendTransferChunk(
                descriptor = FileTransferChunkDescriptorDto(
                    transferId = transfer.id,
                    sessionId = session.sessionId,
                    senderId = localDevice.deviceId,
                    chunkIndex = chunkIndex,
                    chunkOffset = chunkOffset,
                    chunkLength = chunkBytes.size
                ),
                chunkPayload = chunkBytes
            )
        }

        val metadataEnvelope = ProtocolEnvelopeFactory.create(
            packetType = ProtocolPacketTypes.transferChunkRequest,
            payload = FileTransferChunkDescriptorDto(
                transferId = transfer.id,
                sessionId = session.sessionId,
                senderId = localDevice.deviceId,
                chunkIndex = chunkIndex,
                chunkOffset = chunkOffset,
                chunkLength = chunkBytes.size
            ),
            senderDeviceId = localDevice.deviceId,
            receiverDeviceId = session.peer.id,
            sessionId = session.sessionId,
            messageId = "${transfer.id}-$chunkIndex",
            sentAtUtc = Instant.now().toString()
        )

        val boundary = "LocalBridgeBoundary${UUID.randomUUID().toString().replace("-", "")}"
        val metadataBytes = ProtocolJson.format.encodeToString(
            ProtocolEnvelope.serializer(FileTransferChunkDescriptorDto.serializer()),
            metadataEnvelope
        ).toByteArray(Charsets.UTF_8)
        val multipartSections = buildMultipartBodySections(boundary)

        val connection = (URL(buildPeerUrl(session.peer, AppConstants.transferChunkPath)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = AppConstants.transferRequestTimeoutMillis
            readTimeout = AppConstants.transferRequestTimeoutMillis
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
            setFixedLengthStreamingMode(
                multipartSections.totalLength(
                    metadataLength = metadataBytes.size,
                    chunkLength = chunkBytes.size
                )
            )
        }

        return try {
            connection.outputStream.use { output ->
                writeMultipartBody(
                    output = output,
                    sections = multipartSections,
                    metadataBytes = metadataBytes,
                    chunkBytes = chunkBytes
                )
            }

            val statusCode = connection.responseCode
            val responseBody = ProtocolHttpClient.readBody(connection)
            val envelope = responseBody.takeIf { it.isNotBlank() }?.let {
                runCatching { decodeEnvelope<FileTransferChunkResponseDto>(it) }.getOrNull()
            }
            val validation = ProtocolEnvelopeValidator.validate(
                envelope,
                expectedPacketTypes = *arrayOf(ProtocolPacketTypes.transferChunkResponse)
            )
            if (!validation.isValid || statusCode !in 200..299 || envelope?.payload == null) {
                createChunkFailureResponse(
                    transfer.id,
                    chunkIndex,
                    envelope?.error?.code ?: validation.errorCode ?: "chunk_http_$statusCode"
                )
            } else {
                envelope.payload
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun sendComplete(
        transfer: TransferItem,
        session: ConnectedSession,
        localDevice: LocalDeviceProfile
    ): FileTransferCompleteResponseDto {
        val requestDto = FileTransferCompleteRequestDto(
            transferId = transfer.id,
            sessionId = session.sessionId,
            senderId = localDevice.deviceId,
            totalChunks = transfer.totalChunks,
            totalBytes = transfer.totalBytes,
            sentAtUtc = Instant.now().toString()
        )

        if (session.peer.transportMode == com.localbridge.android.models.AppConnectionMode.BluetoothFallback) {
            return bluetoothConnectionService.sendTransferComplete(requestDto)
        }

        val envelope = ProtocolEnvelopeFactory.create(
            packetType = ProtocolPacketTypes.transferCompleteRequest,
            payload = requestDto,
            senderDeviceId = localDevice.deviceId,
            receiverDeviceId = session.peer.id,
            sessionId = session.sessionId,
            messageId = transfer.id,
            sentAtUtc = Instant.now().toString()
        )

        val response = ProtocolHttpClient.postEnvelope<FileTransferCompleteRequestDto, FileTransferCompleteResponseDto>(
            url = buildPeerUrl(session.peer, AppConstants.transferCompletePath),
            envelope = envelope,
            timeoutMillis = AppConstants.transferRequestTimeoutMillis
        )
        val validation = ProtocolEnvelopeValidator.validate(
            response.envelope,
            expectedPacketTypes = *arrayOf(ProtocolPacketTypes.transferCompleteResponse)
        )
        return if (!validation.isValid || response.statusCode !in 200..299 || response.envelope?.payload == null) {
            createCompleteFailureResponse(
                transfer.id,
                response.envelope?.error?.code ?: validation.errorCode ?: "complete_http_${response.statusCode}"
            )
        } else {
            response.envelope.payload
        }
    }

    private suspend fun sendCancel(
        transferId: String,
        peerId: String,
        reason: String
    ): FileTransferCancelResponseDto {
        val session = getConnectedSessionForPeer(peerId)
            ?: return createCancelFailureResponse(transferId, "peer_not_connected")
        val localDevice = localDeviceProfileRepository.getOrCreate()
        val requestDto = FileTransferCancelRequestDto(
            transferId = transferId,
            sessionId = session.sessionId,
            senderId = localDevice.deviceId,
            reason = reason,
            sentAtUtc = Instant.now().toString()
        )

        if (session.peer.transportMode == com.localbridge.android.models.AppConnectionMode.BluetoothFallback) {
            return bluetoothConnectionService.sendTransferCancel(requestDto)
        }

        val envelope = ProtocolEnvelopeFactory.create(
            packetType = ProtocolPacketTypes.transferCancelRequest,
            payload = requestDto,
            senderDeviceId = localDevice.deviceId,
            receiverDeviceId = session.peer.id,
            sessionId = session.sessionId,
            messageId = transferId,
            sentAtUtc = Instant.now().toString()
        )

        val response = ProtocolHttpClient.postEnvelope<FileTransferCancelRequestDto, FileTransferCancelResponseDto>(
            url = buildPeerUrl(session.peer, AppConstants.transferCancelPath),
            envelope = envelope,
            timeoutMillis = AppConstants.transferRequestTimeoutMillis
        )
        val validation = ProtocolEnvelopeValidator.validate(
            response.envelope,
            expectedPacketTypes = *arrayOf(ProtocolPacketTypes.transferCancelResponse)
        )
        return if (!validation.isValid || response.statusCode !in 200..299 || response.envelope?.payload == null) {
            createCancelFailureResponse(
                transferId,
                response.envelope?.error?.code ?: validation.errorCode ?: "cancel_http_${response.statusCode}"
            )
        } else {
            response.envelope.payload
        }
    }

    private suspend fun getConnectedSession(): ConnectedSession? {
        val state = connectionService.connectionState.value
        val peer = connectionService.activePeer.value ?: state.connectedPeer
        if (state.lifecycleState != ConnectionLifecycleState.Connected || state.sessionId.isNullOrBlank() || peer == null) {
            return null
        }

        return ConnectedSession(
            sessionId = state.sessionId,
            peer = peer
        )
    }

    private suspend fun getConnectedSessionForPeer(peerId: String): ConnectedSession? {
        val session = getConnectedSession() ?: return null
        return if (session.peer.id.equals(peerId, ignoreCase = true)) session else null
    }

    private fun getConnectedSessionForIncoming(sessionId: String, senderId: String): ConnectedSession? {
        val state = connectionService.connectionState.value
        val peer = connectionService.activePeer.value ?: state.connectedPeer
        if (state.lifecycleState != ConnectionLifecycleState.Connected || state.sessionId != sessionId || peer == null) {
            return null
        }

        return if (peer.id.equals(senderId, ignoreCase = true)) {
            ConnectedSession(sessionId = sessionId, peer = peer)
        } else {
            null
        }
    }

    private suspend fun updateTransfer(
        transferId: String,
        transform: (TransferItem) -> TransferItem
    ): TransferItem? {
        val current = findTransfer(transferId) ?: return null
        val updated = transform(current)
        transferRepository.replace(updated)
        return updated
    }

    private fun findTransfer(transferId: String): TransferItem? {
        return transferRepository.transfers.value.firstOrNull { it.id == transferId }
    }

    private fun resolveSelectedFile(
        uri: Uri,
        fallbackName: String? = null,
        fallbackSize: Long? = null
    ): SelectedFile? {
        var fileName = fallbackName
        var size = fallbackSize
        var fileCreatedAtUtc: String? = null
        val mimeType = contentResolver.getType(uri).orEmpty()

        val projection = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        runCatching {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (cursor.moveToFirst()) {
                    if (nameIndex >= 0) {
                        fileName = cursor.getString(nameIndex)
                    }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                    if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                        val modifiedAt = cursor.getLong(modifiedIndex)
                        if (modifiedAt > 0L) {
                            fileCreatedAtUtc = Instant.ofEpochMilli(modifiedAt).toString()
                        }
                    }
                }
            }
        }

        if (size == null || size!! < 0L) {
            size = runCatching {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull()?.takeIf { it >= 0L }
        }

        val resolvedName = sanitizeFileName(
            fileName?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "selected-file"
        )
        val resolvedMimeType = mimeType.ifBlank { guessMimeTypeFromName(resolvedName) }
        val resolvedSize = size ?: return null

        return SelectedFile(
            uri = uri,
            fileName = resolvedName,
            size = resolvedSize,
            mimeType = resolvedMimeType,
            kind = kindFromMimeType(resolvedMimeType, resolvedName),
            fileCreatedAtUtc = fileCreatedAtUtc ?: Instant.now().toString()
        )
    }

    private fun buildMultipartBodySections(boundary: String): MultipartBodySections {
        val metadataPrefix = buildString {
            append("--")
            append(boundary)
            append("\r\n")
            append("Content-Disposition: form-data; name=\"")
            append(ProtocolConstants.multipartMetadataPartName)
            append("\"\r\n")
            append("Content-Type: application/json\r\n\r\n")
        }.toByteArray(Charsets.ISO_8859_1)

        val binaryPrefix = buildString {
            append("\r\n--")
            append(boundary)
            append("\r\n")
            append("Content-Disposition: form-data; name=\"")
            append(ProtocolConstants.multipartBinaryPartName)
            append("\"; filename=\"chunk.bin\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }.toByteArray(Charsets.ISO_8859_1)

        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.ISO_8859_1)
        return MultipartBodySections(
            metadataPrefix = metadataPrefix,
            binaryPrefix = binaryPrefix,
            suffix = suffix
        )
    }

    private fun writeMultipartBody(
        output: OutputStream,
        sections: MultipartBodySections,
        metadataBytes: ByteArray,
        chunkBytes: ByteArray
    ) {
        output.write(sections.metadataPrefix)
        output.write(metadataBytes)
        output.write(sections.binaryPrefix)
        output.write(chunkBytes)
        output.write(sections.suffix)
    }

    private fun parseMultipartRequest(request: LocalHttpRequest): MultipartPayload? {
        val contentType = request.header("content-type") ?: return null
        if (!contentType.contains("multipart/form-data", ignoreCase = true)) {
            return null
        }

        val boundary = Regex("boundary=(?:\"([^\"]+)\"|([^;]+))")
            .find(contentType)
            ?.let { match ->
                match.groupValues[1].ifBlank { match.groupValues[2] }
            }
            ?.trim()
            ?.trim('"')
            ?: return null

        val parts = parseMultipartParts(request.bodyBytes, boundary)
        val metadataPart = parts[ProtocolConstants.multipartMetadataPartName] ?: return null
        val chunkPart = parts[ProtocolConstants.multipartBinaryPartName] ?: return null
        return MultipartPayload(
            metadataJson = metadataPart.body.toString(Charsets.UTF_8),
            chunkBytes = chunkPart.body
        )
    }

    private fun parseMultipartParts(body: ByteArray, boundary: String): Map<String, MultipartPart> {
        val parts = linkedMapOf<String, MultipartPart>()
        val delimiter = "--$boundary".toByteArray(Charsets.ISO_8859_1)
        val headerDelimiter = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        var cursor = 0

        while (true) {
            val boundaryStart = indexOfBytes(body, delimiter, cursor)
            if (boundaryStart < 0) {
                break
            }

            cursor = boundaryStart + delimiter.size
            if (cursor + 1 < body.size && body[cursor] == '-'.code.toByte() && body[cursor + 1] == '-'.code.toByte()) {
                break
            }
            if (cursor + 1 < body.size && body[cursor] == '\r'.code.toByte() && body[cursor + 1] == '\n'.code.toByte()) {
                cursor += 2
            }

            val headersEnd = indexOfBytes(body, headerDelimiter, cursor)
            if (headersEnd < 0) {
                break
            }

            val headersText = body.copyOfRange(cursor, headersEnd).toString(Charsets.ISO_8859_1)
            val nextBoundary = indexOfBytes(body, delimiter, headersEnd + headerDelimiter.size)
            if (nextBoundary < 0) {
                break
            }

            var bodyEnd = nextBoundary
            if (bodyEnd >= 2 && body[bodyEnd - 2] == '\r'.code.toByte() && body[bodyEnd - 1] == '\n'.code.toByte()) {
                bodyEnd -= 2
            }

            val contentDisposition = headersText.lines()
                .firstOrNull { it.startsWith("Content-Disposition", ignoreCase = true) }
                .orEmpty()
            val partName = Regex("\\bname=(?:\"([^\"]+)\"|([^;\\s]+))", RegexOption.IGNORE_CASE)
                .find(contentDisposition)
                ?.let { match ->
                    match.groupValues[1].ifBlank { match.groupValues[2] }
                }

            if (!partName.isNullOrBlank()) {
                parts[partName] = MultipartPart(
                    body = body.copyOfRange(headersEnd + headerDelimiter.size, bodyEnd)
                )
            }

            cursor = nextBoundary
        }

        return parts
    }

    private fun indexOfBytes(source: ByteArray, pattern: ByteArray, startIndex: Int): Int {
        if (pattern.isEmpty() || source.isEmpty() || startIndex >= source.size) {
            return -1
        }

        for (index in startIndex..source.size - pattern.size) {
            var matched = true
            for (patternIndex in pattern.indices) {
                if (source[index + patternIndex] != pattern[patternIndex]) {
                    matched = false
                    break
                }
            }
            if (matched) {
                return index
            }
        }

        return -1
    }

    private fun readChunk(input: InputStream, expectedLength: Int): ByteArray? {
        if (expectedLength <= 0) {
            return null
        }

        val buffer = ByteArray(expectedLength)
        var totalRead = 0
        while (totalRead < expectedLength) {
            val read = input.read(buffer, totalRead, expectedLength - totalRead)
            if (read <= 0) {
                break
            }
            totalRead += read
        }

        return if (totalRead == expectedLength) buffer else null
    }

    private fun openOutgoingInput(sourceUri: Uri, startOffset: Long): OutgoingInputHandle? {
        if (startOffset < 0L) {
            return null
        }

        val fileDescriptor = runCatching {
            contentResolver.openFileDescriptor(sourceUri, "r")
        }.getOrNull()

        if (fileDescriptor != null) {
            return runCatching {
                val stream = FileInputStream(fileDescriptor.fileDescriptor)
                stream.channel.position(startOffset)
                OutgoingInputHandle(stream, fileDescriptor)
            }.getOrElse { exception ->
                loggerService.warning(
                    "[TRANSFER] Android seekable source open failed for $sourceUri at offset $startOffset: " +
                        (exception.message ?: "unknown error")
                )
                runCatching { fileDescriptor.close() }
                null
            }
        }

        val inputStream = contentResolver.openInputStream(sourceUri) ?: return null
        if (!skipFully(inputStream, startOffset)) {
            runCatching { inputStream.close() }
            return null
        }

        return OutgoingInputHandle(inputStream, null)
    }

    private fun skipFully(input: InputStream, bytesToSkip: Long): Boolean {
        var remaining = bytesToSkip
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
                continue
            }

            if (input.read() == -1) {
                return false
            }
            remaining -= 1L
        }

        return true
    }

    private fun normalizeRestoredTransfer(transfer: TransferItem): TransferItem {
        return when (transfer.status) {
            TransferState.Preparing,
            TransferState.Sending,
            TransferState.Receiving -> transfer.copy(
                status = if (transfer.direction == TransferDirection.Outgoing) TransferState.Paused else TransferState.Failed,
                speedBytesPerSecond = 0,
                estimatedSecondsRemaining = null,
                lastError = "Transfer interrupted before Android restarted."
            )

            else -> transfer.copy(
                speedBytesPerSecond = 0,
                estimatedSecondsRemaining = if (transfer.status == TransferState.Completed) 0 else transfer.estimatedSecondsRemaining
            )
        }
    }

    private class OutgoingInputHandle(
        val input: InputStream,
        private val fileDescriptor: ParcelFileDescriptor?
    ) : Closeable {
        override fun close() {
            runCatching { input.close() }
            runCatching { fileDescriptor?.close() }
        }
    }

    private fun cleanupStaleTempFiles() {
        if (!incomingTempDirectory.exists()) {
            return
        }

        incomingTempDirectory.listFiles { file -> file.extension.equals("part", ignoreCase = true) }
            ?.forEach { file ->
                runCatching {
                    if (file.lastModified() < System.currentTimeMillis() - 24L * 60L * 60L * 1000L) {
                        file.delete()
                    }
                }
            }
    }

    private fun syncForegroundTransferProtection(items: List<TransferItem>) {
        val activeTransfers = items.filter { transfer ->
            transfer.status in setOf(
                TransferState.Queued,
                TransferState.Preparing,
                TransferState.Sending,
                TransferState.Receiving
            )
        }

        if (activeTransfers.isEmpty()) {
            TransferForegroundService.stop(appContext)
            return
        }

        val primaryTransfer = activeTransfers.first()
        val headline = if (activeTransfers.size == 1) {
            "${primaryTransfer.fileName} is transferring"
        } else {
            "${activeTransfers.size} Localink transfers are active"
        }
        val detail = buildString {
            append("Keeping Localink alive in the background")
            append(" over hotspot/LAN")
            append(" while ")
            append(
                activeTransfers.joinToString(", ") { transfer ->
                    "${transfer.direction.name.lowercase(Locale.ROOT)} ${transfer.fileName}"
                }
            )
            append('.')
        }

        TransferForegroundService.startOrUpdate(
            context = appContext,
            activeCount = activeTransfers.size,
            headline = headline,
            detail = detail
        )
    }

    private suspend fun resolveReceiveDirectory(): File {
        val settings = settingsRepository.settings.first()
        return storageDirectories.ensureReceivedDirectory(settings.receiveFolderName)
    }

    private suspend fun describeActiveReceiveLocation(): String {
        val settings = settingsRepository.settings.first()
        return if (settings.hasExternalReceiveFolder) {
            "external SAF folder ${settings.receiveTreeDisplayName ?: settings.receiveTreeUri.orEmpty()} with app-private fallback ${storageDirectories.resolveReceivedDirectory(settings.receiveFolderName).absolutePath} and Download/${AppConstants.defaultPublicDownloadsFolderName} mirror"
        } else {
            "${storageDirectories.resolveReceivedDirectory(settings.receiveFolderName).absolutePath} with Download/${AppConstants.defaultPublicDownloadsFolderName} mirror"
        }
    }

    private suspend fun persistIncomingTempToConfiguredLocation(
        transfer: TransferItem,
        runtime: IncomingTransferRuntime
    ): SavedTransferDestination {
        val settings = settingsRepository.settings.first()
        val privateFinalFile = ensureUniqueFile(runtime.finalFile)
        privateFinalFile.parentFile?.mkdirs()
        runtime.tempFile.copyTo(privateFinalFile, overwrite = false)

        val privateDestination = SavedTransferDestination(
            savedPath = privateFinalFile.absolutePath,
            logLabel = privateFinalFile.absolutePath
        )
        var externalDestination: SavedTransferDestination? = null

        if (settings.hasExternalReceiveFolder) {
            externalDestination = runCatching {
                writeIncomingFileToPickedTree(
                    treeUriString = settings.receiveTreeUri.orEmpty(),
                    displayName = settings.receiveTreeDisplayName,
                    fileName = transfer.fileName,
                    mimeType = transfer.mimeType,
                    tempFile = privateFinalFile
                )
            }.onFailure { exception ->
                loggerService.warning(
                    "[STORAGE] External SAF save failed for ${transfer.fileName}: ${exception.message}. Falling back to app-private storage."
                )
            }.getOrNull()
        }

        val publicDestination = runCatching {
            mirrorIncomingFileToPublicDownloads(
                fileName = transfer.fileName,
                mimeType = transfer.mimeType,
                sourceFile = privateFinalFile
            )
        }.onFailure { exception ->
            loggerService.warning(
                "[STORAGE] Download mirror failed for ${transfer.fileName}: ${exception.message}. Keeping app-private copy."
            )
        }.getOrNull()

        return publicDestination ?: externalDestination ?: privateDestination
    }

    private fun mirrorIncomingFileToPublicDownloads(
        fileName: String,
        mimeType: String,
        sourceFile: File
    ): SavedTransferDestination {
        val sanitizedName = sanitizeFileName(fileName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, sanitizedName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/${AppConstants.defaultPublicDownloadsFolderName}")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = contentResolver.insert(collection, values)
                ?: error("downloads_insert_failed")

            runCatching {
                contentResolver.openOutputStream(uri, "w")?.use { output ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                } ?: error("downloads_output_stream_unavailable")

                contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
            }.onFailure {
                runCatching { contentResolver.delete(uri, null, null) }
                throw it
            }.getOrThrow()

            return SavedTransferDestination(
                savedPath = uri.toString(),
                logLabel = "Download/${AppConstants.defaultPublicDownloadsFolderName}/$sanitizedName"
            )
        }

        val publicDirectory = storageDirectories.resolveLegacyPublicDownloadsDirectory().apply { mkdirs() }
        val finalFile = ensureUniqueFile(File(publicDirectory, sanitizedName))
        sourceFile.copyTo(finalFile, overwrite = false)
        return SavedTransferDestination(
            savedPath = finalFile.absolutePath,
            logLabel = finalFile.absolutePath
        )
    }

    private fun writeIncomingFileToPickedTree(
        treeUriString: String,
        displayName: String?,
        fileName: String,
        mimeType: String,
        tempFile: File
    ): SavedTransferDestination {
        val treeDocument = DocumentFile.fromTreeUri(appContext, treeUriString.toUri())
            ?: error("document_tree_unavailable")

        if (!treeDocument.canWrite()) {
            error("document_tree_not_writable")
        }

        val targetDocument = createUniqueDocument(
            parent = treeDocument,
            fileName = sanitizeFileName(fileName),
            mimeType = mimeType.ifBlank { "application/octet-stream" }
        ) ?: error("document_create_failed")

        runCatching {
            contentResolver.openOutputStream(targetDocument.uri, "w")?.use { output ->
                tempFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: error("document_output_stream_unavailable")
        }.onFailure {
            runCatching { targetDocument.delete() }
            throw it
        }.getOrThrow()

        return SavedTransferDestination(
            savedPath = targetDocument.uri.toString(),
            logLabel = "${displayName ?: "Picked SAF folder"} (${targetDocument.uri})"
        )
    }

    private fun createUniqueDocument(
        parent: DocumentFile,
        fileName: String,
        mimeType: String
    ): DocumentFile? {
        val baseName = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        var candidateName = fileName
        var counter = 1

        while (parent.findFile(candidateName) != null) {
            candidateName = if (extension == null) {
                "$baseName ($counter)"
            } else {
                "$baseName ($counter).$extension"
            }
            counter += 1
        }

        return parent.createFile(mimeType, candidateName)
    }

    private fun sanitizeFileName(fileName: String): String {
        val sanitized = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return sanitized.ifBlank { "received-file" }
    }

    private fun ensureUniqueFile(target: File): File {
        var candidate = target
        val parent = target.parentFile ?: return target
        val baseName = target.nameWithoutExtension
        val extension = target.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        var index = 1

        while (candidate.exists()) {
            candidate = File(parent, "$baseName ($index)$extension")
            index += 1
        }

        return candidate
    }

    private fun guessMimeTypeFromName(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private fun kindFromMimeType(mimeType: String, fileName: String): String {
        return when {
            mimeType.startsWith("image/") -> "image"
            mimeType.startsWith("video/") -> "video"
            mimeType == "application/pdf" -> "document"
            mimeType.startsWith("text/") || fileName.endsWith(".txt", ignoreCase = true) -> "text"
            else -> "file"
        }
    }

    private fun calculateTotalChunks(totalBytes: Long, chunkSize: Int): Int {
        return ((totalBytes + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(1)
    }

    private fun estimateEta(totalBytes: Long, transferredBytes: Long, speedBytesPerSecond: Long): Long? {
        if (speedBytesPerSecond <= 0L) {
            return null
        }

        val remaining = (totalBytes - transferredBytes).coerceAtLeast(0L)
        return (remaining / speedBytesPerSecond).coerceAtLeast(0L)
    }

    private fun buildPeerUrl(peer: DevicePeer, path: String): String {
        return "http://${peer.ipAddress}:${peer.port}$path"
    }

    private fun mapStateToProtocol(state: TransferState): String {
        return when (state) {
            TransferState.Queued -> ProtocolConstants.transferStateQueued
            TransferState.Preparing -> ProtocolConstants.transferStatePreparing
            TransferState.Sending -> ProtocolConstants.transferStateSending
            TransferState.Receiving -> ProtocolConstants.transferStateReceiving
            TransferState.Paused -> ProtocolConstants.transferStatePaused
            TransferState.Completed -> ProtocolConstants.transferStateCompleted
            TransferState.Failed -> ProtocolConstants.transferStateFailed
            TransferState.Canceled -> ProtocolConstants.transferStateCanceled
        }
    }

    private fun createPrepareFailureResponse(
        transferId: String,
        reason: String,
        localDevice: LocalDeviceProfile
    ): FileTransferPrepareResponseDto {
        return FileTransferPrepareResponseDto(
            accepted = false,
            transferId = transferId,
            status = ProtocolConstants.transferStateFailed,
            failureReason = reason,
            nextExpectedChunkIndex = 0,
            receivedBytes = 0L,
            receiverDeviceId = localDevice.deviceId,
            receiverDeviceName = localDevice.deviceName,
            suggestedFilePath = null,
            respondedAtUtc = Instant.now().toString()
        )
    }

    private fun createChunkFailureResponse(
        transferId: String,
        chunkIndex: Int,
        reason: String
    ): FileTransferChunkResponseDto {
        return FileTransferChunkResponseDto(
            accepted = false,
            transferId = transferId,
            chunkIndex = chunkIndex,
            status = ProtocolConstants.transferStateFailed,
            failureReason = reason,
            nextExpectedChunkIndex = 0,
            receivedBytes = 0L,
            respondedAtUtc = Instant.now().toString()
        )
    }

    private fun createCompleteFailureResponse(transferId: String, reason: String): FileTransferCompleteResponseDto {
        return FileTransferCompleteResponseDto(
            accepted = false,
            transferId = transferId,
            status = ProtocolConstants.transferStateFailed,
            failureReason = reason,
            savedFilePath = null,
            completedAtUtc = Instant.now().toString()
        )
    }

    private fun createCancelFailureResponse(transferId: String, reason: String): FileTransferCancelResponseDto {
        return FileTransferCancelResponseDto(
            accepted = false,
            transferId = transferId,
            status = ProtocolConstants.transferStateFailed,
            failureReason = reason,
            canceledAtUtc = Instant.now().toString()
        )
    }

    private fun <T> protocolSuccessResponse(
        statusCode: Int,
        packetType: String,
        payload: T,
        payloadSerializer: KSerializer<T>,
        localDevice: LocalDeviceProfile,
        receiverDeviceId: String?,
        sessionId: String?,
        correlationId: String?
    ): LocalHttpResponse {
        val envelope = ProtocolEnvelopeFactory.create(
            packetType = packetType,
            payload = payload,
            senderDeviceId = localDevice.deviceId,
            receiverDeviceId = receiverDeviceId,
            sessionId = sessionId,
            correlationId = correlationId
        )

        return LocalHttpResponse.json(
            statusCode = statusCode,
            reasonPhrase = httpReason(statusCode),
            body = ProtocolJson.format.encodeToString(
                ProtocolEnvelope.serializer(payloadSerializer),
                envelope
            )
        )
    }

    private fun <T> protocolErrorResponse(
        statusCode: Int,
        packetType: String,
        payload: T,
        payloadSerializer: KSerializer<T>,
        localDevice: LocalDeviceProfile,
        receiverDeviceId: String?,
        sessionId: String?,
        correlationId: String?,
        errorCode: String,
        errorMessage: String
    ): LocalHttpResponse {
        val envelope = ProtocolEnvelope(
            meta = ProtocolMetadata(
                version = ProtocolConstants.version,
                packetType = packetType,
                messageId = UUID.randomUUID().toString().replace("-", ""),
                sentAtUtc = Instant.now().toString(),
                sessionId = sessionId,
                senderDeviceId = localDevice.deviceId,
                receiverDeviceId = receiverDeviceId,
                correlationId = correlationId
            ),
            payload = payload,
            error = ProtocolError(
                code = errorCode,
                message = errorMessage,
                isRetryable = false
            )
        )

        return LocalHttpResponse.json(
            statusCode = statusCode,
            reasonPhrase = httpReason(statusCode),
            body = ProtocolJson.format.encodeToString(
                ProtocolEnvelope.serializer(payloadSerializer),
                envelope
            )
        )
    }

    private fun httpReason(statusCode: Int): String {
        return when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            409 -> "Conflict"
            426 -> "Upgrade Required"
            else -> "Error"
        }
    }

    private data class ConnectedSession(
        val sessionId: String,
        val peer: DevicePeer
    )

    override suspend fun prepareIncomingBluetoothTransfer(
        request: FileTransferPrepareRequestDto,
        peer: DevicePeer,
        sessionId: String,
        localDevice: LocalDeviceProfile
    ): FileTransferPrepareResponseDto {
        return prepareIncomingTransfer(
            request = request,
            session = ConnectedSession(sessionId = sessionId, peer = peer),
            localDevice = localDevice
        )
    }

    override suspend fun receiveIncomingBluetoothChunk(
        descriptor: FileTransferChunkDescriptorDto,
        chunkBytes: ByteArray,
        peer: DevicePeer,
        sessionId: String
    ): FileTransferChunkResponseDto {
        return receiveChunk(
            descriptor = descriptor,
            chunkBytes = chunkBytes,
            session = ConnectedSession(sessionId = sessionId, peer = peer)
        )
    }

    override suspend fun completeIncomingBluetoothTransfer(
        request: FileTransferCompleteRequestDto,
        peer: DevicePeer,
        sessionId: String
    ): FileTransferCompleteResponseDto {
        return completeIncomingTransfer(request)
    }

    override suspend fun cancelIncomingBluetoothTransfer(
        request: FileTransferCancelRequestDto,
        peer: DevicePeer,
        sessionId: String
    ): FileTransferCancelResponseDto {
        return cancelIncomingTransferFromRemote(request)
    }

    private data class SelectedFile(
        val uri: Uri,
        val fileName: String,
        val size: Long,
        val mimeType: String,
        val kind: String,
        val fileCreatedAtUtc: String
    )

    private data class MultipartPayload(
        val metadataJson: String,
        val chunkBytes: ByteArray
    )

    private data class MultipartPart(
        val body: ByteArray
    )

    private data class MultipartBodySections(
        val metadataPrefix: ByteArray,
        val binaryPrefix: ByteArray,
        val suffix: ByteArray
    ) {
        fun totalLength(metadataLength: Int, chunkLength: Int): Long {
            return metadataPrefix.size.toLong() +
                metadataLength.toLong() +
                binaryPrefix.size.toLong() +
                chunkLength.toLong() +
                suffix.size.toLong()
        }
    }

    private data class SavedTransferDestination(
        val savedPath: String,
        val logLabel: String
    )

    private data class TransferMetrics(
        val speedBytesPerSecond: Long,
        val estimatedSecondsRemaining: Long?
    )

    private data class OutgoingTransferRuntime(
        val transferId: String,
        val gate: Mutex = Mutex(),
        var pauseRequested: Boolean = false,
        var cancelRequested: Boolean = false,
        var workerJob: Job? = null,
        var metricStartedAt: Long = SystemClock.elapsedRealtime(),
        var metricStartBytes: Long = 0L,
        var recoveryAttempts: Int = 0
    ) {
        fun restartMetrics(transferredBytes: Long) {
            metricStartBytes = transferredBytes
            metricStartedAt = SystemClock.elapsedRealtime()
        }

        fun captureMetrics(transferredBytes: Long, totalBytes: Long): TransferMetrics {
            val elapsedMillis = (SystemClock.elapsedRealtime() - metricStartedAt).coerceAtLeast(1L)
            val bytesDelta = (transferredBytes - metricStartBytes).coerceAtLeast(0L)
            val speed = if (bytesDelta <= 0L) 0L else (bytesDelta * 1000L) / elapsedMillis
            return TransferMetrics(
                speedBytesPerSecond = speed,
                estimatedSecondsRemaining = if (speed <= 0L) null else ((totalBytes - transferredBytes).coerceAtLeast(0L) / speed)
            )
        }
    }

    private data class IncomingTransferRuntime(
        val transferId: String,
        var sessionId: String,
        val senderId: String,
        var peerName: String,
        val tempFile: File,
        val finalFile: File,
        val accessFile: RandomAccessFile,
        val gate: Mutex = Mutex(),
        var nextExpectedChunkIndex: Int = 0,
        var metricStartedAt: Long = SystemClock.elapsedRealtime(),
        var metricStartBytes: Long = 0L
    ) {
        fun restartMetrics(transferredBytes: Long) {
            metricStartBytes = transferredBytes
            metricStartedAt = SystemClock.elapsedRealtime()
        }

        fun captureMetrics(transferredBytes: Long, totalBytes: Long): TransferMetrics {
            val elapsedMillis = (SystemClock.elapsedRealtime() - metricStartedAt).coerceAtLeast(1L)
            val bytesDelta = (transferredBytes - metricStartBytes).coerceAtLeast(0L)
            val speed = if (bytesDelta <= 0L) 0L else (bytesDelta * 1000L) / elapsedMillis
            return TransferMetrics(
                speedBytesPerSecond = speed,
                estimatedSecondsRemaining = if (speed <= 0L) null else ((totalBytes - transferredBytes).coerceAtLeast(0L) / speed)
            )
        }
    }
}
