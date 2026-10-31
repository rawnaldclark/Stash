package com.stash.feature.record.card

import android.os.SystemClock
import android.util.Log
import com.stash.core.data.db.dao.RecordPlayDao
import com.stash.core.data.db.dao.WebLibraryExportDao
import com.stash.core.data.db.dao.WebLibraryExportDao.OwnAlbumTotal
import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.archive.KindArchive
import com.stash.feature.record.archive.OwnArchive
import com.stash.feature.record.archive.RecordArchiveSource
import com.stash.feature.record.archive.RecordSources
import com.stash.feature.record.archive.RecordSourcesStore
import com.stash.feature.record.archive.RecordSummary
import com.stash.feature.record.archive.RecordSummaryBuilder
import com.stash.feature.record.archive.ScrobbleIndex
import com.stash.feature.record.archive.SourceCounts
import com.stash.feature.record.archive.SourcesOn
import com.stash.feature.record.archive.StashCover
import com.stash.feature.record.imports.RecordWrites
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What Home's card is made from: the record's own numbers and the albums played most (for the sleeve), for the sources switched
 * on, and what each source brings (the record's switches, before any is switched off).
 */
data class CardData(val summary: RecordSummary, val albums: List<OwnAlbumTotal>, val counts: SourceCounts = SourceCounts(0, 0, 0))

/**
 * Each source's part of the card, worked out when the history changes (never when a switch flips): the Spotify export's plays,
 * this phone's own, and the Last.fm scrobbles they don't cover. [dataFor] puts together the ones switched on, as the record
 * does with the same choice (record.ts `shownSources`: a choice with no source left on shows them all).
 */
class CardParts internal constructor(
    internal val spotify: RecordSummaryBuilder.Partial,
    internal val spotifyAlbums: List<OwnAlbumTotal>,
    internal val own: RecordSummaryBuilder.Partial,
    internal val ownAlbums: List<OwnAlbumTotal>,
    internal val lastfm: RecordSummaryBuilder.Partial,
    internal val lastfmAlbums: List<OwnAlbumTotal>,
) {
    /** What each source brings: its streams (30 s or more), the page's `stash:counts`. */
    val counts = SourceCounts(spotify.streams, own.streams, lastfm.streams)

    fun dataFor(choice: SourcesOn): CardData {
        val on = RecordSources.shown(choice, counts)
        val parts = listOfNotNull(spotify.takeIf { on.spotify }, own.takeIf { on.stash }, lastfm.takeIf { on.lastfm })
        // This phone's own streams decide whether there is a record without an import, whatever the switches say
        val summary = RecordSummaryBuilder(parts).build().copy(ownStreams = own.ownStreams)
        // The album played last first, whichever source it's in (an album's spelling is the one seen first)
        val albums = buildList {
            if (on.stash) addAll(ownAlbums)
            if (on.spotify) addAll(spotifyAlbums)
            if (on.lastfm) addAll(lastfmAlbums)
        }.sortedByDescending { it.lastAt }
        return CardData(summary, albums, counts)
    }
}

/**
 * The card's numbers, worked out the way the record works out its own (the same rows: the Spotify export, this phone's own
 * plays, and the Last.fm scrobbles they don't cover, read by the page's rules, [RecordSummaryBuilder]), for the sources the
 * record's switches leave on, so the card and the record never disagree.
 *
 * The imported plays change only with an import or a removal, and a heavy history is hundreds of thousands of them: the
 * Spotify part of the numbers and of the sleeve's albums is worked out once per [importedStamps] and kept (a few KB: totals and
 * an entry per artist), and so is the scrobbles' part, with a [ScrobbleIndex] of them. A song played here re-reads this phone's
 * own plays alone; the scrobbles are read again only when the plays here now cover different ones (the index tells).
 *
 * Read only when what they are made of changed, never because Home came back:
 * - the plays, skips or blocklist ([WebLibraryExportDao.ownHistoryStamp]), or the imported plays ([RecordPlayDao.stamp]: an
 *   import or a removal): read once a burst settles ([SETTLE_MS]);
 * - the songs themselves ([WebLibraryExportDao.tracksWritten]: a rename, an album moved). A sync writes them many times a
 *   second, so these are read once the writes settle ([TRACKS_SETTLE_MS]) and at most once every [TRACKS_GAP_MS].
 * The result is kept with its stamp: a new Home after a while (the sharing stopped) reads nothing for the same history. Off the
 * main thread, one change at a time; one app-wide copy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class RecordCardSource internal constructor(
    /** Changes when this phone's own history does (compared by value). */
    private val stamps: Flow<Any>,
    private val tracks: Flow<Long>,
    /** This phone's own plays. */
    private val archive: RecordArchiveSource,
    private val albums: suspend () -> List<OwnAlbumTotal>,
    scope: CoroutineScope,
    /** Changes when the imported plays do (an import, a removal, scrobbles brought in). */
    private val importedStamps: Flow<Any> = flowOf(Unit),
    /** The imported plays that aren't scrobbles (the Spotify export's). */
    private val imported: RecordArchiveSource = RecordArchiveSource { _, emit -> emit(emptyList(), true) },
    /** Their albums. */
    private val importedAlbums: suspend () -> List<OwnAlbumTotal> = { emptyList() },
    /** The Last.fm scrobbles. */
    private val scrobbles: RecordArchiveSource = RecordArchiveSource { _, emit -> emit(emptyList(), true) },
    /** The sources the record's switches leave on (kept on this phone). */
    private val sources: StateFlow<SourcesOn> = MutableStateFlow(SourcesOn.ALL_ON),
    /**
     * A Last.fm run is writing (a page every second or so, for minutes), or the lengths pass is (a song's rows at a time, for as
     * long as it takes): the imported part is held as it was and read once the writing is over, never again after every page or
     * song (the web's card, too, changes when its run ends, and as its lengths pass shows what it timed)...
     */
    private val importsPaused: Flow<Boolean> = flowOf(false),
    /** ...except each time the lengths pass shows what it timed (this moves on): then it is read again. */
    private val importsShown: Flow<Int> = flowOf(0),
    /** Where the history is read and summed: off the main thread, one thing at a time. */
    work: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    @Inject
    constructor(dao: WebLibraryExportDao, imported: RecordPlayDao, sources: RecordSourcesStore, writes: RecordWrites) : this(
        stamps = dao.ownHistoryStamp(),
        tracks = dao.tracksWritten(),
        archive = OwnArchive(dao),
        albums = dao::ownAlbumTotals,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        importedStamps = imported.stamp(),
        imported = KindArchive(imported, scrobbles = false),
        importedAlbums = imported::albumTotalsWithoutScrobbles,
        scrobbles = KindArchive(imported, scrobbles = true),
        sources = sources.on,
        importsPaused = combine(writes.lastfmRunning, writes.lengthsRunning) { lastfm, lengths -> lastfm || lengths },
        importsShown = writes.lengthsShown,
    )

    @Volatile private var kept: Pair<Any, CardParts>? = null

    /** The Spotify export's part, by the stamp it was read at. */
    private class ImportedPart(val stamp: Any, val summary: RecordSummaryBuilder.Partial, val albums: List<OwnAlbumTotal>)

    @Volatile private var importedPart: ImportedPart? = null

    /** The scrobbles' part, by the stamp it was read at and what the plays here covered then ([ScrobbleIndex.covering]). */
    private class ScrobblePart(
        val stamp: Any,
        val index: ScrobbleIndex,
        val covered: ScrobbleIndex.Covering,
        val summary: RecordSummaryBuilder.Partial,
        val albums: List<OwnAlbumTotal>,
    )

    @Volatile private var scrobblePart: ScrobblePart? = null

    /** What each source brought when the card last read the history; null before it has. */
    val counts: SourceCounts? get() = kept?.second?.counts

    private sealed interface Change {
        class History(val stamp: Any) : Change
        data object Tracks : Change
    }

    /** The latest parts; the first as soon as they are read (or kept), each later one as above. */
    private val parts: Flow<CardParts> = channelFlow {
        var latest: Any? = null
        // The songs changed since the last read: the next reads afresh even for a history it has kept
        var tracksDirty = false
        var lastTracksRead = Long.MIN_VALUE / 2
        // Waiting to ask for a read (cancelled and set again as writes come); the read itself is never cancelled by a write
        var timer: Job? = null
        var timerForHistory = false
        val reads = Channel<Unit>(Channel.CONFLATED)

        launch {
            for (go in reads) {
                val stamp = latest ?: continue
                val fresh = tracksDirty
                tracksDirty = false
                if (fresh) lastTracksRead = now()
                val data = kept?.takeIf { !fresh && it.first == stamp }?.second ?: read(stamp)?.also { kept = stamp to it }
                if (data != null) send(data)
            }
        }

        fun readIn(waitMs: Long, history: Boolean) {
            timer?.cancel()
            timerForHistory = history
            timer = launch {
                delay(waitMs)
                reads.send(Unit)
            }
        }

        val history = combine(stamps, heldWhile(importedStamps, importsPaused, importsShown)) { own, plays -> own to plays }.distinctUntilChanged()
        merge(history.map { Change.History(it) }, tracks.drop(1).map { Change.Tracks }).collect { change ->
            when (change) {
                is Change.History -> {
                    val first = latest == null
                    latest = change.stamp
                    if (first) reads.send(Unit) else readIn(SETTLE_MS, history = true)
                }
                Change.Tracks -> {
                    tracksDirty = true
                    // A read for the history is coming soon anyway (and reads the songs too); else one once the writes settle
                    val historyComing = timerForHistory && timer?.isActive == true
                    if (latest != null && !historyComing) {
                        readIn(maxOf(TRACKS_SETTLE_MS, lastTracksRead + TRACKS_GAP_MS - now()), history = false)
                    }
                }
            }
        }
    }
        .flowOn(work)

    /** The latest card data for the sources switched on; a switch flipped puts the kept parts together again, reading nothing. */
    val data: Flow<CardData> = combine(parts, sources) { parts, on -> parts.dataFor(on) }
        .distinctUntilChanged()
        .shareIn(scope, SharingStarted.WhileSubscribed(STOP_MS), replay = 1)

    private suspend fun read(stamp: Any): CardParts? = try {
        val importedStamp = (stamp as? Pair<*, *>)?.second ?: Unit
        // The Spotify export's part, read again only when the imported plays changed
        val began = SystemClock.elapsedRealtime()
        val part = importedPart?.takeIf { it.stamp == importedStamp } ?: run {
            // record_plays is keyed by id: no set of ids to hold while it's read
            val b = RecordSummaryBuilder(uniqueIds = true)
            imported.stream(CHUNK) { rows, _ -> rows.forEach(b::add) }
            ImportedPart(importedStamp, b.partial(), importedAlbums()).also {
                importedPart = it
                Log.i(TAG, "the card read the imported plays (${it.summary.plays}) in ${SystemClock.elapsedRealtime() - began} ms")
            }
        }
        // This phone's own plays, and the scrobbles they cover
        val own = RecordSummaryBuilder()
        val cover = StashCover.Builder()
        archive.stream(CHUNK) { rows, _ ->
            for (r in rows) {
                own.add(r)
                cover.add(r)
            }
        }
        val covers = cover.build()
        val lastfm = scrobblesPart(importedStamp, covers)
        Log.i(TAG, "the card read the history in ${SystemClock.elapsedRealtime() - began} ms")
        CardParts(part.summary, part.albums, own.partial(), albums(), lastfm.summary, lastfm.albums)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The card keeps what it had; the next change reads again
        Log.w(TAG, "the card's plays couldn't be read: ${e.javaClass.simpleName}")
        null
    }

    /** A held stamp the lengths pass asked to show ([importsShown]): another value, so the plays are read again whatever the stamp. */
    private data class Shown(val n: Int, val stamp: Any)

    /** What the hold hears: the stamp, or the pass's word (and whether it came while holding). */
    private sealed interface Held {
        data class Stamp(val s: Any) : Held

        data class Word(val n: Int, val holding: Boolean) : Held
    }

    /**
     * [stamps] as they come, except while [paused]: then the one before stays (the first is taken whatever, so Home has a card),
     * until [shown] moves on, which reads the plays as they are then. While held, [stamps] isn't read at all (it sums the table on
     * every write, a page or a slice at a time): once the writing is over it is read again.
     */
    private fun heldWhile(stamps: Flow<Any>, paused: Flow<Boolean>, shown: Flow<Int>): Flow<Any> = flow {
        var held: Any? = null
        var seen: Int? = null
        paused.distinctUntilChanged().flatMapLatest { p ->
            val live = when {
                !p -> stamps
                held == null -> stamps.take(1)
                else -> emptyFlow()
            }
            merge(live.map { Held.Stamp(it) }, shown.map { Held.Word(it, p) })
        }.collect { e ->
            when (e) {
                is Held.Stamp -> {
                    held = e.s
                    emit(e.s)
                }
                is Held.Word -> {
                    val moved = seen != null && e.n != seen
                    seen = e.n
                    val h = held
                    if (moved && e.holding && h != null) {
                        val s = Shown(e.n, h)
                        held = s
                        emit(s)
                    }
                }
            }
        }
    }.distinctUntilChanged()

    /**
     * The scrobbles' part: kept while the imported plays and what this phone's plays cover are as they were; else read again,
     * leaving out the ones they cover (by their words), with an index of them the first time after an import.
     */
    private suspend fun scrobblesPart(stamp: Any, covers: StashCover): ScrobblePart {
        val had = scrobblePart?.takeIf { it.stamp == stamp }
        if (had != null && had.index.covering(covers) == had.covered) return had
        val began = SystemClock.elapsedRealtime()
        val index = if (had == null) ScrobbleIndex.Builder() else null
        val b = RecordSummaryBuilder(uniqueIds = true)
        val albums = ScrobbleAlbums()
        scrobbles.stream(CHUNK) { rows, _ ->
            for (r in rows) {
                index?.add(r)
                if (covers.covers(r)) continue
                b.add(r)
                albums.add(r)
            }
        }
        val idx = index?.build() ?: had!!.index
        return ScrobblePart(stamp, idx, idx.covering(covers), b.partial(), albums.totals()).also {
            scrobblePart = it
            Log.i(TAG, "the card read the scrobbles (${idx.size}, ${idx.size - it.summary.plays} covered here) in ${SystemClock.elapsedRealtime() - began} ms")
        }
    }

    companion object {
        private const val TAG = "YourRecord"

        /** A burst of history changes is read once, this long after the last. */
        const val SETTLE_MS = 2_000L

        /** Songs written (a sync): read once they have been quiet this long... */
        const val TRACKS_SETTLE_MS = 5_000L

        /** ...and at most once in this long. */
        const val TRACKS_GAP_MS = 60_000L

        /** Kept listening this long after Home goes (a tab switch and back reads nothing). */
        const val STOP_MS = 60_000L

        private const val CHUNK = 5_000
    }
}

