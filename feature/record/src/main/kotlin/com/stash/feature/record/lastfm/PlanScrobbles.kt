package com.stash.feature.record.lastfm

import com.stash.core.model.weblink.SongKey
import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.archive.GUESS_MS
import com.stash.feature.record.archive.RecordFlags
import com.stash.feature.record.art.ArtText

/** A kept play's span: its start and its end (start plus time played), epoch ms. */
data class PlaySpan(val s: Long, val e: Long)

/**
 * What planning scrobbles asks of the archive (plays.ts `planScrobbles` reads its `have` for exactly this): which ids it keeps
 * already, and where the plays that aren't scrobbles of one title lie. The import answers from the database a page at a time
 * ([DaoScrobbleStore]), never holding a heavy archive in memory; the tests answer from a list.
 */
interface ScrobbleArchive {
    /** Of [ids], the ones kept already. */
    suspend fun kept(ids: List<String>): Set<String>

    /** The plays that aren't scrobbles whose title folds to [titleFold] (plays.ts `overlapKey`). */
    suspend fun playsOf(titleFold: String): List<PlaySpan>
}

/** What importing a page of scrobbles adds (plays.ts `ScrobblePlan`): the rows, those kept already, and those a play covers. */
data class ScrobblePlan(val add: List<ArchivePlay>, val same: Int, val covered: Int)

/**
 * The web player's rules for Last.fm scrobbles in Your record (stash-player `src/lib/spotify/plays.ts`, spec
 * `docs/spotify-export.md` §7), ported exactly so the phone keeps the rows the web keeps, with the same ids:
 *
 * - a scrobble's row is id `textKey(title, artist)|uts·1000`, flags [RecordFlags.LASTFM]; it has no reasons, so it counts as a
 *   full listen, never as chosen or skipped;
 * - its time played is the song's length, the first that knows it ([bestLengths]: the longest Spotify play of the same song key
 *   that ran to its end, [knownLengths]; else the longest the library holds, [libraryLengths]), never under [LISTEN_MS] (a
 *   scrobble is a listen, [scrobbleMs]); else [GUESS_MS] marked [RecordFlags.ESTIMATED], until the lengths pass times it
 *   ([Lengths]);
 * - one listen counts once: a scrobble whose start falls within [SLACK_MS] before a play's start or after its end, the titles
 *   folded alike (the artist ignored), is that play ([plan] against the archive's Spotify plays; [coveredBy] and
 *   [com.stash.feature.record.archive.StashCover] against this phone's own, which aren't in the archive).
 */
object Scrobbles {
    /** plays.ts `SCROBBLE_SLACK_MS`: scrobblers stamp a play's start loosely. */
    const val SLACK_MS = 180_000L

    /** plays.ts `LISTEN_MS`: a listen, not a skip. A scrobble's row never plays less. */
    const val LISTEN_MS = 30_000L

    /** plays.ts `MAX_SONG_MS`: the longest a song's length can be and still be believed. */
    const val MAX_SONG_MS = 6 * 3_600_000L

    /** plays.ts `scrobbleMs`: a scrobble's `ms` from its song's length, rounded, never under [LISTEN_MS] (a shorter row reads as a skip). */
    fun scrobbleMs(length: Double): Long = maxOf(LISTEN_MS, jsRound(length))

    /** [scrobbleMs] of a whole length. */
    fun scrobbleMs(length: Long): Long = maxOf(LISTEN_MS, length)

    /**
     * plays.ts `libraryLengths` (spec §7, step 2): from the library's songs (title, artist, length in ms as JavaScript reads a
     * number), the longest by song key. A length under a second or over [MAX_SONG_MS] is broken, and left out. The phone's library
     * is read grouped already ([com.stash.core.data.db.dao.WebLibraryExportDao.libraryLengths]).
     */
    fun libraryLengths(items: Iterable<Triple<String, String, Double?>>): Map<String, Long> {
        val out = HashMap<String, Long>()
        for ((t, a, ms) in items) {
            if (ms == null || !ms.isFinite() || ms < 1000 || ms > MAX_SONG_MS) continue
            val k = songKey(t, a)
            val len = jsRound(ms)
            if (len > (out[k] ?: 0L)) out[k] = len
        }
        return out
    }

    /** JavaScript's `Math.round`: half up, towards +∞ (Java's `Math.round` rounds the same way; Kotlin's `round` rounds half to even). */
    private fun jsRound(x: Double): Long = Math.round(x)

    /** plays.ts `bestLengths`: the archive's lengths (step 1), with the library's (step 2) where the archive knows none. */
    fun bestLengths(archive: Map<String, Long>, library: Map<String, Long>?): Map<String, Long> {
        val out = HashMap(library.orEmpty())
        out.putAll(archive)
        return out
    }

    /** song-key.ts `songKey`: one song across its remasters ("Song - 2011 Remaster" is "Song"). */
    fun songKey(title: String, artist: String): String = SongKey.textKey(ArtText.keyTitle(title), artist)

