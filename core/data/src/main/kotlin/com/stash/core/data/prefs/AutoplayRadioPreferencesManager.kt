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
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.autoplayRadioDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "autoplay_radio_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** DataStore-backed [AutoplayRadioPreference]; one boolean, off by default. */
@Singleton
class AutoplayRadioPreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : AutoplayRadioPreference {

    private val enabledKey = booleanPreferencesKey("autoplay_radio_enabled")

    override val enabled: Flow<Boolean> = context.autoplayRadioDataStore.data.map { prefs ->
        prefs[enabledKey] ?: false
    }

    override suspend fun setEnabled(value: Boolean) {
        context.autoplayRadioDataStore.edit { prefs -> prefs[enabledKey] = value }
    }
}
