package com.stash.core.data.listening

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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.recapDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "recap_preference",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The Recap story's remembered choices: whether its music is muted (the mute button on the story).
 * Muted, the story never touches the main player. A failed read means "not muted".
 */
@Singleton
class RecapPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutedKey = booleanPreferencesKey("story_muted")

    val muted: Flow<Boolean> = context.recapDataStore.data
        .map { it[mutedKey] ?: false }
        .distinctUntilChanged()
        .catch { emit(false) }

    suspend fun setMuted(muted: Boolean) {
        context.recapDataStore.edit { it[mutedKey] = muted }
    }
}
