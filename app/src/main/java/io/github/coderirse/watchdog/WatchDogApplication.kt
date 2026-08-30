package io.github.coderirse.watchdog

import android.app.Application
import io.github.coderirse.watchdog.di.AppContainer

class WatchDogApplication : Application() {

    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}
