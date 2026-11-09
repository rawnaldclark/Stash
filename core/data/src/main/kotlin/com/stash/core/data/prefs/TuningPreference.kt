package com.stash.core.data.prefs

import kotlinx.coroutines.flow.Flow

/**
 * Persisted Tuning reference: the A4 (in Hz) everything Stash plays is retuned to, global (not per
 * song) and local to this phone (never sent to a Listen Together room). Lives in `:core:data` so
 * Settings, Now Playing and the playback service share one source of truth; the DataStore
 * implementation is [TuningPreferencesManager], bound via Hilt. Values are kept as
 * [com.stash.core.model.TuningMath.sanitize] leaves them: 392 to 494 Hz, to 0.1 Hz.
 */
interface TuningPreference {

    /** Emits the reference, defaulting to standard (440 Hz). */
    val referenceHz: Flow<Float>

    suspend fun setReferenceHz(hz: Float)
}
