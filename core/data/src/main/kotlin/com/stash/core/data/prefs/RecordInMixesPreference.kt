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
 * Settings › Your record › "Use your record in mixes" (owner decision D2; stash-player settings `recordInMixes`), on by
 * default. Off, the mixes read no taste at all ([com.stash.core.data.record.RecordMixTaste] gives none), exactly as for someone
 * with no import; Your record and its Home card stay. The DataStore-backed implementation is [RecordInMixesPreferencesManager].
 */
interface RecordInMixesPreference {
    /** Whether the mixes use Your record, defaulting to `true`. */
    val enabled: Flow<Boolean>

    suspend fun setEnabled(value: Boolean)

    suspend fun current(): Boolean = enabled.first()

    /** In memory, for tests: on unless told otherwise. */
    class Fixed(on: Boolean = true) : RecordInMixesPreference {
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

/** DataStore-backed [RecordInMixesPreference]; one boolean, on by default. */
@Singleton
class RecordInMixesPreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : RecordInMixesPreference {
    private val enabledKey = booleanPreferencesKey("record_in_mixes")

    override val enabled: Flow<Boolean> = context.recordInMixesDataStore.data.map { it[enabledKey] ?: true }

    override suspend fun setEnabled(value: Boolean) {
        context.recordInMixesDataStore.edit { it[enabledKey] = value }
    }
}
