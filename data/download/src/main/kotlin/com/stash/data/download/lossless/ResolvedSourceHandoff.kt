package com.stash.data.download.lossless

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The match the retry sweep just found for a waiting song, handed to that song's download.
 *
 * The sweep checks a waiting row before re-queuing it, so it doesn't start a download that
 * would only wait again. That check is a relay answer, and a found answer costs a unit of the
 * day's Tidal & Qobuz allowance; the download then asking again cost a second unit for one
 * song. The sweep [put]s what it found here and the download [take]s it instead of asking:
 * one answer, one unit. Kept [TTL_MS] (the download starts at once, and a signed URL outlives
 * that), taken once, and only by the same [Kind] of lookup.
 */
@Singleton
class ResolvedSourceHandoff @Inject constructor() {

    /** Which lookup found it: a lossless one never stands in for AAC, or the other way. */
    enum class Kind { LOSSLESS, TIDAL_AAC }

    private data class Entry(val source: SourceResult, val kind: Kind, val atMs: Long)

    /** Wall clock; tests drive it. */
    internal var nowMs: () -> Long = System::currentTimeMillis

    private val entries = ConcurrentHashMap<Long, Entry>()

    fun put(trackId: Long, source: SourceResult, kind: Kind) {
        entries[trackId] = Entry(source, kind, nowMs())
    }

    /** The match found for [trackId] by a [kind] lookup in the last [TTL_MS], once; else null. */
    fun take(trackId: Long, kind: Kind): SourceResult? {
        val entry = entries[trackId] ?: return null
        if (entry.kind != kind) return null
        entries.remove(trackId, entry)
        return entry.source.takeIf { nowMs() - entry.atMs < TTL_MS }
    }

    companion object {
        internal const val TTL_MS = 10 * 60_000L
    }
}
