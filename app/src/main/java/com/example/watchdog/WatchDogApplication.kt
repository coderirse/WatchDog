package com.example.watchdog

import android.app.Application
import com.example.watchdog.di.AppContainer

class WatchDogApplication : Application() {

    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}
