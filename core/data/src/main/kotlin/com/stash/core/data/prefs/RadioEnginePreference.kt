package com.stash.core.data.prefs

import com.stash.core.data.radio.RadioEngineId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Which radio engine builds stations: Stash's Last.fm graph, or a second one supplied
 * outside this module (the YouTube Music extension's).
 *
 * Defaults to [RadioEngineId.STASH]: the built-in engine is the app's own promise, so the
 * second one ships opt-in rather than replacing the described behaviour for everyone who
 * never opens Settings. Choosing it is one row in Settings ▸ Playback.
 *
 * Resolving the choice is [com.stash.core.data.radio.RadioEngines.active]'s job, and it
 * falls back to the Stash engine when the chosen one isn't in the build or can't serve —
 * so a stored value for an engine this build doesn't have is harmless.
 *
 * The DataStore-backed implementation is [RadioEnginePreferencesManager], bound via Hilt.
 */
interface RadioEnginePreference {
    /** Emits the chosen engine, defaulting to [RadioEngineId.STASH]. */
    val engine: Flow<RadioEngineId>

    /** The chosen engine, read once. For callers that already suspend and can't collect. */
    suspend fun current(): RadioEngineId

    /** Persists the choice. */
    suspend fun setEngine(id: RadioEngineId)

    companion object {
        /** Always the Stash engine; the player's default so tests and previews need no store. */
        val Stash: RadioEnginePreference = object : RadioEnginePreference {
            override val engine: Flow<RadioEngineId> = flowOf(RadioEngineId.STASH)
            override suspend fun current(): RadioEngineId = RadioEngineId.STASH
            override suspend fun setEngine(id: RadioEngineId) = Unit
        }
    }
}
