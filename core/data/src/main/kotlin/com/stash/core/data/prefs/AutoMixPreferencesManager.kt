package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.autoMixDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "automix_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

@Singleton
class AutoMixPreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : AutoMixPreference {
    private val enabledKey = booleanPreferencesKey("automix_enabled")
    private val modeKey = stringPreferencesKey("automix_mode")

    override val enabled: Flow<Boolean> = context.autoMixDataStore.data.map { it[enabledKey] ?: false }
    override val mode: Flow<AutoMixMode> = context.autoMixDataStore.data.map { prefs ->
        runCatching { AutoMixMode.valueOf(prefs[modeKey].orEmpty()) }.getOrDefault(AutoMixMode.BEAT_SYNC)
    }

    override suspend fun setEnabled(value: Boolean) {
        context.autoMixDataStore.edit { it[enabledKey] = value }
    }

    override suspend fun setMode(value: AutoMixMode) {
        context.autoMixDataStore.edit { it[modeKey] = value.name }
    }
}
