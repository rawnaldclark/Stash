package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.model.DownloadNetworkMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Extension property providing a singleton DataStore for download network preference. */
private val Context.downloadNetworkDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "download_network_preference",
)

/**
 * Persists the user's chosen [DownloadNetworkMode] for background
 * downloads. Both `StashDiscoveryWorker` and `TagEnrichmentWorker` read
 * this at schedule time to decide what `Constraints` to apply.
 *
 * When the user changes the mode, the Settings layer is responsible for
 * re-scheduling the affected workers so the new constraints take effect —
 * WorkManager snapshots constraints at enqueue time, it doesn't observe
 * this flow itself.
 *
 * Stored by enum name so it survives ordinal shifts across app versions.
 */
@Singleton
class DownloadNetworkPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val modeKey = stringPreferencesKey("download_network_mode")

    /** Reactive stream of the currently-selected mode, defaulting to [DownloadNetworkMode.WIFI_AND_CHARGING]. */
    val mode: Flow<DownloadNetworkMode> = context.downloadNetworkDataStore.data.map { prefs ->
        val name = prefs[modeKey]
        name?.let { runCatching { DownloadNetworkMode.valueOf(it) }.getOrNull() }
            ?: DownloadNetworkMode.WIFI_AND_CHARGING
    }

    /** Snapshot of the current mode — used at worker schedule time. */
    suspend fun current(): DownloadNetworkMode = mode.first()

    /** Persist [value]. Caller is responsible for re-scheduling workers. */
    suspend fun setMode(value: DownloadNetworkMode) {
        context.downloadNetworkDataStore.edit { prefs ->
            prefs[modeKey] = value.name
        }
    }
}
