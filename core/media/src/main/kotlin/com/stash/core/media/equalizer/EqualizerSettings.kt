package com.stash.core.media.equalizer

/**
 * Snapshot of all equalizer-related settings that are persisted across sessions.
 *
 * @property enabled      Whether the equalizer effects chain is active.
 * @property preset       Name of the active [EqPreset] (stored as the enum name).
 * @property customGains  Per-band gain values in millibels for the [EqPreset.CUSTOM] preset.
 *                        Empty list means "not yet customised" and will default to flat.
 * @property bassBoostStrength  BassBoost strength in the range 0..1000.
 * @property virtualizerStrength  Virtualizer strength in the range 0..1000.
 * @property loudnessGainMb  LoudnessEnhancer target gain in millibels (0 = off).
 */
data class EqualizerSettings(
    val enabled: Boolean = true,
    val preset: String = EqPreset.FLAT.name,
    val customGains: List<Int> = emptyList(),
    val bassBoostStrength: Int = 0,
    val virtualizerStrength: Int = 0,
    val loudnessGainMb: Int = 0,
)
