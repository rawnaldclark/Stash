package com.stash.core.data.prefs

import com.stash.core.data.radio.RadioEngineId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Which radio engine builds stations: Stash's Last.fm graph, or YouTube Music's own
 * station algorithm.
 *
 * Defaults to [RadioEngineId.YOUTUBE_MUSIC] (owner decision 2026-11-15): the Last.fm
 * similarity graph is tag co-occurrence rather than listening, and it was the reported
 * cause of off-theme stations. A build without the YouTube Music extension, or an
 * install with no account linked, resolves that default to the Stash engine through
 * [com.stash.core.data.radio.RadioEngines.active], so the default is safe either way.
 *
 * The DataStore-backed implementation is [RadioEnginePreferencesManager], bound via Hilt.
 */
interface RadioEnginePreference {
    /** Emits the chosen engine, defaulting to [RadioEngineId.YOUTUBE_MUSIC]. */
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
