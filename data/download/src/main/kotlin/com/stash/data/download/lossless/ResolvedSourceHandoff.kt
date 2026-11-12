package com.stash.data.download.lossless

import com.stash.core.addons.AddonRegistry
import com.stash.core.addons.InstalledAddon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The match the retry sweep just found for a waiting song, handed to that song's download.
 *
 * The sweep checks a waiting row before re-queuing it, so it doesn't start a download that
 * would only wait again. That check is a relay answer, and a found answer costs a unit of the
 * day's Tidal allowance; the download then asking again cost a second unit for one
 * song. The sweep [put]s what it found here and the download [take]s it instead of asking:
 * one answer, one unit. Kept [TTL_MS] (the download starts at once, and a signed URL outlives
 * that), taken once, and only by the same [Kind] of lookup.
 */
@Singleton
class ResolvedSourceHandoff @Inject constructor(
    /** The add-ons that are on. Built without one (tests of other things), nothing is held back. */
    private val addons: AddonRegistry = NoAddonGate,
) {

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
        if (!addonIsOn(entry.source)) return null
        return entry.source.takeIf { nowMs() - entry.atMs < TTL_MS }
    }

    /**
     * A match an add-on found is handed over only while that add-on is on. Removed, switched off or
     * turned off by Stash since the sweep found it, its link is a fetch from a host the add-on chose,
     * after the user (or Stash) said stop: it is dropped, and the download asks afresh, without that
     * add-on (review-885 M6).
     */
    private fun addonIsOn(source: SourceResult): Boolean {
        if (addons === NoAddonGate) return true
        val id = PlayOrder.addonIdOf(source.sourceId) ?: return true
        return addons.generic().any { it.id == id }
    }

    companion object {
        internal const val TTL_MS = 10 * 60_000L
    }
}

/** No registry given: no add-on's match is held back. */
private object NoAddonGate : AddonRegistry {
    override val addons: StateFlow<List<InstalledAddon>> = MutableStateFlow(emptyList())
}
