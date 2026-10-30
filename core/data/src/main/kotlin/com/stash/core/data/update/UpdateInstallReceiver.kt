package com.stash.core.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * The installer's answer to [AppUpdater]'s session: "the listener must confirm" opens Android's confirm screen; anything else
 * goes back to the updater. Not exported: only this app's own PendingIntent reaches it.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface UpdaterEntryPoint {
        fun appUpdater(): AppUpdater
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            if (confirm != null) {
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Log.w(TAG, "can't open the confirm: ${e.javaClass.simpleName}")
                }
            }
        }
        EntryPointAccessors.fromApplication(context.applicationContext, UpdaterEntryPoint::class.java)
            .appUpdater()
            .onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }

    private companion object {
        const val TAG = "UpdateInstall"
    }
}
