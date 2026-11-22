package com.stash.core.media.tuning

import kotlin.math.abs

/**
 * The rule for a song heard for the first time, measured while it plays (true tuning, spec 2026-11-21, decision 2):
 * when a reading of the song so far is good enough to shift by, when a later one replaces it, and when it is worth
 * keeping for the next play. One per play of a song; fed each reading of [TuningMonitor] on the song's own audio,
 * before the pitch stage.
 *
 * Real songs set the numbers (TuningCorpusReport, "how soon the reading settles"): most read within 1.5 cents of
 * their whole-song tuning after 12 to 20 seconds, but an intro can sit somewhere else, and a few are 8 to 19 cents
 * out until later. So the first confident reading is used, and the song goes on being measured: a reading that
 * has moved is followed, and only one that rests on a minute of audio is kept.
 */
class LiveTuning {

    /**
     * @param applyCents the song's offset to shift by from now on, or null to leave playback as it is.
     * @param store a reading to keep on the song for its next play, or null.
     */
    data class Decision(val applyCents: Float?, val store: Stored?)

    data class Stored(val cents: Float, val confidence: Float)

    /** What playback is shifting by, once a reading was good enough; null before. */
    var appliedCents: Float? = null
        private set
    private var appliedConfidence = 0f
    private var stored = false

    fun on(reading: TuningMonitor.Reading): Decision {
        var apply: Float? = null
        val bar = if (appliedCents == null) FIRST_CONFIDENCE else LATER_CONFIDENCE
        if (reading.confidence >= bar && reading.seconds >= FIRST_SECONDS) {
            val cents = reading.cents.toFloat()
            val held = appliedCents
            if (held == null || apart(cents, held) > MOVE_CENTS) {
                appliedCents = cents
                appliedConfidence = reading.confidence.toFloat()
                apply = cents
            }
        }

        var store: Stored? = null
        val held = appliedCents
        if (held != null && reading.seconds >= STORE_SECONDS && (apply != null || !stored)) {
            store = Stored(held, appliedConfidence)
        } else if (held == null && !stored && reading.seconds >= NO_PITCH_SECONDS && !reading.measured) {
            // Two minutes in and nothing to read: speech, drums. Kept, so the next play doesn't say "measuring".
            store = Stored(reading.cents.toFloat(), reading.confidence.toFloat())
        }
        if (store != null) stored = true
        return Decision(apply, store)
    }

    /** The distance between two offsets round the 100-cent circle. */
    private fun apart(a: Float, b: Float): Float {
        val d = abs(a - b) % 100f
        return if (d > 50f) 100f - d else d
    }

    companion object {
        /** The first reading to shift by must be this sure and rest on this much audio. */
        const val FIRST_CONFIDENCE = 0.7
        const val FIRST_SECONDS = 12.0

        /** A later reading replaces it when it has moved this far, and is at least this sure. */
        const val MOVE_CENTS = 3f
        const val LATER_CONFIDENCE = 0.5

        /** A reading is kept for the next play once it rests on this much of the song. */
        const val STORE_SECONDS = 60.0
        const val NO_PITCH_SECONDS = 120.0
    }
}
