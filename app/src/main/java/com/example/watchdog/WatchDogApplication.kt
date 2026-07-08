package com.example.watchdog

import android.app.Application
import com.example.watchdog.di.AppContainer

class WatchDogApplication : Application() {

    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        appContainer = AppContainer(this)
    }

    companion object {
        lateinit var instance: WatchDogApplication
            private set
    }
}
