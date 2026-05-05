package com.localbridge.android.ui.widgets

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.localbridge.android.models.TransferItem
import java.io.File

object TransferFileActions {
    fun open(context: Context, transfer: TransferItem): Boolean {
        val uri = resolveUri(context, transfer) ?: return false
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, transfer.mimeType.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.isSuccess
    }

    fun share(context: Context, transfer: TransferItem): Boolean {
        val uri = resolveUri(context, transfer) ?: return false
        return runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = transfer.mimeType.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, transfer.fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share ${transfer.fileName}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    private fun resolveUri(context: Context, transfer: TransferItem) = transfer.savedPath?.let { savedPath ->
        if (savedPath.startsWith("content://")) {
            savedPath.toUri()
        } else {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                File(savedPath)
            )
        }
    }
}
