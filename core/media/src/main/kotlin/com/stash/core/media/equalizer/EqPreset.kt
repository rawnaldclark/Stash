package com.stash.core.media.equalizer

/**
 * Predefined equalizer presets with gain values for a 5-band equalizer.
 *
 * Gain values are in **millibels** (mB). One millibel = 1/1000 of a bel,
 * or 1/100 of a decibel. For example, 800 mB = 8.0 dB boost.
 *
 * The bands typically correspond to: 60Hz, 230Hz, 910Hz, 3.6kHz, 14kHz,
 * though actual center frequencies are device-dependent and discovered at runtime.
 *
 * If the device reports a different number of bands, [EqualizerManager] will
 * interpolate or pad these values to match.
 */
enum class EqPreset(val displayName: String, val gains: IntArray) {

    FLAT("Flat", intArrayOf(0, 0, 0, 0, 0)),
    BASS_BOOST("Bass Boost", intArrayOf(800, 500, 0, 0, 0)),
    TREBLE_BOOST("Treble Boost", intArrayOf(0, 0, 0, 500, 800)),
    ROCK("Rock", intArrayOf(500, 200, 0, 300, 600)),
    POP("Pop", intArrayOf(200, 100, 300, 200, -100)),
    JAZZ("Jazz", intArrayOf(400, 200, -200, 300, 400)),
    CLASSICAL("Classical", intArrayOf(300, 0, -300, 200, 500)),
    VOCAL("Vocal", intArrayOf(-200, 100, 400, 300, -100)),
    CUSTOM("Custom", intArrayOf(0, 0, 0, 0, 0));

    companion object {
        /** Number of bands the preset gain arrays are designed for. */
        const val PRESET_BAND_COUNT = 5

        /**
         * Safely resolve a preset by name, falling back to [FLAT] for unknown values.
         */
        fun fromName(name: String): EqPreset =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: entries.firstOrNull { it.displayName.equals(name, ignoreCase = true) }
                ?: FLAT
    }
}
