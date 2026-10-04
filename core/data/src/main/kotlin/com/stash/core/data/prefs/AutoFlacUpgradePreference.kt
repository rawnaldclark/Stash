package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Dedicated DataStore for the auto FLAC upgrade opt-in (a settings backup carries it). */
private val Context.autoFlacUpgradeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "auto_flac_upgrade_preference",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Whether the FLAC upgrade sweep runs by itself after every sync
 * ([com.stash.core.data.lossless.FlacUpgradeSweeper.afterSync]). Off by default:
 * each sweep queues every lossy download for a lossless lookup. Library Health's
 * "Check for upgrades" runs the same sweep on demand whatever this says.
 */
@Singleton
class AutoFlacUpgradePreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val enabledKey = booleanPreferencesKey("enabled")

    val enabled: Flow<Boolean> = context.autoFlacUpgradeDataStore.data.map { prefs ->
        prefs[enabledKey] ?: false
    }

    suspend fun current(): Boolean = enabled.first()

    suspend fun setEnabled(value: Boolean) {
        context.autoFlacUpgradeDataStore.edit { it[enabledKey] = value }
    }
}
