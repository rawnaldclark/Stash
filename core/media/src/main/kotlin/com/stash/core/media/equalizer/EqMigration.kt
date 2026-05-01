// EqMigration.kt
package com.stash.core.media.equalizer

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the legacy equalizer DataStore to the new [EqStore].
 *
 * Runs at most once per install (gated by [EqStore.read].presetId being
 * present and non-default, or by an internal "migrated" sentinel).
 *
 * Forces `enabled = false` regardless of the legacy value. The legacy
 * code's stuck-on bug means a true value can't be trusted; user re-enables
 * via the new UI when they want.
 */
@Singleton
class EqMigration @Inject constructor(
  private val newStore: EqStore,
  private val legacyStore: LegacyEqualizerStore,
) {
  suspend fun migrateIfNeeded() {
    val current = newStore.read()
    if (current.presetId != "flat" || current != EqState()) return // already migrated

    val migrated = if (legacyStore.exists()) {
      val legacy = legacyStore.readLegacy()
      EqState(
        enabled = false,
        presetId = mapLegacyPresetName(legacy.presetName),
        gainsDb = adaptGains(legacy.gains),
        bassBoostDb = legacy.bassBoostStrength.coerceIn(0, 1000) / 1000f * 15f,
      )
    } else {
      EqState(enabled = false)
    }

    newStore.write(migrated)
    if (legacyStore.exists()) legacyStore.deleteLegacy()
  }

  private fun mapLegacyPresetName(name: String): String = when (name.uppercase()) {
    "FLAT"          -> "flat"
    "BASS_BOOST"    -> "bass"
    "TREBLE_BOOST"  -> "treble"
    "VOCAL"         -> "vocal"
    "ROCK"          -> "rock"
    "POP"           -> "pop"
    "JAZZ"          -> "jazz"
    "CLASSICAL"     -> "classical"
    else            -> "flat"
  }

  private fun adaptGains(legacy: List<Int>): FloatArray {
    // Empty legacy gains crashed v0.8.0 in the wild — `coerceIn(0, -1)`
    // throws because lastIndex is -1 for an empty list. Reproduces when
    // the legacy DataStore had `eq_custom_gains = "[]"` (empty JSON
    // array), which parses to an empty list and slips past the
    // `?: listOf(0,0,0,0,0)` null fallback in LegacyEqualizerStoreImpl.
    // Any empty / missing legacy gains is interpreted as flat (zeros).
    if (legacy.isEmpty()) return FloatArray(5)
    val mb = if (legacy.size == 5) legacy
            else List(5) { i ->
              val ratio = i.toFloat() * (legacy.size - 1) / 4f
              val lo = legacy[ratio.toInt().coerceIn(0, legacy.lastIndex)]
              val hi = legacy[(ratio.toInt() + 1).coerceIn(0, legacy.lastIndex)]
              val frac = ratio - ratio.toInt()
              (lo + (hi - lo) * frac).toInt()
            }
    return mb.map { (it / 100f).coerceIn(-12f, 12f) }.toFloatArray()
  }
}

interface LegacyEqualizerStore {
  suspend fun exists(): Boolean
  suspend fun readLegacy(): LegacySettings
  suspend fun deleteLegacy()
}

data class LegacySettings(
  val enabled: Boolean,
  val presetName: String,
  val gains: List<Int>,        // millibels
  val bassBoostStrength: Int,  // 0..1000
)
