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

// A corrupt file (a crash mid-write) comes back as the defaults instead of throwing on every read.
private val Context.headphoneDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "headphone_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** DataStore-backed [HeadphonePreference]; one boolean, off by default. */
@Singleton
class HeadphonePreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : HeadphonePreference {

    private val resumeOnReconnectKey = booleanPreferencesKey("resume_on_reconnect")

    override val resumeOnReconnect: Flow<Boolean> = context.headphoneDataStore.data
        .map { prefs -> prefs[resumeOnReconnectKey] ?: false }

    override suspend fun setResumeOnReconnect(enabled: Boolean) {
        context.headphoneDataStore.edit { prefs ->
            prefs[resumeOnReconnectKey] = enabled
        }
    }
}
