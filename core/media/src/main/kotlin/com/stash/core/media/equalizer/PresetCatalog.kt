// PresetCatalog.kt
package com.stash.core.media.equalizer

/**
 * Built-in graphic-EQ presets. Gain values match the spec
 * (docs/superpowers/specs/2026-04-30-equalizer-redesign-design.md §UI).
 *
 * Each preset is a [NamedPreset] for symmetry with user-saved presets;
 * built-in IDs are stable strings so persisted state survives re-orderings.
 */
object PresetCatalog {
  val builtIn: List<NamedPreset> = listOf(
    NamedPreset("flat",       "Flat",         floatArrayOf( 0f,  0f,  0f,  0f,  0f), 0f),
    NamedPreset("bass",       "Bass Boost",   floatArrayOf(+5f, +3f,  0f,  0f,  0f), 0f),
    NamedPreset("treble",     "Treble Boost", floatArrayOf( 0f,  0f,  0f, +3f, +5f), 0f),
    NamedPreset("vocal",      "Vocal",        floatArrayOf(-2f,  0f, +3f, +2f,  0f), 0f),
    NamedPreset("rock",       "Rock",         floatArrayOf(+4f, +2f, -1f, +2f, +3f), 0f),
    NamedPreset("pop",        "Pop",          floatArrayOf(-1f, +2f, +3f, +2f, -1f), 0f),
    NamedPreset("jazz",       "Jazz",         floatArrayOf(+3f, +2f,  0f, +1f, +3f), 0f),
    NamedPreset("classical",  "Classical",    floatArrayOf(+4f, +3f, -2f,  0f, +2f), 0f),
  )

  fun byId(id: String): NamedPreset? = builtIn.firstOrNull { it.id == id }

  /** Combined catalog for UI display; built-ins first, custom after. */
  fun allFor(state: EqState): List<NamedPreset> = builtIn + state.customPresets
}
