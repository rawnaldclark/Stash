package com.stash.core.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * The installer's answer to [AppUpdater]'s session: "the listener must confirm" goes to [AppUpdater.onConfirmNeeded], which
 * opens Android's confirm screen, or holds it while Stash is in the background; anything else goes to
 * [AppUpdater.onInstallResult]. Each answer carries its session's id: one for a session staged earlier (abandoned when the
 * APK was staged again) is dropped there. Not exported: only this app's own PendingIntent reaches it.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface UpdaterEntryPoint {
        fun appUpdater(): AppUpdater
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val updater = EntryPointAccessors.fromApplication(context.applicationContext, UpdaterEntryPoint::class.java).appUpdater()
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            updater.onConfirmNeeded(sessionId, IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java))
        } else {
            updater.onInstallResult(sessionId, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}
