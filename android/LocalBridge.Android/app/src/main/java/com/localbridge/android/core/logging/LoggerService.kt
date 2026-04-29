package com.localbridge.android.core.logging

import kotlinx.coroutines.flow.StateFlow

interface LoggerService {
    val recentEntries: StateFlow<List<LogEntry>>

    fun launch()
    fun info(message: String)
    fun warning(message: String)
    fun error(message: String, throwable: Throwable? = null)
}
