package com.localbridge.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import android.content.pm.PackageManager
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import com.localbridge.android.core.permissions.PermissionsController
import com.localbridge.android.ui.LocalBridgeAndroidApp

class MainActivity : ComponentActivity() {
    private val permissionsController = PermissionsController()
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        (application as? LocalBridgeApplication)?.container?.refreshRuntimeState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissionsIfNeeded()
        handleIncomingIntent(intent)
        setContent {
            LocalBridgeAndroidApp()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val missingPermissions = permissionsController.runtimeDangerousPermissions()
            .filter { permission ->
                ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
            }

        if (missingPermissions.isNotEmpty()) {
            permissionsLauncher.launch(missingPermissions.toTypedArray())
        } else {
            (application as? LocalBridgeApplication)?.container?.refreshRuntimeState()
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        (application as? LocalBridgeApplication)
            ?.container
            ?.externalShareCoordinator
            ?.ingestShareIntent(intent)
    }
}
