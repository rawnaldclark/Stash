// EqStore.kt
package com.stash.core.media.equalizer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Persistence wrapper for [EqState].
 *
 * Stores the entire state as a single kotlinx.serialization JSON string in
 * one Preferences DataStore key. This guarantees atomic writes — a partial
 * write during process death cannot leave EQ in a half-written state.
 *
 * Missing key → default (enabled = false). Corrupted JSON → default.
 * This is the bug-fix anchor: a fresh, partial, or broken store can never
 * produce "EQ effectively on" surprise behavior.
 */
@Singleton
class EqStore @Inject constructor(
  private val dataStore: DataStore<Preferences>,
) {
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  suspend fun read(): EqState {
    val raw = dataStore.data.first()[KEY] ?: return EqState()
    return try {
      json.decodeFromString(EqState.serializer(), raw)
    } catch (_: SerializationException) {
      EqState()
    } catch (_: IllegalArgumentException) {
      EqState()
    }
  }

  suspend fun write(state: EqState) {
    dataStore.edit { it[KEY] = json.encodeToString(EqState.serializer(), state) }
  }

  /** Test-only: write a raw string to simulate corruption. */
  internal suspend fun writeRaw(raw: String) {
    dataStore.edit { it[KEY] = raw }
  }

  companion object {
    private val KEY = stringPreferencesKey("eq_state_v1_json")
  }
}
