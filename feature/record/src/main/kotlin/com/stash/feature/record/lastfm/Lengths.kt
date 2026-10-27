package com.stash.feature.record.lastfm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The lengths pass's memory (lengths.ts `LengthsMark`), kept beside the archive ([DaoLengthsStore]). */
data class LengthsMark(
    /** Song key -> when Last.fm last said it has no length (epoch s). */
    val none: Map<String, Long>,
    /** Song key -> when its ask last failed after every try (epoch s). */
    val failed: Map<String, Long> = emptyMap(),
    /** Rows the pass has timed so far. */
    val timed: Int = 0,
    /** When a slice last ran (epoch s). */
    val at: Long,
)

/** One spelling of a song's guessed scrobbles: the words as written, and how many rows have them. */
data class GuessedSpelling(val t: String, val a: String, val n: Int)

/** One song to ask Last.fm for (lengths.ts `LengthAsk`): its key, the spelling its guessed scrobbles use most, and how many there are. */
data class LengthAsk(val key: String, val t: String, val a: String, val plays: Int)

/** A song Spotify or the library times now: its spellings, and the `ms` its rows will play. */
data class LengthFill(val key: String, val spellings: List<GuessedSpelling>, val ms: Long) {
    val rows: Int get() = spellings.sumOf { it.n }
}

/** What a slice does next (lengths.ts `LengthPlan`). */
data class LengthPlan(
    /** Songs Spotify or the library times now: rewritten, nothing asked. */
    val fill: List<LengthFill>,
    /** The songs to ask Last.fm for, in order: most guessed plays first, then key. */
    val asks: List<LengthAsk>,
    /** The pass's misses, kept only for songs still guessed and asked within [Lengths.NONE_AGAIN_S]. */
    val none: Map<String, Long>,
    /** Its failures, kept only for songs still guessed and failed within [Lengths.FAILED_AGAIN_S]. */
    val failed: Map<String, Long>,
)

/** One answer to a length ask (lengths.ts `LengthAnswer`): the song's length in ms, or null; [hit] when no request reached Last.fm. */
data class LengthAnswer(val ms: Long?, val hit: Boolean = false)

/** How the pass asks for a song's length (lengths.ts `LengthReader`). */
fun interface LengthReader {
    /** @throws LastfmError [LastfmFailure.RECONNECT] (the account is gone), [LastfmFailure.BUSY] (try later) or [LastfmFailure.UNAVAILABLE]. */
    suspend fun look(artist: String, track: String): LengthAnswer
}

/** What the pass keeps and reads (the web's archive and its `lengths` meta), under the archive's write lock. */
interface LengthsStore {
    /** The scrobbles still guessed, by spelling. */
    suspend fun guessed(): List<GuessedSpelling>

    /** [Scrobbles.knownLengths] of the plays kept now (Spotify's, step 1). */
    suspend fun knownLengths(): Map<String, Long>

    suspend fun mark(): LengthsMark?

    suspend fun setMark(mark: LengthsMark)

    /** Every guessed scrobble with one of [spellings]' words plays [ms] and is guessed no more; returns how many rows. */
    suspend fun time(spellings: List<GuessedSpelling>, ms: Long): Int
}

/** What one slice did (lengths.ts `LengthsRun`): rows rewritten (filled and timed), songs asked, songs left to ask, and why it stopped short. */
data class LengthsRun(val rows: Int, val asked: Int, val left: Int, val stopped: LastfmFailure? = null)

