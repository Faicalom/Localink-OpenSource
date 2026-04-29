package com.localbridge.android.services

import com.localbridge.android.core.AppConstants
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class BluetoothFrameKind(val wireValue: Byte) {
    JsonEnvelope(1),
    JsonEnvelopeWithBinary(2);

    companion object {
        fun fromWireValue(value: Byte): BluetoothFrameKind {
            return entries.firstOrNull { it.wireValue == value }
                ?: throw IOException("Unknown Bluetooth frame kind $value.")
        }
    }
}

internal data class BluetoothTransportFrame(
    val kind: BluetoothFrameKind,
    val metadataJson: String,
    val binaryPayload: ByteArray? = null
)

internal object BluetoothTransportFrameCodec {
    suspend fun writeJsonEnvelope(
        outputStream: OutputStream,
        metadataJson: String
    ) = withContext(Dispatchers.IO) {
        val metadataBytes = metadataJson.toByteArray(Charsets.UTF_8)
        writeFrame(
            outputStream = outputStream,
            frameKind = BluetoothFrameKind.JsonEnvelope,
            metadataBytes = metadataBytes,
            binaryPayload = null
        )
    }

    suspend fun writeJsonEnvelopeWithBinary(
        outputStream: OutputStream,
        metadataJson: String,
        binaryPayload: ByteArray
    ) = withContext(Dispatchers.IO) {
        val metadataBytes = metadataJson.toByteArray(Charsets.UTF_8)
        writeFrame(
            outputStream = outputStream,
            frameKind = BluetoothFrameKind.JsonEnvelopeWithBinary,
            metadataBytes = metadataBytes,
            binaryPayload = binaryPayload
        )
    }

    suspend fun read(
        inputStream: InputStream
    ): BluetoothTransportFrame? = withContext(Dispatchers.IO) {
        val header = ByteArray(5)
        val headerRead = readExactly(inputStream, header, header.size)
        if (headerRead == 0) {
            return@withContext null
        }
        if (headerRead != header.size) {
            throw EOFException("Bluetooth frame header is incomplete.")
        }

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val frameKind = BluetoothFrameKind.fromWireValue(buffer.get())
        val metadataLength = buffer.int
        if (metadataLength <= 0 || metadataLength > AppConstants.bluetoothFrameMaxJsonBytes) {
            throw IOException("Bluetooth metadata frame size $metadataLength is outside the accepted range.")
        }

        val metadataBytes = ByteArray(metadataLength)
        val metadataRead = readExactly(inputStream, metadataBytes, metadataBytes.size)
        if (metadataRead != metadataBytes.size) {
            throw EOFException("Bluetooth frame metadata is incomplete.")
        }

        val binaryPayload = if (frameKind == BluetoothFrameKind.JsonEnvelopeWithBinary) {
            val binaryLengthBytes = ByteArray(4)
            val binaryLengthRead = readExactly(inputStream, binaryLengthBytes, binaryLengthBytes.size)
            if (binaryLengthRead != binaryLengthBytes.size) {
                throw EOFException("Bluetooth binary frame length is incomplete.")
            }

            val binaryLength = ByteBuffer.wrap(binaryLengthBytes).order(ByteOrder.BIG_ENDIAN).int
            if (binaryLength < 0 || binaryLength > AppConstants.bluetoothTransferChunkSizeBytes) {
                throw IOException("Bluetooth binary frame size $binaryLength is outside the accepted range.")
            }

            val payload = ByteArray(binaryLength)
            val payloadRead = readExactly(inputStream, payload, payload.size)
            if (payloadRead != payload.size) {
                throw EOFException("Bluetooth binary frame payload is incomplete.")
            }

            payload
        } else {
            null
        }

        BluetoothTransportFrame(
            kind = frameKind,
            metadataJson = metadataBytes.toString(Charsets.UTF_8),
            binaryPayload = binaryPayload
        )
    }

    private fun writeFrame(
        outputStream: OutputStream,
        frameKind: BluetoothFrameKind,
        metadataBytes: ByteArray,
        binaryPayload: ByteArray?
    ) {
        val header = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN).apply {
            put(frameKind.wireValue)
            putInt(metadataBytes.size)
        }.array()

        outputStream.write(header)
        outputStream.write(metadataBytes)

        if (frameKind == BluetoothFrameKind.JsonEnvelopeWithBinary) {
            val payload = binaryPayload ?: ByteArray(0)
            val payloadHeader = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size).array()
            outputStream.write(payloadHeader)
            if (payload.isNotEmpty()) {
                outputStream.write(payload)
            }
        }

        outputStream.flush()
    }

    private fun readExactly(inputStream: InputStream, buffer: ByteArray, expectedLength: Int): Int {
        var totalRead = 0
        while (totalRead < expectedLength) {
            val bytesRead = inputStream.read(buffer, totalRead, expectedLength - totalRead)
            if (bytesRead <= 0) {
                return totalRead
            }
            totalRead += bytesRead
        }
        return totalRead
    }
}
