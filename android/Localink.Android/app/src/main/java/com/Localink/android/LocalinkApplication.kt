package com.localink.android

import android.app.Application
import com.localink.android.core.di.AppContainer

class LocalinkApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}
