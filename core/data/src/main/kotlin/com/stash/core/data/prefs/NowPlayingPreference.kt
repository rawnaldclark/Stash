package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.nowPlayingDataStore by preferencesDataStore(
    name = "now_playing_preference",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

@Singleton
class NowPlayingPreference @Inject constructor(@ApplicationContext private val context: Context) {
    private val ambientAnimationKey = booleanPreferencesKey("ambient_animation_enabled")
    val ambientAnimationEnabled = context.nowPlayingDataStore.data.map { it[ambientAnimationKey] ?: true }

    suspend fun setAmbientAnimationEnabled(enabled: Boolean) {
        context.nowPlayingDataStore.edit { it[ambientAnimationKey] = enabled }
    }
}
