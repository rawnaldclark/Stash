package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.data.radio.RadioEngineId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Dedicated DataStore for the radio engine choice. Internal so tests can write a stale key. */
internal val Context.radioEngineDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "radio_engine_preference",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * DataStore-backed [RadioEnginePreference]; one string key holding a
 * [RadioEngineId.key] (never the enum name, which would rename on a refactor).
 *
 * Both an unknown stored key and an unreadable store read as [DEFAULT] rather than
 * failing: this preference sits in front of every station the player builds, and a
 * broken store must not be able to wedge playback.
 */
@Singleton
class RadioEnginePreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : RadioEnginePreference {

    private val engineKey = stringPreferencesKey("radio_engine")

    override val engine: Flow<RadioEngineId> = context.radioEngineDataStore.data.map { prefs ->
        RadioEngineId.fromKey(prefs[engineKey]) ?: DEFAULT
    }.distinctUntilChanged().catch { emit(DEFAULT) }

    override suspend fun current(): RadioEngineId = engine.first()

    override suspend fun setEngine(id: RadioEngineId) {
        context.radioEngineDataStore.edit { it[engineKey] = id.key }
    }

    companion object {
        /** YouTube Music (owner 2026-11-15); see [RadioEnginePreference] for why it's safe as a default. */
        val DEFAULT: RadioEngineId = RadioEngineId.YOUTUBE_MUSIC
    }
}
