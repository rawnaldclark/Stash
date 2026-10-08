package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.nowPlayingDataStore by preferencesDataStore(
    name = "now_playing_preference",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** How Now Playing's ambient moves (Settings > Appearance > Ambient style). */
enum class AmbientStyle {
    /** The default: the web player's soft orbs. */
    SOFT_GLOW,

    /** The app's original ambient. */
    CLASSIC,
}

@Singleton
class NowPlayingPreference @Inject constructor(@ApplicationContext private val context: Context) {
    private val ambientAnimationKey = booleanPreferencesKey("ambient_animation_enabled")
    val ambientAnimationEnabled = context.nowPlayingDataStore.data.map { it[ambientAnimationKey] ?: true }

    suspend fun setAmbientAnimationEnabled(enabled: Boolean) {
        context.nowPlayingDataStore.edit { it[ambientAnimationKey] = enabled }
    }

    private val ambientStyleKey = stringPreferencesKey("ambient_style")

    /** Unset or unknown reads as [AmbientStyle.SOFT_GLOW], so existing installs get the default. */
    val ambientStyle = context.nowPlayingDataStore.data.map { prefs ->
        prefs[ambientStyleKey]?.let { name -> AmbientStyle.entries.firstOrNull { it.name == name } }
            ?: AmbientStyle.SOFT_GLOW
    }

    suspend fun setAmbientStyle(style: AmbientStyle) {
        context.nowPlayingDataStore.edit { it[ambientStyleKey] = style.name }
    }
}
