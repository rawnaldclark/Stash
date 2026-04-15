package com.stash.core.data.sync

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.model.SyncMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.syncPrefsDataStore by preferencesDataStore(name = "sync_preferences")

/**
 * User-configurable sync scheduling preferences backed by DataStore.
 *
 * @property syncHour   Hour of day (0-23) for the scheduled daily sync.
 * @property syncMinute Minute of hour (0-59) for the scheduled daily sync.
 * @property autoSyncEnabled Whether the daily auto-sync is active.
 * @property wifiOnly   Whether sync should only run on unmetered (Wi-Fi) connections.
 */
data class SyncPreferences(
    val syncHour: Int = 6,
    val syncMinute: Int = 0,
    val autoSyncEnabled: Boolean = true,
    val wifiOnly: Boolean = true,
    val syncMode: SyncMode = SyncMode.REFRESH,
)

/**
 * Singleton manager for reading and writing sync scheduling preferences.
 *
 * All values are persisted in a dedicated DataStore file (`sync_preferences`)
 * and exposed as a reactive [Flow] so that UI and background workers can
 * observe changes immediately.
 */
@Singleton
class SyncPreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val SYNC_HOUR = intPreferencesKey("sync_hour")
        val SYNC_MINUTE = intPreferencesKey("sync_minute")
        val AUTO_SYNC = booleanPreferencesKey("auto_sync_enabled")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val SYNC_MODE = stringPreferencesKey("sync_mode")
    }

    /** Reactive stream of the current [SyncPreferences]. */
    val preferences: Flow<SyncPreferences> = context.syncPrefsDataStore.data.map { prefs ->
        SyncPreferences(
            syncHour = prefs[Keys.SYNC_HOUR] ?: 6,
            syncMinute = prefs[Keys.SYNC_MINUTE] ?: 0,
            autoSyncEnabled = prefs[Keys.AUTO_SYNC] ?: true,
            wifiOnly = prefs[Keys.WIFI_ONLY] ?: true,
            syncMode = prefs[Keys.SYNC_MODE]?.let { name ->
                runCatching { SyncMode.valueOf(name) }.getOrDefault(SyncMode.REFRESH)
            } ?: SyncMode.REFRESH,
        )
    }

    /**
     * Reactive stream of just the [SyncMode] preference.
     *
     * Exposed separately so background workers can read only the mode
     * without pulling in all scheduling preferences.
     */
    val syncMode: Flow<SyncMode> = context.syncPrefsDataStore.data.map { prefs ->
        prefs[Keys.SYNC_MODE]?.let { name ->
            runCatching { SyncMode.valueOf(name) }.getOrDefault(SyncMode.REFRESH)
        } ?: SyncMode.REFRESH
    }

    /**
     * Persist a new sync schedule time.
     *
     * @param hour   Hour of day (0-23).
     * @param minute Minute of hour (0-59).
     */
    suspend fun setSyncTime(hour: Int, minute: Int) {
        context.syncPrefsDataStore.edit {
            it[Keys.SYNC_HOUR] = hour
            it[Keys.SYNC_MINUTE] = minute
        }
    }

    /** Enable or disable the daily automatic sync. */
    suspend fun setAutoSyncEnabled(enabled: Boolean) {
        context.syncPrefsDataStore.edit { it[Keys.AUTO_SYNC] = enabled }
    }

    /** Restrict sync to Wi-Fi only, or allow any network. */
    suspend fun setWifiOnly(wifiOnly: Boolean) {
        context.syncPrefsDataStore.edit { it[Keys.WIFI_ONLY] = wifiOnly }
    }

    /** Set the playlist sync mode (REFRESH or ACCUMULATE). */
    suspend fun setSyncMode(mode: SyncMode) {
        context.syncPrefsDataStore.edit { it[Keys.SYNC_MODE] = mode.name }
    }
}
