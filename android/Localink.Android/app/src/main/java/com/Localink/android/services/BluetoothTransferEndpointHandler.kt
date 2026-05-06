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
import com.localink.android.models.LocalDeviceProfile

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
