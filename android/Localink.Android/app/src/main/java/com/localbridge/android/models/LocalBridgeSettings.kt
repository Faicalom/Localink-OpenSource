package com.localbridge.android.models

import com.localbridge.android.core.AppConstants

data class LocalBridgeSettings(
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
            "localbridge/transfers/$receiveFolderName"
        }
}
