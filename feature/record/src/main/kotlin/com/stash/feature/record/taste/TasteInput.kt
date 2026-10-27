package com.stash.feature.record.taste

import com.stash.core.model.weblink.SongKey
import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.archive.RecordFlags
import com.stash.feature.record.archive.StashCover
import com.stash.feature.record.lastfm.Scrobbles

/** One of this phone's own plays as the taste reads it (service.ts `live`): its words, when it started, the song's length. */
class OwnPlay(val t: String, val a: String, val al: String?, val s: Long, val d: Long?)

/**
 * The plays the taste is worked out from (stash-player `src/lib/spotify/importer.ts` `refreshTaste`), built a page at a time:
 *
 * - every archive row (the Spotify export's plays and the Last.fm scrobbles, `record_plays`);
 * - minus the scrobbles this phone's own plays cover (plays.ts `coveredBy`: the same folded title, starting no more than 180 s
 *   before a play's start or after its end; the plays' spans here are the taste's own, `max(30 s, length)`, not the record's);
 * - plus this phone's own plays as `f = 0`, `ms = max(30 s, the song's length)` (service.ts `live`: always a listen, never a
 *   skip), each unless the archive holds a row with its id (`playId`) already, covered or not.
 *
 * Strings are kept once each ([intern]): half a million plays share a few tens of thousands of spellings.
 */
class TasteInput(own: List<OwnPlay>) {
    private val strings = HashMap<String, String>()

    private fun intern(s: String): String = strings.getOrPut(s) { s }

    private fun internOrNull(s: String?): String? = s?.let(::intern)

    private val live: List<TastePlay> = own.map {
        TastePlay(intern(it.t), intern(it.a), it.s, maxOf(Taste.LISTEN_MS, it.d ?: 0L), 0, internOrNull(it.al?.takeIf { a -> a.isNotEmpty() }))
    }

    /** Each own play's id (`textKey(title, artist)|start`), in [live]'s order. */
    private val liveIds: Array<String>

    /** The ids the archive holds that an own play has too: those own plays are the archive's, counted once. */
    private val held = HashSet<String>()

    /** The own plays by folded title: start and end, two longs a play. */
    private val spans = HashMap<String, LongList>()

    private val rows = ArrayList<TastePlay>()

    init {
        val keys = HashMap<String, String>()
        liveIds = Array(live.size) { i ->
            val p = live[i]
            "${keys.getOrPut(p.t + '\u0000' + p.a) { SongKey.textKey(p.t, p.a) }}|${p.s}"
        }
        val folds = HashMap<String, String>()
        for (p in live) spans.getOrPut(folds.getOrPut(p.t) { SongKey.fold(p.t) }) { LongList() }.add(p.s, p.s + p.ms)
    }

    private val liveIdSet: HashSet<String> = liveIds.toHashSet()

    /** One page of archive rows, in any order. */
    fun add(page: List<ArchivePlay>) {
        for (r in page) {
            if (liveIdSet.isNotEmpty() && r.id in liveIdSet) held += r.id
            if (r.f and RecordFlags.LASTFM != 0 && covered(r)) continue
            rows += TastePlay(intern(r.t), intern(r.a), r.s, r.ms, r.f, internOrNull(r.al), internOrNull(r.sid))
        }
    }

    private fun covered(r: ArchivePlay): Boolean {
        val list = spans[StashCover.titleOf(r.id)] ?: return false
        var i = 0
        while (i < list.size) {
            if (r.s >= list[i] - Scrobbles.SLACK_MS && r.s <= list[i + 1] + Scrobbles.SLACK_MS) return true
            i += 2
        }
        return false
    }

    /** Every play for the taste: the archive's rows kept, then the own plays the archive doesn't hold. */
    fun plays(): List<TastePlay> {
        val out = ArrayList<TastePlay>(rows.size + live.size)
        out += rows
        for (i in live.indices) if (liveIds[i] !in held) out += live[i]
        return out
    }

    /** A growable list of longs. */
    private class LongList {
        private var a = LongArray(4)
        var size = 0
            private set

        fun add(s: Long, e: Long) {
            if (size + 2 > a.size) a = a.copyOf(a.size * 2)
            a[size++] = s
            a[size++] = e
        }

        operator fun get(i: Int): Long = a[i]
    }
}
