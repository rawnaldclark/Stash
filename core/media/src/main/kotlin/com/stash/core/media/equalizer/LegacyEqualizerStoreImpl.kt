// LegacyEqualizerStoreImpl.kt
package com.stash.core.media.equalizer

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import org.json.JSONArray

// Uses the same DataStore name as the legacy EqualizerStore so it reads
// the real on-device data written by the old code.
private val Context.legacyEqDataStore by preferencesDataStore("equalizer_prefs")

@Singleton
class LegacyEqualizerStoreImpl @Inject constructor(
  @ApplicationContext private val context: Context,
) : LegacyEqualizerStore {

  // Exact key strings from legacy EqualizerStore.Keys (verified in Step 5.0):
  //   booleanPreferencesKey("eq_enabled")
  //   stringPreferencesKey("eq_preset")
  //   stringPreferencesKey("eq_custom_gains")   ← JSON array, NOT per-band ints
  //   intPreferencesKey("bass_boost_strength")
  //   intPreferencesKey("virtualizer_strength") — not migrated (new store has no virtualizer)
  //   intPreferencesKey("loudness_gain")        — not migrated (no equivalent in EqState)
  private val K_ENABLED      = booleanPreferencesKey("eq_enabled")
  private val K_PRESET       = stringPreferencesKey("eq_preset")
  private val K_CUSTOM_GAINS = stringPreferencesKey("eq_custom_gains")
  private val K_BASS         = intPreferencesKey("bass_boost_strength")

  override suspend fun exists(): Boolean {
    val prefs = context.legacyEqDataStore.data.first()
    return prefs[K_ENABLED] != null || prefs[K_PRESET] != null
  }

  override suspend fun readLegacy(): LegacySettings {
    val prefs = context.legacyEqDataStore.data.first()
    val gains = prefs[K_CUSTOM_GAINS]?.let { parseGainsJson(it) }
      ?: listOf(0, 0, 0, 0, 0)
    return LegacySettings(
      enabled = prefs[K_ENABLED] ?: false,
      presetName = prefs[K_PRESET] ?: "FLAT",
      gains = gains,
      bassBoostStrength = prefs[K_BASS] ?: 0,
    )
  }

  override suspend fun deleteLegacy() {
    context.legacyEqDataStore.edit { it.clear() }
  }

  private fun parseGainsJson(json: String): List<Int> = try {
    val array = JSONArray(json)
    // Empty JSON array (`"[]"`) was a real failure mode in v0.8.0 —
    // it parsed to an empty list and crashed EqMigration.adaptGains.
    // Treat empty just like malformed: fall back to default zeros.
    if (array.length() == 0) listOf(0, 0, 0, 0, 0)
    else List(array.length()) { i -> array.getInt(i) }
  } catch (_: Exception) {
    listOf(0, 0, 0, 0, 0)
  }
}
