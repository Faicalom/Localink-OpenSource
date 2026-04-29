package com.localbridge.android.core.logging

import android.util.Log
import com.localbridge.android.core.AppConstants
import com.localbridge.android.core.storage.StorageDirectories
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FileLoggerService(
    private val storageDirectories: StorageDirectories
) : LoggerService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileLock = Mutex()
    private val _recentEntries = MutableStateFlow(emptyList<LogEntry>())
    private lateinit var logFile: File

    override val recentEntries: StateFlow<List<LogEntry>> = _recentEntries.asStateFlow()

    override fun launch() {
        logFile = File(storageDirectories.logsDirectory, AppConstants.logsFileName)
        scope.launch {
            append("INFO", "Logger initialized.")
        }
    }

    override fun info(message: String) {
        emit("INFO", message, null)
    }

    override fun warning(message: String) {
        emit("WARN", message, null)
    }

    override fun error(message: String, throwable: Throwable?) {
        emit("ERROR", message, throwable)
    }

    private fun emit(level: String, message: String, throwable: Throwable?) {
        Log.println(
            when (level) {
                "WARN" -> Log.WARN
                "ERROR" -> Log.ERROR
                else -> Log.INFO
            },
            "LocalBridge",
            if (throwable == null) message else "$message | ${throwable.message}"
        )

        scope.launch {
            append(level, message, throwable)
        }
    }

    private suspend fun append(level: String, message: String, throwable: Throwable? = null) {
        val entry = LogEntry(
            level = level,
            message = if (throwable == null) message else "$message | ${throwable.message.orEmpty()}",
            timestampUtc = Instant.now().toString()
        )

        fileLock.withLock {
            if (!::logFile.isInitialized) {
                logFile = File(storageDirectories.logsDirectory, AppConstants.logsFileName)
            }

            logFile.parentFile?.mkdirs()
            logFile.appendText("[${entry.timestampUtc}] ${entry.level} ${entry.message}\n")
            _recentEntries.value = (_recentEntries.value + entry).takeLast(80)
        }
    }
}
