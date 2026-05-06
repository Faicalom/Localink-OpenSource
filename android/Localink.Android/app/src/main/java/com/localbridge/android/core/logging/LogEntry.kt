package com.localbridge.android.core.logging

data class LogEntry(
    val level: String,
    val message: String,
    val timestampUtc: String
)
