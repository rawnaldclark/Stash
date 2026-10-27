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
    /** Song key -> when its ask last failed (epoch s)... */
    val failed: Map<String, Long> = emptyMap(),
    /** ...and how many times in a row it has (1 when missing): its wait doubles with each ([Lengths.failedWait]). */
    val failures: Map<String, Int> = emptyMap(),
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
    /** Its failures and their counts, kept for songs still guessed until they are answered. */
    val failed: Map<String, Long>,
    val failures: Map<String, Int> = emptyMap(),
)

/** One answer to a length ask (lengths.ts `LengthAnswer`): the song's length in ms, or null; [hit] when no request reached Last.fm. */
data class LengthAnswer(val ms: Long?, val hit: Boolean = false)

/** How the pass asks for a song's length (lengths.ts `LengthReader`). */
fun interface LengthReader {
    /**
     * @throws LastfmError [LastfmFailure.RECONNECT], [LastfmFailure.BUSY] (try later), [LastfmFailure.UNAVAILABLE], or one with a
     *   [LastfmError.hold] (a limit, no network, no proxy: no song to blame).
     */
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

    /**
     * Every guessed scrobble of each song (its spellings' words) plays that song's `ms` and is guessed no more, all in one write;
     * returns how many rows each song timed, in order.
     */
    suspend fun time(songs: List<Pair<List<GuessedSpelling>, Long>>): List<Int>
}

/**
 * What one slice did (lengths.ts `LengthsRun`): rows rewritten (filled and timed), songs asked, songs left to ask, why it stopped
 * short (if it did), the songs failing in a row at its end (the run's next slice carries on from it), and, when it was stopped by a
 * hold ([LengthHold]: a limit, no network, no proxy), which, and for a limit when to come back.
 */
data class LengthsRun(
    val rows: Int,
    val asked: Int,
    val left: Int,
    val stopped: LastfmFailure? = null,
    val streak: Int = 0,
    val retryAt: Long? = null,
    val hold: LengthHold? = null,
)

/**
 * A run of the lengths pass, planned once ([Lengths.plan]) and asked a slice at a time ([Lengths.slice]): within a run no other
 * writer touches the archive (they stop the pass first), so the plan holds, and the songs answered drop off its front as they go.
 */
class LengthsRunState internal constructor(
    internal val guessed: Map<String, List<GuessedSpelling>>,
    internal var fill: List<LengthFill>,
    internal val asks: ArrayDeque<LengthAsk>,
    internal val none: LinkedHashMap<String, Long>,
    internal val failed: LinkedHashMap<String, Long>,
    internal val failures: LinkedHashMap<String, Int>,
    /** The mark's timed count, with what this run has timed. */
    internal var timed: Int,
    /** Songs failing in a row, carried from slice to slice. */
    internal var streak: Int,
) {
    /** Songs left to ask. */
    val left: Int get() = asks.size
}

/**
 * Your record's lengths pass (stash-player `src/lib/spotify/lengths.ts`, spec §7). A scrobble says when it started, never how
 * long it played, so its row counts the song's length. The best length known when a scrobble is written goes in with it
 * ([Scrobbles.plan]: a finished Spotify play of the song, else the library's own length, else [com.stash.feature.record.archive.GUESS_MS]
 * marked estimated). This pass then times the songs still guessed:
 *   1. again from Spotify and the library, which may know a song now (an export or a library song that came later): no ask;
 *   2. then from Last.fm's track.getInfo (through Stash's proxy, [com.stash.core.data.lastfm.recap.LastFmRecapRepository.songLength]),
 *      the songs with the most guessed plays first, so the hours come right soonest.
 * A song Last.fm can't time stays guessed and isn't asked again for [NONE_AGAIN_S]. One whose ask keeps failing (busy after its
 * [LENGTH_TRIES], or an answer that isn't one) waits [FAILED_AGAIN_S], twice that after a second failure in a row, and so on up
 * to [FAILED_AGAIN_MAX_S], and the pass goes on to the next, so one bad song never holds up the rest. [FAILS_IN_A_ROW] songs
 * failing one after another is Last.fm down: the run ends (the streak carries from slice to slice).
 *
 * Resumable without a cursor: a timed song's rows lose the estimated flag, so they are out of the next run's plan, and the songs
 * Last.fm had no length for are kept ([LengthsMark]). Paced: one ask at a time, a gap after one that reached the network (none
 * after an answer the phone had). [RecordLengths] runs it in slices ([SLICE] asks or [SLICE_MS], whichever ends first) under the
 * archive's write lock, each written at its end in one go.
 *
 * The phone plans from the guessed rows' spellings, grouped in the database ([com.stash.core.data.db.dao.RecordPlayDao.guessed]),
 * never the rows themselves: the same plan, since a song key is a function of a row's words.
 */
object Lengths {
    /** A song Last.fm had no length for is asked again after this long (it may have learnt it). */
    const val NONE_AGAIN_S = 30 * 86_400L

