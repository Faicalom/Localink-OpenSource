package com.localbridge.android.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.localbridge.android.MainActivity
import com.localbridge.android.R
import com.localbridge.android.core.AppConstants

class TransferForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            actionStop -> {
                stopForegroundCompat()
                releaseLocks()
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                ensureNotificationChannel()
                acquireLocks()

                val activeCount = intent?.getIntExtra(extraActiveCount, 1)?.coerceAtLeast(1) ?: 1
                val headline = intent?.getStringExtra(extraHeadline).orEmpty().ifBlank {
                    if (activeCount == 1) {
                        "Localink transfer is running"
                    } else {
                        "$activeCount Localink transfers are running"
                    }
                }
                val detail = intent?.getStringExtra(extraDetail).orEmpty().ifBlank {
                    "Keeping hotspot/LAN transfer alive while the app is in the background."
                }

                val notification = NotificationCompat.Builder(this, AppConstants.transferForegroundNotificationChannelId)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(headline)
                    .setContentText(detail)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setContentIntent(
                        PendingIntent.getActivity(
                            this,
                            0,
                            Intent(this, MainActivity::class.java).apply {
                                this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            },
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                    .build()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        AppConstants.transferForegroundNotificationId,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(AppConstants.transferForegroundNotificationId, notification)
                }

                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(AppConstants.transferForegroundNotificationChannelId)
        if (existing != null) {
            return
        }

        manager.createNotificationChannel(
            NotificationChannel(
                AppConstants.transferForegroundNotificationChannelId,
                "Localink transfers",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Localink file transfers alive while Android is in the background."
                setShowBadge(false)
            }
        )
    }

    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:localbridge-transfer")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }

        if (wifiLock?.isHeld != true) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifiManager
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:localbridge-transfer")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null

        runCatching {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        }
        wifiLock = null
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        private const val actionStartOrUpdate = "com.localbridge.android.action.TRANSFER_FOREGROUND_START_OR_UPDATE"
        private const val actionStop = "com.localbridge.android.action.TRANSFER_FOREGROUND_STOP"
        private const val extraActiveCount = "active_count"
        private const val extraHeadline = "headline"
        private const val extraDetail = "detail"

        fun startOrUpdate(
            context: Context,
            activeCount: Int,
            headline: String,
            detail: String
        ) {
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                action = actionStartOrUpdate
                putExtra(extraActiveCount, activeCount)
                putExtra(extraHeadline, headline)
                putExtra(extraDetail, detail)
            }

            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TransferForegroundService::class.java).apply {
                action = actionStop
            })
        }
    }
}
