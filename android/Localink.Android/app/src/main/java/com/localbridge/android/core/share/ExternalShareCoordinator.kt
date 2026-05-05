package com.localbridge.android.core.share

import android.content.Intent
import android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.content.Context
import android.net.Uri
import android.os.Build
import com.localbridge.android.core.logging.LoggerService
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class PendingShareStage {
    AwaitingQr,
    Connecting,
    Sending
}

data class PendingShareRequest(
    val id: String = UUID.randomUUID().toString(),
    val uris: List<Uri>,
    val stage: PendingShareStage = PendingShareStage.AwaitingQr,
    val shouldLaunchQrScanner: Boolean = true
) {
    val itemCount: Int
        get() = uris.size
}

class ExternalShareCoordinator(
    private val appContext: Context,
    private val loggerService: LoggerService
) {
    private val _pendingShare = MutableStateFlow<PendingShareRequest?>(null)

    val pendingShare: StateFlow<PendingShareRequest?> = _pendingShare.asStateFlow()

    fun ingestShareIntent(intent: Intent?): Boolean {
        if (intent == null) {
            return false
        }

        if (intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            return false
        }

        val uris = extractSharedUris(intent)
        if (uris.isEmpty()) {
            return false
        }

        retainReadAccess(intent, uris)
        _pendingShare.value = PendingShareRequest(uris = uris)
        loggerService.info("[SHARE] Android received ${uris.size} shared item(s) and is waiting for a Windows QR scan.")
        return true
    }

    fun markScannerPresented() {
        _pendingShare.update { current ->
            current?.copy(shouldLaunchQrScanner = false)
        }
    }

    fun markConnecting() {
        _pendingShare.update { current ->
            current?.copy(
                stage = PendingShareStage.Connecting,
                shouldLaunchQrScanner = false
            )
        }
    }

    fun markSending() {
        _pendingShare.update { current ->
            current?.copy(
                stage = PendingShareStage.Sending,
                shouldLaunchQrScanner = false
            )
        }
    }

    fun clear() {
        _pendingShare.value = null
    }

    private fun extractSharedUris(intent: Intent): List<Uri> {
        val uris = buildList {
            singleSharedUri(intent)?.let(::add)
            multipleSharedUris(intent).forEach(::add)
            val clipData = intent.clipData
            if (clipData != null) {
                for (index in 0 until clipData.itemCount) {
                    clipData.getItemAt(index).uri?.let(::add)
                }
            }
            intent.data?.let(::add)
        }

        return uris
            .map(Uri::normalizeScheme)
            .distinctBy(Uri::toString)
    }

    private fun retainReadAccess(intent: Intent, uris: List<Uri>) {
        val incomingFlags = intent.flags and
            (FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION or FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

        if (incomingFlags and FLAG_GRANT_READ_URI_PERMISSION == 0) {
            return
        }

        val persistableFlags = incomingFlags and (FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)
        if (incomingFlags and FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0 || persistableFlags == 0) {
            return
        }

        uris.forEach { uri ->
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(uri, persistableFlags)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun singleSharedUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
    }

    @Suppress("DEPRECATION")
    private fun multipleSharedUris(intent: Intent): List<Uri> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
    }
}
