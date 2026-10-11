package com.stash.core.ui.ambient

import com.stash.core.model.PlayerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest

/*
 * When the app's chrome wears the album colour (owner decision, 2026-10-11): the wordmark and the
 * selected tab are tinted only while a song is actually playing; paused or idle they are neutral.
 */

/**
 * How long a fall from playing waits before the chrome turns neutral. Skipping a song stops the
 * old item before the next one starts, and a few hundred ms of "not playing" in between would
 * otherwise flash the wordmark white and back across two 800 ms crossfades.
 */
const val TINT_GRACE_MS = 600L

/**
 * One step of the tint rule. Playing tints; buffering keeps whatever the chrome already was (a
 * seek or a slow stream resolve while playing is not a pause, and buffering a paused song after a
 * seek must not light the chrome up); anything else is neutral.
 */
fun nextTinted(wasTinted: Boolean, isPlaying: Boolean, isBuffering: Boolean): Boolean =
    isPlaying || (isBuffering && wasTinted)

/**
 * Whether the chrome is tinted, from the player's state: [nextTinted] per update, with a fall to
 * neutral held back [graceMs] so a skip's momentary not-playing never reaches the screen. A rise
 * is immediate. Each collection keeps its own state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<PlayerState>.tintedWhilePlaying(graceMs: Long = TINT_GRACE_MS): Flow<Boolean> =
    flow {
        var tinted = false
        collect { s ->
            tinted = nextTinted(tinted, s.isPlaying, s.isBuffering)
            emit(tinted)
        }
    }
        .distinctUntilChanged()
        .transformLatest { tinted ->
            if (!tinted) delay(graceMs)
            emit(tinted)
        }
        .distinctUntilChanged()
