package com.localbridge.android.models

import kotlinx.serialization.Serializable

@Serializable
data class TransferItem(
    val id: String,
    val fileName: String,
    val peerId: String,
    val peerName: String,
    val direction: TransferDirection,
    val kind: String,
    val mimeType: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val status: TransferState,
    val createdAtUtc: String,
    val fileCreatedAtUtc: String,
    val startedAtUtc: String? = null,
    val completedAtUtc: String? = null,
    val speedBytesPerSecond: Long = 0,
    val estimatedSecondsRemaining: Long? = null,
    val sourcePath: String? = null,
    val savedPath: String? = null,
    val lastError: String = "",
    val chunkSize: Int = 0,
    val totalChunks: Int = 0,
    val processedChunks: Int = 0
) {
    val progress: Float
        get() = if (totalBytes <= 0) 0f else transferredBytes.toFloat() / totalBytes.toFloat()

    val progressPercent: Int
        get() = (progress * 100f).toInt().coerceIn(0, 100)

    val statusLabel: String
        get() = when (status) {
            TransferState.Queued -> "Queued"
            TransferState.Preparing -> "Preparing"
            TransferState.Sending -> "Sending"
            TransferState.Receiving -> "Receiving"
            TransferState.Paused -> "Paused"
            TransferState.Completed -> "Completed"
            TransferState.Failed -> "Failed"
            TransferState.Canceled -> "Canceled"
        }

    val directionLabel: String
        get() = if (direction == TransferDirection.Outgoing) "Outgoing" else "Incoming"

    val canPause: Boolean
        get() = direction == TransferDirection.Outgoing &&
            status in setOf(TransferState.Queued, TransferState.Preparing, TransferState.Sending)

    val canResume: Boolean
        get() = direction == TransferDirection.Outgoing && status == TransferState.Paused

    val canCancel: Boolean
        get() = status in setOf(
            TransferState.Queued,
            TransferState.Preparing,
            TransferState.Sending,
            TransferState.Receiving,
            TransferState.Paused
        )

    val canOpen: Boolean
        get() = status == TransferState.Completed && !savedPath.isNullOrBlank()

    val previewPath: String?
        get() = savedPath ?: sourcePath

    val isImagePreviewCandidate: Boolean
        get() = when {
            kind.equals("image", ignoreCase = true) -> !previewPath.isNullOrBlank()
            mimeType.startsWith("image/", ignoreCase = true) -> !previewPath.isNullOrBlank()
            else -> previewPath
                ?.substringAfterLast('.', "")
                ?.lowercase()
                ?.let { extension ->
                    extension in setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
                } == true
        }

    val previewFallbackLabel: String
        get() = when {
            kind.equals("image", ignoreCase = true) -> "Image preview unavailable"
            kind.equals("video", ignoreCase = true) -> "Video file"
            kind.equals("document", ignoreCase = true) || mimeType == "application/pdf" -> "PDF document"
            kind.equals("text", ignoreCase = true) || mimeType.startsWith("text/", ignoreCase = true) -> "Text file"
            else -> fileName.substringAfterLast('.', "").takeIf { it.isNotBlank() }?.uppercase()?.plus(" file")
                ?: "Generic file"
        }

    val sizeLabel: String
        get() = "${formatBytes(transferredBytes)} / ${formatBytes(totalBytes)}"

    val speedLabel: String
        get() = if (speedBytesPerSecond <= 0) {
            "Speed --"
        } else {
            "Speed ${formatBytes(speedBytesPerSecond)}/s"
        }

    val etaLabel: String
        get() = estimatedSecondsRemaining
            ?.takeIf { it > 0 }
            ?.let { seconds ->
                val minutes = seconds / 60
                val remainder = seconds % 60
                "ETA %02d:%02d".format(minutes, remainder)
            } ?: "ETA --"

    companion object {
        fun formatBytes(bytes: Long): String {
            var value = bytes.coerceAtLeast(0).toDouble()
            val units = arrayOf("B", "KB", "MB", "GB")
            var unitIndex = 0

            while (value >= 1024.0 && unitIndex < units.lastIndex) {
                value /= 1024.0
                unitIndex += 1
            }

            return if (unitIndex == 0) {
                "${value.toLong()} ${units[unitIndex]}"
            } else {
                String.format("%.1f %s", value, units[unitIndex])
            }
        }
    }
}
