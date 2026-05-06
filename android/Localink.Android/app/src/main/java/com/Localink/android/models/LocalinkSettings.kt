package com.localink.android.models

import com.localink.android.core.AppConstants

data class LocalinkSettings(
    val preferredMode: AppConnectionMode = AppConnectionMode.LocalWifiLan,
    val receiveFolderName: String = AppConstants.defaultReceiveFolderName,
    val receiveTreeUri: String? = null,
    val receiveTreeDisplayName: String? = null,
    val deviceAlias: String = "Android Phone",
    val darkThemeEnabled: Boolean = true,
    val language: AppLanguage = AppLanguage.English
) {
    val hasExternalReceiveFolder: Boolean
        get() = !receiveTreeUri.isNullOrBlank()

    val receiveFolderLabel: String
        get() = if (hasExternalReceiveFolder) {
            receiveTreeDisplayName ?: "Picked SAF directory"
        } else {
            "localink/transfers/$receiveFolderName"
        }
}