    /** A song whose ask failed is asked again after this long, doubled for each failure in a row after the first... */
    const val FAILED_AGAIN_S = 86_400L

    /** ...up to this. */
    const val FAILED_AGAIN_MAX_S = 16 * 86_400L

    /** Asks of one song while Last.fm says busy (the pages' six are for a whole history; a song is asked again after its wait). */
    const val LENGTH_TRIES = 2

    /** This many songs failing in a row is Last.fm down, not a bad song: the run ends, whichever slice it is in. */
    const val FAILS_IN_A_ROW = 3

    /** One slice of the pass (one hold of the write lock): at most this many asks, for at most this long. */
    const val SLICE = 200
    const val SLICE_MS = 20_000L

    /** lengths.ts `failedWait`: how long a song that has failed [failures] times in a row waits (s): 1, 2, 4, 8, then 16 days. */
    fun failedWait(failures: Int): Long {
        val doublings = (failures - 1).coerceIn(0, 30)
        return minOf(FAILED_AGAIN_MAX_S, FAILED_AGAIN_S shl doublings.coerceAtMost(5))
    }

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
     * Spotify lengths, then the library's); [none], [failed] and [failures]: the mark's misses, failures and their counts. A song
     * [known] times is filled straight away; the rest are asked, except those Last.fm had no length for within [NONE_AGAIN_S], or
     * whose ask failed within its wait ([failedWait]). Each ask uses the spelling most of its scrobbles have (a tie: the first in
     * string order, title then artist).
     */
    fun planLengths(
        guessed: Map<String, List<GuessedSpelling>>,
        known: Map<String, Long>,
        none: Map<String, Long>,
        nowS: Long,
        failed: Map<String, Long> = emptyMap(),
        failures: Map<String, Int> = emptyMap(),
    ): LengthPlan {
        val fill = ArrayList<LengthFill>()
        val asks = ArrayList<LengthAsk>()
        val keptNone = LinkedHashMap<String, Long>()
        val keptFailed = LinkedHashMap<String, Long>()
        val keptFailures = LinkedHashMap<String, Int>()
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
            val at = failed[k]
            if (at != null) {
                // Kept with its count until it is answered, so another failure waits longer
                val n = failures[k] ?: 1
                keptFailed[k] = at
                keptFailures[k] = n
                if (lately(at, failedWait(n))) continue
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
        return LengthPlan(fill, asks, keptNone, keptFailed, keptFailures)
    }

    /** A run's plan, read from [store] as the archive is now (under the write lock). */
    suspend fun plan(store: LengthsStore, nowS: Long, library: Map<String, Long> = emptyMap(), streak: Int = 0): LengthsRunState {
        val guessed = guessedBySong(store.guessed())
        val prev = store.mark()
        val plan = planLengths(guessed, Scrobbles.bestLengths(store.knownLengths(), library), prev?.none.orEmpty(), nowS, prev?.failed.orEmpty(), prev?.failures.orEmpty())
        return LengthsRunState(
            guessed = guessed,
            fill = plan.fill,
            asks = ArrayDeque(plan.asks),
            none = LinkedHashMap(plan.none),
            failed = LinkedHashMap(plan.failed),
            failures = LinkedHashMap(plan.failures),
            timed = prev?.timed ?: 0,
            streak = streak,
        )
    }

    /**
     * lengths.ts `fillLengths`'s slice, over a run's plan: fills what Spotify and the library know (the run's first slice), then
     * asks Last.fm for up to [max] songs in order for up to [maxMs] (by [clock], a monotonic one), and writes what it learnt in one
     * go at its end ([LengthsStore.time], then the mark). The caller holds the write lock.
     * - A song still busy after [LENGTH_TRIES] waits ([failedWait]) and the next is asked; [FAILS_IN_A_ROW] in a row, counting the
     *   run's earlier slices, end the slice ([LengthsRun.stopped] BUSY), and the run.
     * - An answer that isn't one ([LastfmFailure.UNAVAILABLE], or an ask that throws) marks its song failed too, then ends the
     *   slice, so the next pass goes on to the next song. A reconnect ends it, marking nothing.
     * - A hold ([LastfmError.hold]: a rate limit, the daily cap, no network, no proxy) ends it at once, marking nothing, with
     *   when to come back for a limit. What Spotify and the library know is filled all the same.
     * - A song whose rows can't be found when written (none timed) is marked as having no length, so it can't sit at the front.
     *
     * A cancelled coroutine (the web's stop) reads and writes nothing if it was cancelled before it began; cancelled part way, it
     * writes what it learnt and goes on being cancelled (the web returns `stopped` instead).
     */
    suspend fun slice(
        state: LengthsRunState,
        store: LengthsStore,
        look: LengthReader,
        nowS: Long,
        max: Int = SLICE,
        maxMs: Long? = null,
        gapMs: Long = ImportScrobbles.PAGE_GAP_MS,
        busyMs: Long = ImportScrobbles.BUSY_WAIT_MS,
        clock: () -> Long = { System.nanoTime() / 1_000_000 },
        /** Rows were rewritten (as written, at the slice's end): a slice stopped part way has still changed the hours. */
        onTimed: (Int) -> Unit = {},
    ): LengthsRun {
        // Stopped while it waited for the lock: nothing read, nothing written
        currentCoroutineContext().ensureActive()
        val until = maxMs?.let { clock() + it }
        // What this slice learnt, written at its end: songs to time (key, spellings, ms), in order
        val pending = ArrayList<Triple<String, List<GuessedSpelling>, Long>>()
        for (f in state.fill) pending += Triple(f.key, f.spellings, f.ms)
        state.fill = emptyList()
        var rows = 0
        var asked = 0
        var stopped: LastfmFailure? = null
        var retryAt: Long? = null
        var hold: LengthHold? = null
        fun fail(key: String) {
            state.failed[key] = nowS
            state.failures[key] = (state.failures[key] ?: 0) + 1
        }
        suspend fun write() {
            if (pending.isNotEmpty()) {
                val counts = store.time(pending.map { it.second to it.third })
                var timedNow = 0
                for ((i, p) in pending.withIndex()) {
                    val n = counts.getOrElse(i) { 0 }
                    if (n > 0) {
                        timedNow += n
                    } else {
                        // Its rows weren't there to time: no length, rather than the front of every plan from now on
                        state.none[p.first] = nowS
                        state.failed.remove(p.first)
                        state.failures.remove(p.first)
                    }
                }
                pending.clear()
                rows += timedNow
                state.timed += timedNow
                if (timedNow > 0) onTimed(timedNow)
            }
            store.setMark(LengthsMark(none = state.none.toMap(), failed = state.failed.toMap(), failures = state.failures.toMap(), timed = state.timed, at = nowS))
        }
        val count = minOf(max, state.asks.size)
        try {
            for (i in 0 until count) {
                currentCoroutineContext().ensureActive()
                if (i > 0 && until != null && clock() >= until) break
                val ask = state.asks.first()
                val a: LengthAnswer? = try {
                    ImportScrobbles.patiently(busyMs, LENGTH_TRIES) { look.look(ask.a, ask.t) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: LastfmError) {
                    when {
                        // A hold isn't the song's: it stays first, nothing marked, and the run comes back when it lifts
                        e.hold != null -> {
                            hold = e.hold
                            retryAt = e.retryAt
                            throw e
                        }
                        e.kind == LastfmFailure.RECONNECT -> throw e
                        e.kind == LastfmFailure.BUSY -> null
                        else -> {
                            // An answer that isn't one: this song waits too, so the next pass goes on to the next one
                            state.asks.removeFirst()
                            asked++
                            fail(ask.key)
                            throw LastfmError(LastfmFailure.UNAVAILABLE)
                        }
                    }
                } catch (e: Exception) {
                    // An ask that throws (a fault, an answer that couldn't be read): the same
                    state.asks.removeFirst()
                    asked++
                    fail(ask.key)
                    throw LastfmError(LastfmFailure.UNAVAILABLE)
                }
                state.asks.removeFirst()
                asked++
                if (a == null) {
                    // Busy after its tries: this song waits, the next is asked; several in a row is Last.fm down
                    fail(ask.key)
                    state.streak++
                    if (state.streak >= FAILS_IN_A_ROW) throw LastfmError(LastfmFailure.BUSY)
                } else {
                    state.streak = 0
                    // Answered: no longer failing
                    state.failed.remove(ask.key)
                    state.failures.remove(ask.key)
                    if (a.ms != null && a.ms != 0L) {
                        pending += Triple(ask.key, state.guessed[ask.key].orEmpty(), Scrobbles.scrobbleMs(a.ms))
                    } else {
                        state.none[ask.key] = nowS
                    }
                }
                if (i < count - 1) delay(if (a?.hit == true) 0 else gapMs)
            }
        } catch (e: CancellationException) {
            // Stopped part way: what it learnt is kept, as the web saves after a stop
            withContext(NonCancellable) { write() }
            throw e
        } catch (e: LastfmError) {
            stopped = e.kind
        }
        write()
        return LengthsRun(rows = rows, asked = asked, left = state.asks.size, stopped = stopped, streak = state.streak, retryAt = retryAt, hold = hold)
    }

    /**
     * lengths.ts `fillLengths`: one slice planned against the archive as it is ([plan] then [slice]), as the web runs every slice;
     * [streak]: the songs failing in a row before it.
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
        streak: Int = 0,
        onTimed: (Int) -> Unit = {},
    ): LengthsRun {
        // Stopped while it waited for the lock: nothing read, nothing written
        currentCoroutineContext().ensureActive()
        return slice(plan(store, nowS, library, streak), store, look, nowS, max, maxMs, gapMs, busyMs, clock, onTimed)
    }
}
