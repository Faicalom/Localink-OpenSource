package com.localbridge.android.services

import com.localbridge.android.core.protocol.FileTransferCancelRequestDto
import com.localbridge.android.core.protocol.FileTransferCancelResponseDto
import com.localbridge.android.core.protocol.FileTransferChunkDescriptorDto
import com.localbridge.android.core.protocol.FileTransferChunkResponseDto
import com.localbridge.android.core.protocol.FileTransferCompleteRequestDto
import com.localbridge.android.core.protocol.FileTransferCompleteResponseDto
import com.localbridge.android.core.protocol.FileTransferPrepareRequestDto
import com.localbridge.android.core.protocol.FileTransferPrepareResponseDto
import com.localbridge.android.models.DevicePeer

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
