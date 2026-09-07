package com.stash.core.data.prefs

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * "Autoplay radio": when the last queued track starts, seed a song radio from
 * it so the music never stops at the end of a one-off play (or an album).
 * Off by default. The DataStore-backed implementation is
 * [AutoplayRadioPreferencesManager], bound via Hilt.
 */
interface AutoplayRadioPreference {
    /** Emits whether autoplay radio is on, defaulting to `false`. */
    val enabled: Flow<Boolean>

    /** Persists the toggle. */
    suspend fun setEnabled(value: Boolean)

    companion object {
        /** Always off; the player's default so tests and previews need no store. */
        val Off: AutoplayRadioPreference = object : AutoplayRadioPreference {
            override val enabled: Flow<Boolean> = flowOf(false)
            override suspend fun setEnabled(value: Boolean) = Unit
        }
    }
}
