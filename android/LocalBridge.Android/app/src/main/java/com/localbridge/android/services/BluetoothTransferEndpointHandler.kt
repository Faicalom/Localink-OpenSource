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
import com.localbridge.android.models.LocalDeviceProfile

interface BluetoothTransferEndpointHandler {
    suspend fun prepareIncomingBluetoothTransfer(
        request: FileTransferPrepareRequestDto,
        peer: DevicePeer,
        sessionId: String,
        localDevice: LocalDeviceProfile
    ): FileTransferPrepareResponseDto

    suspend fun receiveIncomingBluetoothChunk(
        descriptor: FileTransferChunkDescriptorDto,
        chunkBytes: ByteArray,
        peer: DevicePeer,
        sessionId: String
    ): FileTransferChunkResponseDto

    suspend fun completeIncomingBluetoothTransfer(
        request: FileTransferCompleteRequestDto,
        peer: DevicePeer,
        sessionId: String
    ): FileTransferCompleteResponseDto

    suspend fun cancelIncomingBluetoothTransfer(
        request: FileTransferCancelRequestDto,
        peer: DevicePeer,
        sessionId: String
    ): FileTransferCancelResponseDto
}