    /**
     * plays.ts `knownLengths`: from (title, artist, longest time) of the plays that ran to their end and aren't scrobbles (the
     * database groups them by the words as written: [com.stash.core.data.db.dao.RecordPlayDao.doneLengths]), the longest by
     * song key.
     */
    fun knownLengths(rows: Iterable<Triple<String, String, Long>>): Map<String, Long> {
        val out = HashMap<String, Long>()
        for ((t, a, ms) in rows) {
            val k = songKey(t, a)
            if (ms > (out[k] ?: 0L)) out[k] = ms
        }
        return out
    }

    /** plays.ts `knownLengths` over archive rows as they are (the tests', and the web's own signature). */
    fun knownLengthsOf(rows: Iterable<ArchivePlay>): Map<String, Long> = knownLengths(
        rows.filter { it.f and RecordFlags.DONE != 0 && it.f and RecordFlags.LASTFM == 0 }.map { Triple(it.t, it.a, it.ms) },
    )

    /** plays.ts `planScrobbles`'s order: by start, then title, then artist (plain UTF-16 order, never a Collator). Stable. */
    private val ORDER: Comparator<Scrobble> = compareBy<Scrobble> { it.uts }
        .thenComparator { x, y -> x.t.compareTo(y.t) }
        .thenComparator { x, y -> x.a.compareTo(y.a) }

    /**
     * plays.ts `planScrobbles`: what importing [scrobbles] into [archive] adds. A scrobble is left out when its id is kept
     * already (or came earlier in this page) or a play that isn't a scrobble covers it. [lengths]: [bestLengths]. Coverage reads
     * a scrobble's start only, never its `ms`, so the lengths pass rewriting `ms` later changes nothing here.
     */
    suspend fun plan(archive: ScrobbleArchive, scrobbles: List<Scrobble>, lengths: Map<String, Long>): ScrobblePlan {
        if (scrobbles.isEmpty()) return ScrobblePlan(emptyList(), 0, 0)
        class Row(val sc: Scrobble, val titleFold: String, val id: String)
        val rows = scrobbles.sortedWith(ORDER).map { sc ->
            val ft = SongKey.fold(sc.t)
            Row(sc, ft, id(sc, ft))
        }
        val ids = HashSet(archive.kept(rows.map { it.id }.distinct()))
        val plays = HashMap<String, List<PlaySpan>>()
        val add = ArrayList<ArchivePlay>()
        var same = 0
        var covered = 0
        for (r in rows) {
            val s = r.sc.uts * 1000
            if (r.id in ids) {
                same++
                continue
            }
            val spans = plays.getOrPut(r.titleFold) { archive.playsOf(r.titleFold) }
            if (spans.any { s >= it.s - SLACK_MS && s <= it.e + SLACK_MS }) {
                covered++
                continue
            }
            val known = lengths[songKey(r.sc.t, r.sc.a)]?.takeIf { it != 0L }
            add += ArchivePlay(
                id = r.id,
                t = r.sc.t,
                a = r.sc.a,
                al = r.sc.al?.takeIf { it.isNotEmpty() },
                s = s,
                ms = if (known != null) scrobbleMs(known) else GUESS_MS,
                f = RecordFlags.LASTFM or (if (known != null) 0 else RecordFlags.ESTIMATED),
            )
            ids += r.id
        }
        return ScrobblePlan(add, same, covered)
    }

    /** plays.ts `playId` of a scrobble: `textKey(title, artist)|uts·1000`. [titleFold]: its title folded, when known already. */
    fun id(sc: Scrobble, titleFold: String = SongKey.fold(sc.t)): String = "$titleFold|${SongKey.fold(sc.a)}|${sc.uts * 1000}"

    /**
     * plays.ts `coveredBy`: of [rows], the scrobbles one of [plays] covers (this phone's own, which Stash scrobbled): the same
     * folded title, starting within [SLACK_MS] before the play's start or after its end. Their ids.
     */
    fun coveredBy(rows: Iterable<ArchivePlay>, plays: Iterable<Triple<String, Long, Long>>): Set<String> {
        val byTitle = HashMap<String, MutableList<PlaySpan>>()
        for ((t, s, ms) in plays) byTitle.getOrPut(SongKey.fold(t)) { ArrayList() } += PlaySpan(s, s + ms)
        val out = LinkedHashSet<String>()
        for (r in rows) {
            if (r.f and RecordFlags.LASTFM == 0) continue
            if (byTitle[SongKey.fold(r.t)]?.any { r.s >= it.s - SLACK_MS && r.s <= it.e + SLACK_MS } == true) out += r.id
        }
        return out
    }
}

/** [ScrobbleArchive] over rows in memory (the tests, and the web's own `have`). */
class MemoryScrobbleArchive(rows: Iterable<ArchivePlay>) : ScrobbleArchive {
    private val ids = HashSet<String>()
    private val plays = HashMap<String, MutableList<PlaySpan>>()

    init {
        rows.forEach(::add)
    }

    /** [r] is kept now (a page's rows once written). */
    fun add(r: ArchivePlay) {
        ids += r.id
        if (r.f and RecordFlags.LASTFM == 0) plays.getOrPut(SongKey.fold(r.t)) { ArrayList() } += PlaySpan(r.s, r.s + r.ms)
    }

    override suspend fun kept(ids: List<String>): Set<String> = ids.filterTo(HashSet()) { it in this.ids }

    override suspend fun playsOf(titleFold: String): List<PlaySpan> = plays[titleFold].orEmpty()
}
