package com.stash.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.stash.core.data.seed.DatabaseSeeder
import com.stash.core.data.sync.SyncNotificationManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Implements [Configuration.Provider] so WorkManager uses the Hilt-provided
 * [HiltWorkerFactory] instead of the default reflection-based factory.
 * The default [androidx.startup.InitializationProvider] initializer for
 * WorkManager is removed in AndroidManifest.xml so that this manual
 * configuration takes effect.
 */
@HiltAndroidApp
class StashApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var databaseSeeder: DatabaseSeeder

    @Inject
    lateinit var syncNotificationManager: SyncNotificationManager

    /** Application-scoped coroutine scope for one-shot startup tasks. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Supply the custom [Configuration] that tells WorkManager to use the
     * Hilt-injected [HiltWorkerFactory], which enables @AssistedInject
     * constructors in all @HiltWorker classes.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        syncNotificationManager.createChannels()
        applicationScope.launch {
            databaseSeeder.seedIfEmpty()
        }
    }
}
