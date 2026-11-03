package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "headphone_preferences")

@Singleton
class HeadphonePreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : HeadphonePreference {

    private val RESUME_ON_RECONNECT = booleanPreferencesKey("resume_on_reconnect")

    override val resumeOnReconnect: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[RESUME_ON_RECONNECT] ?: false }

    override suspend fun setResumeOnReconnect(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[RESUME_ON_RECONNECT] = enabled
        }
    }
}