/**
 * The scrobbles' listens totalled by artist and album as written, as [RecordPlayDao.albumTotals] totals the export's (30 s or
 * more, an album named; its song the greatest title in the database's order, which is code point order), the album played last
 * first. Worked out here because the ones a play made here covers are left out, which the database can't tell.
 */
internal class ScrobbleAlbums {
    private class Total(val artist: String, val album: String, var title: String, var plays: Int, var ms: Long, var first: Long, var last: Long)

    private val by = HashMap<String, Total>()

    fun add(r: ArchivePlay) {
        val al = r.al
        if (al.isNullOrEmpty() || r.ms < RecordPlayDao.LISTEN_MS) return
        val t = by.getOrPut("${r.a}\u0000$al") { Total(r.a, al, r.t, 0, 0, r.s, r.s) }
        t.plays++
        t.ms += r.ms
        if (r.s < t.first) t.first = r.s
        if (r.s > t.last) t.last = r.s
        if (codePoints(r.t, t.title) > 0) t.title = r.t
    }

    fun totals(): List<OwnAlbumTotal> = by.values
        .sortedWith(compareByDescending<Total> { it.last }.thenComparator { x, y -> codePoints(x.artist, y.artist) }.thenComparator { x, y -> codePoints(x.album, y.album) })
        .map { OwnAlbumTotal(it.artist, it.album, it.title, null, it.plays, it.ms, it.first, it.last) }

    /** SQLite's BINARY order on UTF-8 text: code point order. */
    private fun codePoints(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a.codePointAt(i)
            val y = b.codePointAt(j)
            if (x != y) return x.compareTo(y)
            i += Character.charCount(x)
            j += Character.charCount(y)
        }
        return (a.length - i).compareTo(b.length - j)
    }
}
