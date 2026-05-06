package com.localbridge.android.models

enum class AppLanguage(val wireValue: String) {
    English("en"),
    Arabic("ar");

    companion object {
        fun fromWireValue(value: String?): AppLanguage {
            return entries.firstOrNull { language ->
                language.wireValue.equals(value, ignoreCase = true)
            } ?: English
        }
    }
}
