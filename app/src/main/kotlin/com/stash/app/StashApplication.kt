package com.stash.app

import android.app.Application
import com.stash.core.data.seed.DatabaseSeeder
import com.stash.core.data.sync.SyncNotificationManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class StashApplication : Application() {

    @Inject
    lateinit var databaseSeeder: DatabaseSeeder

    @Inject
    lateinit var syncNotificationManager: SyncNotificationManager

    /** Application-scoped coroutine scope for one-shot startup tasks. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        syncNotificationManager.createChannels()
        applicationScope.launch {
            databaseSeeder.seedIfEmpty()
        }
    }
}
