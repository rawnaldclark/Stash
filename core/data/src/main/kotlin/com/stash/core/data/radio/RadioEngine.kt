package com.stash.core.data.radio

import com.stash.core.model.Track

/**
 * One live radio station, as the player sees it: a source of playable batches and nothing else.
 *
 * Deliberately opaque. The Last.fm engine's cursor/no-repeat state
 * ([RadioSession]) is `internal` to this module and a YouTube station is just a
 * continuation token, so the player holds a [RadioStation], never a session —
 * that is what lets a second engine exist without :core:media knowing which one
 * is running.
 */
interface RadioStation {
    /** The next playable batch; empty once the station is over. */
    suspend fun nextBatch(): List<Track>
}

/**
 * Which engine builds stations. [key] is what the preference stores, so it must
 * never be renamed: an unknown stored key falls back to the default engine.
 */
enum class RadioEngineId(val key: String) {
    /** Stash's own generator: a Last.fm similarity graph turned into YouTube tracks. */
    STASH("stash"),

    /** YouTube Music's own station algorithm (`/next` with an `RD…` playlist id). */
    YOUTUBE_MUSIC("youtube_music");

    companion object {
        fun fromKey(key: String?): RadioEngineId? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A radio engine: turns a [RadioSeed] into a station plus its first batch.
 *
 * Implementations are collected by Hilt set multibinding and chosen by
 * [RadioEngines], so a build without the YouTube Music extension still has
 * exactly one engine and behaves as it always did.
 */
interface RadioEngine {
    val id: RadioEngineId

    /**
     * Whether this engine can serve a station *right now*. The YouTube Music
     * engine answers false without a linked account or a usable cookie, which is
     * what lets it be the default while a fresh install still plays Last.fm
     * stations instead of failing. The Stash engine is always available.
     */
    suspend fun available(): Boolean = true

    /** The station, and a first batch already resolved — the player queues that batch before it grows. */
    suspend fun start(seed: RadioSeed): Pair<RadioStation, List<Track>>
}
