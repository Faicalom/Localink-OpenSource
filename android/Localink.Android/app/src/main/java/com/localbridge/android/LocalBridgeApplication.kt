package com.localbridge.android

import android.app.Application
import com.localbridge.android.core.di.AppContainer

class LocalBridgeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}
