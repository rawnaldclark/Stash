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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Use your record in mixes" (owner decision D2; stash-player settings `recordInMixes`): a switch on Daily Discover's page and in
 * Settings › Library & Storage › Your record, the same preference. **Off by default** (owner 2026-11-04; it was on until then).
 * Only a tap stores a value, so "never touched" is "nothing stored": those people read off now, and anyone who turned it
 * on keeps it on. Off, the mixes read no taste at all ([com.stash.core.data.record.RecordMixTaste] gives none), exactly as for
 * someone with no import; Your record and its Home card stay. The DataStore-backed implementation is
 * [RecordInMixesPreferencesManager].
 */
interface RecordInMixesPreference {
    /** Whether the mixes use Your record, defaulting to `false` (nothing stored). */
    val enabled: Flow<Boolean>

    suspend fun setEnabled(value: Boolean)

    suspend fun current(): Boolean = enabled.first()

    /** In memory, for tests: off unless told otherwise, as the real one. */
    class Fixed(on: Boolean = false) : RecordInMixesPreference {
        private val state = MutableStateFlow(on)
        override val enabled: Flow<Boolean> = state
        override suspend fun setEnabled(value: Boolean) {
            state.value = value
        }
    }
}

private val Context.recordInMixesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "record_in_mixes_preference",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** DataStore-backed [RecordInMixesPreference]; one boolean, off until a tap stores one. */
@Singleton
class RecordInMixesPreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : RecordInMixesPreference {
    private val enabledKey = booleanPreferencesKey("record_in_mixes")

    override val enabled: Flow<Boolean> = context.recordInMixesDataStore.data.map { it[enabledKey] ?: DEFAULT }

    override suspend fun setEnabled(value: Boolean) {
        context.recordInMixesDataStore.edit { it[enabledKey] = value }
    }

    companion object {
        /** What nothing stored means. stash-player's `recordInMixes` default is off too. */
        const val DEFAULT = false
    }
}
