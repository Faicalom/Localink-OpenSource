package com.localink.android.services

import com.localink.android.core.protocol.FileTransferCancelRequestDto
import com.localink.android.core.protocol.FileTransferCancelResponseDto
import com.localink.android.core.protocol.FileTransferChunkDescriptorDto
import com.localink.android.core.protocol.FileTransferChunkResponseDto
import com.localink.android.core.protocol.FileTransferCompleteRequestDto
import com.localink.android.core.protocol.FileTransferCompleteResponseDto
import com.localink.android.core.protocol.FileTransferPrepareRequestDto
import com.localink.android.core.protocol.FileTransferPrepareResponseDto
import com.localink.android.models.DevicePeer

interface BluetoothFileTransferHandler {
    suspend fun prepareIncomingBluetoothTransfer(
        request: FileTransferPrepareRequestDto,
        sessionId: String,
        peer: DevicePeer
    ): FileTransferPrepareResponseDto

    suspend fun receiveIncomingBluetoothChunk(
        descriptor: FileTransferChunkDescriptorDto,
        chunkBytes: ByteArray,
        sessionId: String,
        peer: DevicePeer
    ): FileTransferChunkResponseDto

    suspend fun completeIncomingBluetoothTransfer(
        request: FileTransferCompleteRequestDto,
        sessionId: String,
        peer: DevicePeer
    ): FileTransferCompleteResponseDto

    suspend fun cancelIncomingBluetoothTransfer(
        request: FileTransferCancelRequestDto,
        sessionId: String,
        peer: DevicePeer
    ): FileTransferCancelResponseDto
}
