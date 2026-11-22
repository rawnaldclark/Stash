package com.stash.core.model.tuning

import kotlin.math.pow

/**
 * What is known about one recording's own tuning (true tuning, spec 2026-11-21), from the three columns the
 * measurement writes.
 */
sealed interface SongTuning {

    /** Never measured: a stream, or a download the background pass hasn't reached. */
    data object NotMeasured : SongTuning

    /**
     * Measured, and there was no tuning to read: no steady notes (speech, drums), two tunings at once, or a file
     * that couldn't be decoded. Measuring it again would say the same.
     */
    data object NoPitch : SongTuning

    /** Measured: the recording's notes sit [cents] from the grid at A = 440 Hz (-50 until +50). */
    data class Measured(val cents: Float) : SongTuning {
        /** The recording's own concert pitch: 427.5 to 452.9 Hz. */
        val hz: Double get() = 440.0 * 2.0.pow(cents / 1200.0)
    }

    /**
     * The offset playback corrects by. A song without a reading is treated as a standard recording, at 440
     * (the owner's decision 1): every song is brought to the chosen pitch, measured or not.
     */
    val appliedCents: Float get() = (this as? Measured)?.cents ?: 0f

    companion object {
        /** From a track's `tuning_cents`, `tuning_confidence` and `tuning_measured_at`. */
        fun of(cents: Float?, confidence: Float?, measuredAt: Long?): SongTuning = when {
            measuredAt == null -> NotMeasured
            cents == null || confidence == null || !cents.isFinite() -> NoPitch
            confidence < TuningEstimator.MIN_CONFIDENCE.toFloat() -> NoPitch
            else -> Measured(cents.coerceIn(-50f, 50f))
        }
    }
}