/**
 * Your record's lengths pass (stash-player `src/lib/spotify/lengths.ts`, spec §7). A scrobble says when it started, never how
 * long it played, so its row counts the song's length. The best length known when a scrobble is written goes in with it
 * ([Scrobbles.plan]: a finished Spotify play of the song, else the library's own length, else [com.stash.feature.record.archive.GUESS_MS]
 * marked estimated). This pass then times the songs still guessed:
 *   1. again from Spotify and the library, which may know a song now (an export or a library song that came later): no ask;
 *   2. then from Last.fm's track.getInfo (through Stash's proxy, [com.stash.core.data.lastfm.recap.LastFmRecapRepository.songLength]),
 *      the songs with the most guessed plays first, so the hours come right soonest.
 * A song Last.fm can't time stays guessed and isn't asked again for [NONE_AGAIN_S]; one whose ask keeps failing (busy after every
 * try) is left for [FAILED_AGAIN_S] and the pass goes on to the next, so one bad song never holds up the rest.
 *
 * Resumable without a cursor: a timed song's rows lose the estimated flag, so they are out of the next plan, and the songs Last.fm
 * had no length for are kept ([LengthsMark]), saved after each. Paced like the history pages: one ask at a time,
 * [ImportScrobbles.PAGE_GAP_MS] apart (an answer the phone already had never reached Last.fm, so the next ask goes straight on), a
 * busy answer waited out [ImportScrobbles.BUSY_TRIES] times. [RecordLengths] runs it in slices ([SLICE] asks or [SLICE_MS],
 * whichever ends first) under the archive's write lock, each planned against the archive as it is then.
 *
 * The phone plans from the guessed rows' spellings, grouped in the database ([com.stash.core.data.db.dao.RecordPlayDao.guessed]),
 * never the rows themselves: the same plan, since a song key is a function of a row's words.
 */
object Lengths {
    /** A song Last.fm had no length for is asked again after this long (it may have learnt it). */
    const val NONE_AGAIN_S = 30 * 86_400L

    /** A song whose ask kept failing is asked again after this long. */
    const val FAILED_AGAIN_S = 86_400L

    /** This many songs failing in a row is Last.fm down, not a bad song: the slice stops. */
    const val FAILS_IN_A_ROW = 3

    /** One slice of the pass (one hold of the write lock): at most this many asks, for at most this long. */
    const val SLICE = 200
    const val SLICE_MS = 20_000L

    /** lengths.ts `guessedBySong`: the guessed scrobbles' spellings, by song key ([Scrobbles.songKey]: remasters folded). */
    fun guessedBySong(spellings: Iterable<GuessedSpelling>): Map<String, List<GuessedSpelling>> {
        val out = LinkedHashMap<String, MutableList<GuessedSpelling>>()
        for (s in spellings) out.getOrPut(Scrobbles.songKey(s.t, s.a)) { ArrayList() } += s
        return out
    }

    /** Plain UTF-16 order, as JavaScript compares strings (never a Collator). */
    private val byText: Comparator<String> = Comparator { x, y -> x.compareTo(y) }

    /**
     * lengths.ts `planLengths`, pure. [guessed]: [guessedBySong] over the archive; [known]: [Scrobbles.bestLengths] (the archive's
     * Spotify lengths, then the library's); [none] and [failed]: the mark's misses and failures. A song [known] times is filled
     * straight away; the rest are asked, except those Last.fm had no length for within [NONE_AGAIN_S], or whose ask failed within
     * [FAILED_AGAIN_S]. Each ask uses the spelling most of its scrobbles have (a tie: the first in string order, title then artist).
     */
    fun planLengths(
        guessed: Map<String, List<GuessedSpelling>>,
        known: Map<String, Long>,
        none: Map<String, Long>,
        nowS: Long,
        failed: Map<String, Long> = emptyMap(),
    ): LengthPlan {
        val fill = ArrayList<LengthFill>()
        val asks = ArrayList<LengthAsk>()
        val keptNone = LinkedHashMap<String, Long>()
        val keptFailed = LinkedHashMap<String, Long>()
        fun lately(at: Long?, within: Long) = at != null && at > nowS - within && at <= nowS
        for (k in guessed.keys.sortedWith(byText)) {
            val spellings = guessed.getValue(k)
            val len = known[k]
            if (len != null && len != 0L) {
                fill += LengthFill(k, spellings, Scrobbles.scrobbleMs(len))
                continue
            }
            if (lately(none[k], NONE_AGAIN_S)) {
                keptNone[k] = none.getValue(k)
                continue
            }
            if (lately(failed[k], FAILED_AGAIN_S)) {
                keptFailed[k] = failed.getValue(k)
                continue
            }
            // The same words in one place, however the database grouped them
            val counts = LinkedHashMap<Pair<String, String>, Int>()
            for (s in spellings) counts.merge(s.t to s.a, s.n, Int::plus)
            val best = counts.entries.sortedWith(
                compareByDescending<Map.Entry<Pair<String, String>, Int>> { it.value }
                    .thenComparator { x, y -> byText.compare(x.key.first, y.key.first) }
                    .thenComparator { x, y -> byText.compare(x.key.second, y.key.second) },
            ).first().key
            asks += LengthAsk(key = k, t = best.first, a = best.second, plays = counts.values.sum())
        }
        asks.sortWith(compareByDescending<LengthAsk> { it.plays }.thenComparator { x, y -> byText.compare(x.key, y.key) })
        return LengthPlan(fill, asks, keptNone, keptFailed)
    }

