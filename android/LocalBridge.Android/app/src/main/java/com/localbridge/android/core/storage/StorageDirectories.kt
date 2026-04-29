package com.localbridge.android.core.storage

import android.content.Context
import android.os.Environment
import com.localbridge.android.core.AppConstants
import java.io.File

class StorageDirectories(context: Context) {
    val rootDirectory = File(context.filesDir, "localbridge")
    val logsDirectory = File(rootDirectory, "logs")
    val transfersDirectory = File(rootDirectory, "transfers")
    val receivedDirectory = File(transfersDirectory, AppConstants.defaultReceiveFolderName)
    val trustedDirectory = File(rootDirectory, AppConstants.defaultTrustedFolderName)

    fun ensureAll() {
        listOf(rootDirectory, logsDirectory, transfersDirectory, receivedDirectory, trustedDirectory)
            .forEach { directory ->
                if (!directory.exists()) {
                    directory.mkdirs()
                }
            }
    }

    fun resolveReceivedDirectory(folderName: String): File {
        return File(transfersDirectory, sanitizeFolderName(folderName))
    }

    fun ensureReceivedDirectory(folderName: String): File {
        val directory = resolveReceivedDirectory(folderName)
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return directory
    }

    fun resolveLegacyPublicDownloadsDirectory(): File {
        return File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            AppConstants.defaultPublicDownloadsFolderName
        )
    }

    fun publicDownloadsDisplayPath(): String {
        return "Download/${AppConstants.defaultPublicDownloadsFolderName}"
    }

    companion object {
        fun sanitizeFolderName(input: String): String {
            val sanitized = input
                .trim()
                .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
                .replace(Regex("\\s+"), "-")
                .trim('_', '-', '.')

            return sanitized.ifBlank { AppConstants.defaultReceiveFolderName }
        }
    }
}
