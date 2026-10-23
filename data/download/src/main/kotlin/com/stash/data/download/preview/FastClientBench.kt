package com.stash.data.download.preview

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Sidelines a pinned fast yt-dlp client ([PreviewUrlExtractor.FAST_PLAYER_CLIENTS]) that YouTube
 * has gated, for downloads and streaming alike.
 *
 * Each rotation of YouTube's gates (android_vr's PO token 2026-08-21, web_embedded's SABR
 * 2026-10-23) turned a fast client into one that fails on every song, and every download and
 * stream kept paying for the doomed run, about half a minute under sync load, until someone read
 * the logs. Now [FAILS_TO_BENCH] strikes in a row bench a client for [BENCH_MS]; then it gets
 * tried again, so a client YouTube un-gates comes back by itself. A strike is a fast attempt
 * that failed on a song the default client set then served (callers count it that way), so a
 * song nothing can serve never benches anything. Process-wide, in memory: a restart starts clean.
 */
internal object FastClientBench {
    internal const val FAILS_TO_BENCH = 3
    internal const val BENCH_MS = 15 * 60_000L

    private val strikes = ConcurrentHashMap<String, Int>()
    private val benchedUntil = ConcurrentHashMap<String, Long>()

    /** Test seam. */
    internal var clock: () -> Long = System::currentTimeMillis

    fun usable(client: String): Boolean = (benchedUntil[client] ?: 0L) <= clock()

    fun succeeded(client: String) {
        strikes.remove(client)
        benchedUntil.remove(client)
    }

    fun failed(client: String) {
        val n = strikes.merge(client, 1, Int::plus) ?: 1
        if (n >= FAILS_TO_BENCH) {
            strikes.remove(client)
            benchedUntil[client] = clock() + BENCH_MS
            runCatching { Log.w("FastClientBench", "yt-dlp client $client failed $n songs in a row; skipping it for ${BENCH_MS / 60_000} min") }
        }
    }

    /** Test-only. */
    internal fun reset() {
        strikes.clear()
        benchedUntil.clear()
        clock = System::currentTimeMillis
    }
}
