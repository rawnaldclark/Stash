package com.stash.core.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The download queue's pause, kept apart from every settings DataStore: a backup never carries it (DatabaseBackupManager). */
private val Context.downloadQueueStateStore: DataStore<Preferences> by preferencesDataStore(
    name = DownloadQueueState.DATASTORE_FILE,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Why the download queue is holding back, which every runner and screen reads:
 *
 * - [paused]: the user tapped Pause (the notification or the Downloads screen). Kept on disk, so it
 *   holds across a cold start, until Resume, "Sync now" or a new download tap clears it. While it
 *   is on, nothing downloads on its own: not the resume run, not the drain, not a sync's download
 *   step.
 * - [storageFull]: a download ran out of space. Only in memory: nothing retries on its own until
 *   the user taps Resume, or the next cold start (when space may have been freed).
 */
@Singleton
class DownloadQueueState @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val pausedKey = booleanPreferencesKey("paused")

    val paused: Flow<Boolean> = context.downloadQueueStateStore.data
        .map { it[pausedKey] ?: false }
        .distinctUntilChanged()

    suspend fun isPaused(): Boolean = paused.first()

    suspend fun setPaused(value: Boolean) {
        context.downloadQueueStateStore.edit { it[pausedKey] = value }
    }

    private val _storageFull = MutableStateFlow(false)
    val storageFull: StateFlow<Boolean> = _storageFull.asStateFlow()

    /**
     * The out-of-space hold. Clearing it (Resume, a tap, a song that landed) also takes down the
     * "Downloads paused" notification, wherever it is cleared from: left up, it said downloads
     * were paused while they ran.
     */
    fun setStorageFull(value: Boolean) {
        val was = _storageFull.getAndUpdate { value }
        if (was && !value) {
            runCatching { NotificationManagerCompat.from(context).cancel(SyncNotificationManager.NOTIFICATION_ID_DOWNLOADS_PAUSED) }
        }
    }

    companion object {
        /** Its DataStore file's name, which backups leave out. */
        const val DATASTORE_FILE = "download_queue_state"
    }
}