    /**
     * lengths.ts `fillLengths`: one slice of the pass. Plans against the archive as it is, fills what Spotify and the library
     * know, then asks Last.fm for up to [max] songs in order for up to [maxMs] (by [clock], a monotonic one), writing each answer
     * before the next ask. The caller holds the write lock. A song still busy after every try is left for [FAILED_AGAIN_S] and
     * the next asked; [FAILS_IN_A_ROW] of them, a reconnect or an unavailable reader end the slice ([LengthsRun.stopped]). What was
     * written stays, and the mark is saved at the end.
     *
     * A cancelled coroutine (the web's stop) reads and writes nothing if it was cancelled before it began; cancelled part way, it
     * saves the mark and goes on being cancelled (the web returns `stopped` instead).
     */
    suspend fun fill(
        store: LengthsStore,
        look: LengthReader,
        nowS: Long,
        library: Map<String, Long> = emptyMap(),
        max: Int = SLICE,
        maxMs: Long? = null,
        gapMs: Long = ImportScrobbles.PAGE_GAP_MS,
        busyMs: Long = ImportScrobbles.BUSY_WAIT_MS,
        clock: () -> Long = { System.nanoTime() / 1_000_000 },
        /** Rows were rewritten (each fill and each length, as written): a slice stopped part way has still changed the hours. */
        onTimed: (Int) -> Unit = {},
    ): LengthsRun {
        // Stopped while it waited for the lock: nothing read, nothing written
        currentCoroutineContext().ensureActive()
        val until = maxMs?.let { clock() + it }
        val guessed = guessedBySong(store.guessed())
        val prev = store.mark()
        val plan = planLengths(guessed, Scrobbles.bestLengths(store.knownLengths(), library), prev?.none.orEmpty(), nowS, prev?.failed.orEmpty())
        val none = LinkedHashMap(plan.none)
        val failed = LinkedHashMap(plan.failed)
        var rows = 0
        var asked = 0
        var left = plan.asks.size
        suspend fun save() = store.setMark(LengthsMark(none = none.toMap(), failed = failed.toMap(), timed = (prev?.timed ?: 0) + rows, at = nowS))
        for (f in plan.fill) {
            val n = store.time(f.spellings, f.ms)
            rows += n
            if (n > 0) onTimed(n)
        }
        val asks = plan.asks.take(max)
        var inARow = 0
        var stopped: LastfmFailure? = null
        try {
            for ((i, ask) in asks.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (i > 0 && until != null && clock() >= until) break
                val a: LengthAnswer? = try {
                    ImportScrobbles.patiently(busyMs) { look.look(ask.a, ask.t) }
                } catch (e: LastfmError) {
                    if (e.kind != LastfmFailure.BUSY) throw e
                    null
                }
                asked++
                left--
                if (a == null) {
                    // Busy after every try: this song waits, the next is asked; several in a row is Last.fm down
                    failed[ask.key] = nowS
                    save()
                    if (++inARow >= FAILS_IN_A_ROW) throw LastfmError(LastfmFailure.BUSY)
                } else {
                    inARow = 0
                    if (a.ms != null && a.ms != 0L) {
                        val n = store.time(guessed[ask.key].orEmpty(), Scrobbles.scrobbleMs(a.ms))
                        rows += n
                        if (n > 0) onTimed(n)
                    } else {
                        none[ask.key] = nowS
                        save()
                    }
                }
                if (i < asks.size - 1) delay(if (a?.hit == true) 0 else gapMs)
            }
        } catch (e: CancellationException) {
            // Stopped part way: what it learnt is kept, as the web saves after a stop
            withContext(NonCancellable) { save() }
            throw e
        } catch (e: LastfmError) {
            stopped = e.kind
        }
        save()
        return LengthsRun(rows = rows, asked = asked, left = left, stopped = stopped)
    }
}
