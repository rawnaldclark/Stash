package com.stash.core.model.sync

import kotlin.math.floor

/**
 * The handoff card's timing (sync-v1 "Clocks"; spec §2.5, §8.2). It uses the server's `serverAt` stamps only, never a device's
 * clock, so a phone set an hour ahead can't make its state look newer. Same rules and vectors as the web's `clock.ts`.
 */
object HandoffTiming {
    /** States at least this old are not offered. */
    const val OFFER_MAX_AGE_MS = 12L * 60 * 60 * 1000

    /**
     * Where a published state is now: when it was playing, `positionMs + floor((serverTime − serverAt) × rate)` (elapsed never below
     * zero); past the song's end (when [durationMs] is known) the published position instead: the device was probably killed.
     */
    fun extrapolate(playing: Boolean, positionMs: Long, rate: Double, durationMs: Long?, serverAt: Long, serverTime: Long): Long {
        if (!playing) return positionMs
        val p = positionMs + floor(maxOf(0L, serverTime - serverAt).toDouble() * rate).toLong()
        return if (durationMs != null && p > durationMs) positionMs else p
    }

    data class Candidate(val device: String, val serverAt: Long, val hasSong: Boolean)

    /**
     * The device whose state to offer, or null: another device, with a song, newer than this device's own last playback
     * ([ownLastAt], server time), younger than 12 hours, not dismissed ([dismissed] holds `"<device>@<serverAt>"`), and only while
     * this device isn't playing. The newest `serverAt` wins; a tie goes to the smaller device id.
     */
    fun pickOffer(
        states: List<Candidate>,
        me: String,
        serverTime: Long,
        ownLastAt: Long,
        playingHere: Boolean,
        dismissed: Collection<String>,
    ): String? {
        if (playingHere) return null
        return states
            .filter { s ->
                s.device != me && s.hasSong && s.serverAt > ownLastAt &&
                    serverTime - s.serverAt < OFFER_MAX_AGE_MS && "${s.device}@${s.serverAt}" !in dismissed
            }
            .minWithOrNull(compareByDescending<Candidate> { it.serverAt }.thenBy { it.device })
            ?.device
    }
}
