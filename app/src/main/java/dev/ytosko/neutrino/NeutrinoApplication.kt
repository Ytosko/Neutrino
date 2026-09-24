package dev.ytosko.neutrino

import android.app.Application

/**
 * Application entry point. Holds the [AppContainer] for simple, explicit
 * dependency injection (no DI framework).
 */
class NeutrinoApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** App-wide dependencies. Repositories (Health Connect, AI providers, backups) are added here. */
class AppContainer(@Suppress("unused") private val application: Application)
