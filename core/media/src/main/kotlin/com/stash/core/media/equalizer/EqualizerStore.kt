package com.stash.core.media.equalizer

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Top-level extension property for the equalizer DataStore.
 *
 * Declared at file level (outside the class) as required by the
 * [preferencesDataStore] delegate — it must be a top-level property
 * or an extension property on [Context].
 */
private val Context.eqDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "equalizer_prefs",
)

/**
 * Persists [EqualizerSettings] using Jetpack DataStore Preferences.
 *
 * Follows the same pattern as [com.stash.core.media.PlaybackStateStore]:
 * top-level DataStore delegate, [Keys] object for preference keys,
 * suspend read/write methods, and a reactive [Flow].
 */
@Singleton
class EqualizerStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private object Keys {
        val ENABLED = booleanPreferencesKey("eq_enabled")
        val PRESET = stringPreferencesKey("eq_preset")
        val CUSTOM_GAINS = stringPreferencesKey("eq_custom_gains")
        val BASS_BOOST = intPreferencesKey("bass_boost_strength")
        val VIRTUALIZER = intPreferencesKey("virtualizer_strength")
        val LOUDNESS = intPreferencesKey("loudness_gain")
    }

    /**
     * Reads the current settings snapshot. Returns defaults if nothing has been saved.
     */
    suspend fun getSettings(): EqualizerSettings {
        val prefs = context.eqDataStore.data.first()
        return prefs.toEqualizerSettings()
    }

    /**
     * Persists all equalizer settings atomically.
     */
    suspend fun saveSettings(settings: EqualizerSettings) {
        context.eqDataStore.edit { prefs ->
            prefs[Keys.ENABLED] = settings.enabled
            prefs[Keys.PRESET] = settings.preset
            prefs[Keys.CUSTOM_GAINS] = gainsToJson(settings.customGains)
            prefs[Keys.BASS_BOOST] = settings.bassBoostStrength
            prefs[Keys.VIRTUALIZER] = settings.virtualizerStrength
            prefs[Keys.LOUDNESS] = settings.loudnessGainMb
        }
    }

    /**
     * Reactive stream of [EqualizerSettings], emitting on every change.
     * Useful for UI layers that observe settings in real time.
     */
    val settingsFlow: Flow<EqualizerSettings> =
        context.eqDataStore.data.map { prefs -> prefs.toEqualizerSettings() }

    // ---- Serialization helpers ----

    /**
     * Maps a [Preferences] snapshot to an [EqualizerSettings] instance,
     * falling back to safe defaults for any missing key.
     */
    private fun Preferences.toEqualizerSettings(): EqualizerSettings = EqualizerSettings(
        enabled = this[Keys.ENABLED] ?: true,
        preset = this[Keys.PRESET] ?: EqPreset.FLAT.name,
        customGains = this[Keys.CUSTOM_GAINS]?.let { gainsFromJson(it) } ?: emptyList(),
        bassBoostStrength = this[Keys.BASS_BOOST] ?: 0,
        virtualizerStrength = this[Keys.VIRTUALIZER] ?: 0,
        loudnessGainMb = this[Keys.LOUDNESS] ?: 0,
    )

    /**
     * Serialises a list of gain values to a compact JSON array string.
     * Example: [800, 500, 0, 0, 0] -> "[800,500,0,0,0]"
     */
    private fun gainsToJson(gains: List<Int>): String {
        val array = JSONArray()
        gains.forEach { array.put(it) }
        return array.toString()
    }

    /**
     * Deserialises a JSON array string back to a list of gain values.
     * Returns an empty list if parsing fails.
     */
    private fun gainsFromJson(json: String): List<Int> {
        return try {
            val array = JSONArray(json)
            List(array.length()) { i -> array.getInt(i) }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
