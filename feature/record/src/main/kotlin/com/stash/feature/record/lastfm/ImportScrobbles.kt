package com.stash.feature.record.lastfm

import com.stash.feature.record.archive.ArchivePlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Where the Last.fm import stands (lastfm.ts `LastfmMark`), kept between runs ([com.stash.feature.record.imports.RecordImportStore]). */
data class LastfmMark(
    /** The Last.fm user it belongs to: another user's scrobbles start over. */
    val user: String,
    /** The whole history's upper bound (epoch s, fixed for the run, so new scrobbles don't move the pages)... */
    val to: Long,
    /** ...and the next page of it to read. */
    val next: Int,
    val pages: Int,
    /** All of it read. */
    val done: Boolean,
    /** The newest scrobble read (epoch s): a top-up reads from [ImportScrobbles.LATE_S] below it. 0: none yet, so the next run reads the whole history again. */
    val newest: Long,
    /**
     * This account's scrobbles the archive holds. Alone in the archive (no [base]), it is the archive's Last.fm rows, counted
     * again at the start of every run, so rows written just before the app died count and rows a Spotify import took over since
     * don't. Beside another account's rows, which the archive can't tell apart, it is this account's own reads: every scrobble of
     * a whole read the archive holds (written then or before), and what a top-up adds.
     */
    val added: Int,
    /** Scrobbles read that Spotify already had (counted once, as read; a top-up's overlap isn't counted again). */
    val covered: Int,
    /** When it last ran (epoch s, the run's start). */
    val at: Long,
    /** The Last.fm rows other accounts had brought in when this one's import began (they stay in the record). Set: [added] is counted from reads. */
    val base: Int? = null,
    /** The whole read's oldest scrobble read so far (epoch s): a page skipped next lies below it. */
    val oldest: Long? = null,
    /** A page before the last that listed nothing, and the runs in a row that stopped on it (the whole read's, or a top-up's). */
    val stuck: Stuck? = null,
    /** What Last.fm didn't send, as stretches of time: asked again on every later top-up, gone once one reads through. */
    val holes: List<Hole>? = null,
) {
    /**
     * lastfm.ts `stuck`: [page] stopped [runs] runs in a row as a page before the last that listed nothing, the first at [since] and
     * the latest at [last] (epoch s); [pages]: how many pages Last.fm claimed then; [topup]: in a top-up; [tail]: the latest found
     * the pages after it gone too while the page before still had plays (the tail, not a hole in the middle).
     */
    data class Stuck(
        val page: Int,
        val runs: Int,
        val since: Long,
        val last: Long,
        val pages: Int? = null,
        val topup: Boolean = false,
        val tail: Boolean = false,
    )

    /**
     * lastfm.ts `Hole`: a stretch of the history Last.fm didn't send, epoch s, both ends kept ([from] 0: everything below [to]),
     * and when it was found ([at]). [open]: the whole read's, its lower end not known yet: the next page read with plays sets it;
     * none (the read reached the end) leaves it at 0, the tail. [fails], [next]: asks in a row that didn't read through, and not
     * before when to ask again; [empty]: when Last.fm first said there is nothing in it.
     */
    data class Hole(
        val from: Long,
        val to: Long,
        val at: Long,
        val open: Boolean = false,
        val fails: Int = 0,
        val next: Long? = null,
        val empty: Long? = null,
    )
}

/** How far a run is (lastfm.ts `LastfmProgress`): the page just read of how many, and the scrobbles kept so far. */
data class LastfmProgress(val page: Int, val pages: Int, val added: Int)

/** How a read can stop short (lastfm.ts `LastfmError`). */
enum class LastfmFailure {
    /** The session ended (the account was disconnected, or Last.fm no longer takes its session): connect again. */
    RECONNECT,

    /** Busy, rate limited or unreachable, still after waiting: carry on later. */
    BUSY,

    /** Last.fm answered with something that isn't an answer. */
    UNAVAILABLE,

    /** Anything else. */
    OTHER,
}

/** Why the lengths pass stops asking, though no song is to blame: nothing marked, and it comes back when the hold lifts. */
enum class LengthHold {
    /** A rate limit, or the day's lookups spent: back at [LastfmError.retryAt]. */
    LIMIT,

    /** No network the app's Wi-Fi-only setting allows: back once there is one. */
    NETWORK,

    /** No proxy to ask through (this build has none): no lookups at all. */
    NO_PROXY,
}

/**
 * lastfm.ts `LastfmError`: a page (or a length) that couldn't be read, and why. [empty]: busy because a page before the last listed
 * nothing (a hole in the paging, not an outage: counted, [ImportScrobbles.STUCK_RUNS]), Last.fm claiming [pages] pages then.
 * [hold]: the lengths pass stops asking though no song is to blame ([LengthHold]), not before [retryAt] (epoch ms) for a limit;
 * never waited out in place.
 */
class LastfmError(
    val kind: LastfmFailure,
    val empty: Boolean = false,
    val pages: Int? = null,
    val hold: LengthHold? = null,
    val retryAt: Long? = null,
) : Exception("Last.fm: $kind") {
    /** A rate limit or the day's cap. */
    val limited: Boolean get() = hold == LengthHold.LIMIT
}

/** One page from Last.fm (lastfm.ts `PageReader`): page [page] (from 1) up to [to], from [from] when given (epoch s). */
fun interface PageReader {
    /** @throws LastfmError when the page can't be read. */
    suspend fun read(page: Int, to: Long, from: Long?): HistoryPage
}

/** What the import keeps (the web's archive and its meta): the plays it checks and writes, and its mark. */
interface ScrobbleStore : ScrobbleArchive {
    suspend fun mark(): LastfmMark?

    suspend fun setMark(mark: LastfmMark)

    suspend fun clearMark()

    /** [Scrobbles.knownLengths] of the plays kept now. */
    suspend fun knownLengths(): Map<String, Long>

    /** How many scrobbles the archive holds now (lastfm.ts `startMark`'s `held`). */
    suspend fun held(): Int

    /** A page's rows, written. */
    suspend fun add(rows: List<ArchivePlay>)

    /** Every scrobble out, and what the lengths pass kept about them; returns how many went. */
    suspend fun deleteScrobbles(): Int
}

/**
 * Your Last.fm scrobbles into Your record (stash-player `src/lib/spotify/lastfm.ts`, spec §7), page by page, newest first, into
 * the same archive as the Spotify export. Resumable: where it stopped is kept ([LastfmMark]), so a run stopped (by the person,
 * the system, or the app's death) carries on from that page. Once the whole history is in, a top-up reads what is newer than
 * the newest scrobble kept, from [LATE_S] below it (Last.fm takes scrobbles stamped up to two weeks back, from a phone that synced
 * late). Paced to stay well inside Last.fm's limits: one page at a time, [PAGE_GAP_MS] apart, and a busy answer waits
 * [BUSY_WAIT_MS] times the attempt before asking again, [BUSY_TRIES] attempts at most. The song lengths come after, in their own
 * pass ([Lengths]).
 */
object ImportScrobbles {
    const val PAGE_GAP_MS = 350L
    const val BUSY_WAIT_MS = 10_000L
    const val BUSY_TRIES = 6

    /** lastfm.ts `LATE_S`: how far below the newest scrobble kept a top-up reads (Last.fm takes scrobbles stamped up to 14 days back). */
    const val LATE_S = 14 * 86_400L

    /**
     * lastfm.ts `STUCK_RUNS`: a page before the last that still lists nothing after this many runs, each asking it [BUSY_TRIES]
     * times over minutes, and over at least [STUCK_SPAN_S] from the first to the latest, is a hole in Last.fm's paging, not a
     * stumble: the next run skips it so the import can finish. So is a tail Last.fm stopped sending (the pages after it empty, the
     * page before still full). What was skipped is kept as a stretch of time ([LastfmMark.holes]), never as a page number, which
     * deletions, late scrobbles and backfills move; every later top-up asks that stretch again by time, so what a passing incident
     * hid comes back by itself. Three runs over an hour are separate tries at different times; three quick Carry on taps are not.
     */
    const val STUCK_RUNS = 3
    const val STUCK_SPAN_S = 3_600L

    /**
     * lastfm.ts `HOLE_PAGES`: pages a top-up asks of its holes in one run, all holes together: a big one (a heavy listener's tail)
     * goes on next time from where it stopped, so a run stays well inside a background job's ten minutes and never holds the
     * archive's lock for long.
     */
    const val HOLE_PAGES = 100

    /** lastfm.ts `HOLE_AGAIN_S`: a hole that didn't read through is asked again after a day, doubling each time, to 16 days at most. */
    const val HOLE_AGAIN_S = 86_400L
    const val HOLE_AGAIN_MAX_S = 16 * 86_400L

    /** lastfm.ts `HOLE_EMPTY_S`: a hole Last.fm says is empty is dropped only when it says so again at least this long after. */
    const val HOLE_EMPTY_S = 3_600L

    /** lastfm.ts `holeWait`: how long a hole that didn't read through [fails] times in a row waits. */
    fun holeWait(fails: Int): Long = minOf(HOLE_AGAIN_MAX_S, HOLE_AGAIN_S shl minOf(maxOf(0, fails - 1), 20))

    /**
     * lastfm.ts `patiently`: [ask], asked again while Last.fm is busy (at most [tries] times, waiting [wait] × the attempt between).
     * A hold ([LastfmError.hold]) is never waited out here: it says when to come back.
     */
    internal suspend fun <T> patiently(wait: Long, tries: Int = BUSY_TRIES, ask: suspend () -> T): T {
        var i = 0
        while (true) {
            try {
                return ask()
            } catch (e: LastfmError) {
                if (e.kind != LastfmFailure.BUSY || e.hold != null || i >= tries - 1) throw e
                delay(wait * (i + 1))
            }
            i++
        }
    }

    /**
     * lastfm.ts `steadily`: a page, asked patiently, and asked again (bounded) while it says the history is shorter than the run
     * knew ([known] pages), empty or not: that is Last.fm stumbling as often as a history that shrank, and a smaller total would
     * end the read short. Still so after [BUSY_TRIES] asks, it is believed.
     */
    private suspend fun steadily(read: PageReader, page: Int, to: Long, from: Long?, known: Int, wait: Long): HistoryPage {
        var p = patiently(wait) { read.read(page, to, from) }
        var i = 1
        while (i < BUSY_TRIES && p.pages < known && page <= known) {
            delay(wait * i)
            p = patiently(wait) { read.read(page, to, from) }
            i++
        }
        return p
    }

    /**
     * lastfm.ts `startMark`, pure: the mark a run starts from. [held]: the Last.fm rows the archive holds now.
     * - None, or another user's: this user's whole history from the top. Rows held then are other accounts' ([LastfmMark.base]),
     *   and stay.
     * - A whole read that found nothing (done, newest 0): the whole history again, below now. There is nothing to top up from, and
     *   an empty first read (a new account, say) must not leave the import stuck empty.
     * - Otherwise where it stood; alone in the archive, its count taken again from it ([held]).
     */
    fun startMark(prev: LastfmMark?, user: String, nowS: Long, held: Int): LastfmMark {
        if (prev == null || prev.user != user) {
            return LastfmMark(user = user, to = nowS, next = 1, pages = 0, done = false, newest = 0, added = 0, covered = 0, at = 0, base = held.takeIf { it > 0 })
        }
        val added = if ((prev.base ?: 0) > 0) prev.added else held
        // (a fresh whole read: the old one's stuck page and holes are read again with it)
        if (prev.done && prev.newest == 0L) {
            return prev.copy(to = nowS, next = 1, pages = 0, done = false, added = added, stuck = null, holes = null, oldest = null)
        }
        return prev.copy(added = added)
    }

    /** A skipped page as earlier builds kept it, by number: as it was asked (its read's [to], a top-up's [from]), and when ([at]). */
    data class SkippedPage(val page: Int, val to: Long, val from: Long?, val at: Long)

    /**
     * lastfm.ts `readMark`'s part for an earlier build's skipped pages, which can't be asked again safely by number: each becomes
     * the stretch it could have held, a top-up's page that top-up's window, a whole read's page everything below that read's bound
     * (read once more at the next top-up, its ids keeping each play once).
     */
    fun holesFrom(skipped: List<SkippedPage>): List<LastfmMark.Hole> =
        skipped.filter { it.to > 0 }.map { LastfmMark.Hole(from = it.from?.takeIf { f -> f > 0 } ?: 0L, to = it.to, at = it.at) }

    /** What one page brought: rows written, the page's scrobbles the archive holds now, ones Spotify had, and its newest start. */
    private class Took(val added: Int, val own: Int, val covered: Int, val newest: Long)

    /**
     * lastfm.ts `importScrobbles`: [user]'s scrobbles into [store], the rest of the whole history, then (or once that's done)
     * what is newer. Each page is planned against the archive as it is and written before the next is asked for; the mark is
     * saved after every page's plays, in the whole read and in a top-up alike. Only a page at or past Last.fm's last ends a read:
     * an empty page before it is Last.fm stumbling, which [LastfmPages] answers as busy ([LastfmError.empty]). One that stays so
     * [STUCK_RUNS] runs in a row, over [STUCK_SPAN_S], is skipped, a tail gone the same way (`stuckOn` tells them apart, and an
     * incident from either); what they held is kept as a stretch of time ([LastfmMark.holes]) and asked again, by time, on every
     * later top-up.
     *
     * [library]: the library's song lengths ([Scrobbles.libraryLengths]), for the rows the archive's Spotify plays can't time.
     * [onStart]: the mark the run starts from, its count taken from the archive (the done line counts from it). The caller holds
     * the archive's write lock for the whole run (nothing else writes meanwhile). A cancelled coroutine stops at the next page or
     * pause; what was written and marked stays.
     */
    suspend fun run(
        store: ScrobbleStore,
        read: PageReader,
        user: String,
        nowS: Long,
        library: Map<String, Long> = emptyMap(),
        progress: (LastfmProgress) -> Unit = {},
        onStart: (LastfmMark) -> Unit = {},
        gapMs: Long = PAGE_GAP_MS,
        busyMs: Long = BUSY_WAIT_MS,
        /** Pages a top-up may ask of its holes this run ([HOLE_PAGES]). */
        holePages: Int = HOLE_PAGES,
    ): LastfmMark {
        var mark = startMark(store.mark(), user, nowS, store.held())
        onStart(mark)
        // Known once: scrobbles never count towards a song's length, so the run's own writes can't change them
        val lengths = Scrobbles.bestLengths(store.knownLengths(), library)

        /**
         * Writes a page's new plays. At or below [since] a top-up reads again what it read before (the late-scrobble overlap): what
         * is new there is added, but what Spotify covers there was counted the first time. [own] (only when asked: one more read):
         * the page's scrobbles the archive holds now, written now or before, each once.
         */
        suspend fun take(plays: List<Scrobble>, since: Long = 0, own: Boolean = false): Took {
            val newer = if (since != 0L) plays.filter { it.uts > since } else plays
            val older = if (since != 0L) plays.filter { it.uts <= since } else emptyList()
            // (each plan reads the archive as it is: an empty part isn't planned)
            val plan = if (newer.isNotEmpty()) Scrobbles.plan(store, newer, lengths) else ScrobblePlan(emptyList(), 0, 0)
            val add = if (older.isNotEmpty()) plan.add + Scrobbles.plan(store, older, lengths).add else plan.add
            store.add(add)
            val held = if (own) store.kept(plays.map { Scrobbles.id(it) }.distinct()).size else 0
            return Took(add.size, held, plan.covered, maxOf(0L, plays.maxOfOrNull { it.uts } ?: 0L))
        }

        /** One ask of [page], not waited out: null when it isn't answered. */
        suspend fun askOnce(page: Int, to: Long, from: Long?): HistoryPage? = try {
            read.read(page, to, from)
        } catch (c: CancellationException) {
            throw c
        } catch (x: Exception) {
            null
        }

        /**
         * lastfm.ts `stuckOn`: the run stopped on an empty page before the last ([topup]: in a top-up; [to], [from]: the read's
         * window). What lies past it is asked:
         * - the last page Last.fm claims, once. With plays, or no answer: a hole in the middle.
         * - empty too: the page before (just read, with plays) once more. Empty now, or no answer: Last.fm is emptying pages for a
         *   while, an incident, plain busy and not counted. Still with plays: the tail is gone (scrobbles deleted under a stale
         *   total). A first page has none before it, so its empty last page says the tail.
         * A hole or a tail is one more run for this page, kept with the mark; only [STUCK_RUNS] of them over [STUCK_SPAN_S] skip it.
         */
        suspend fun stuckOn(e: LastfmError, page: Int, topup: Boolean, to: Long, from: Long?) {
            if (!e.empty) return
            val claimed = e.pages ?: 0
            var tail = false
            if (claimed > page) {
                val last = askOnce(claimed, to, from)
                if (last != null && last.plays.isEmpty()) {
                    if (page > 1) {
                        val before = askOnce(page - 1, to, from)
                        if (before == null || before.plays.isEmpty()) return
                    }
                    tail = true
                }
            }
            val s = mark.stuck?.takeIf { it.page == page && it.topup == topup }
            val pages = maxOf(claimed, s?.pages ?: 0)
            mark = mark.copy(stuck = LastfmMark.Stuck(page, (s?.runs ?: 0) + 1, s?.since ?: nowS, nowS, pages.takeIf { it > 0 }, topup, tail))
            store.setMark(mark)
        }

        /** [page] has stopped [STUCK_RUNS] runs in a row, the first and the latest [STUCK_SPAN_S] apart: skipped. */
        fun hole(page: Int, topup: Boolean) =
            mark.stuck?.let { it.page == page && it.topup == topup && it.runs >= STUCK_RUNS && it.last - it.since >= STUCK_SPAN_S } == true

        fun withHoles(holes: List<LastfmMark.Hole>) {
            mark = mark.copy(holes = holes.ifEmpty { null })
        }

        /** The whole read's open hole, its lower end now known ([from]: the newest play of the page read below it; 0: the tail). */
        fun close(from: Long) {
            val holes = mark.holes.orEmpty()
            val i = holes.indexOfFirst { it.open }
            if (i < 0) return
            withHoles(holes.mapIndexed { j, h -> if (j == i) h.copy(from = from, open = false) else h })
        }

        fun oldestOf(plays: List<Scrobble>): Long = plays.minOf { it.uts }

        /**
         * lastfm.ts `recheckHoles`: the holes found before this run, each asked again by time (pages can't move under a window of
         * time), at most [holePages] pages in all this run. Each page is asked once (one that won't come is tried again later, not
         * waited out), its bounds a second wider (Last.fm's may leave out the second itself; ids keep each play once). What a hole
         * holds now comes in, and the hole is narrowed to below what came with every page saved, so a run cut short (or out of
         * pages) goes on next time from there. One that reads through is gone; one Last.fm says is empty only when it says so again
         * at least [HOLE_EMPTY_S] later; one that fails is asked again after [holeWait].
         */
        suspend fun recheckHoles() {
            var left = holePages
            val holes = mark.holes.orEmpty().toMutableList<LastfmMark.Hole?>()
            suspend fun save() {
                withHoles(holes.filterNotNull())
                store.setMark(mark)
            }
            var k = 0
            while (k < holes.size && left > 0) {
                val w = holes[k]!!
                if (w.open || w.at >= nowS || (w.next ?: 0L) > nowS) {
                    k++
                    continue
                }
                val to = w.to + 1
                val from = if (w.from > 1) w.from - 1 else null
                var page = 1
                while (left > 0) {
                    left--
                    val p = try {
                        read.read(page, to, from)
                    } catch (e: LastfmError) {
                        if (e.kind == LastfmFailure.RECONNECT) throw e
                        val fails = holes[k]!!.fails + 1
                        holes[k] = holes[k]!!.copy(fails = fails, next = nowS + holeWait(fails))
                        save()
                        break
                    }
                    // Inside the hole: the plays at its bounds (and the second beyond) were read with the pages beside it
                    val inside = p.plays.filter { it.uts > w.from && it.uts < w.to }
                    if (page == 1 && inside.isEmpty() && page >= p.pages) {
                        // Nothing in it, Last.fm says (total 0, or only what its bounds hold): believed when it says so again, an hour on
                        val h = holes[k]!!
                        if (h.empty != null && nowS - h.empty >= HOLE_EMPTY_S) {
                            holes[k] = null
                        } else if (h.empty == null) {
                            holes[k] = h.copy(empty = nowS)
                        }
                        save()
                        break
                    }
                    if (p.plays.isNotEmpty()) {
                        val got = take(p.plays)
                        // Beside another account's rows, this account's own reads, as the whole read counts them
                        val beside = (mark.base ?: 0) > 0
                        val own = if (beside) store.kept(inside.map { Scrobbles.id(it) }.distinct()).size else 0
                        mark = mark.copy(added = mark.added + if (beside) own else got.added, covered = mark.covered + got.covered)
                        val h = holes[k]!!
                        holes[k] = LastfmMark.Hole(h.from, minOf(h.to, oldestOf(p.plays)), h.at, h.open)
                    }
                    if (page >= p.pages) holes[k] = null
                    save()
                    if (holes[k] == null) break
                    delay(gapMs)
                    page++
                }
                k++
            }
        }

        // The whole history, from where it stopped (pages are newest first, below a fixed `to`)
        while (!mark.done) {
            val page = mark.next
            if (hole(page, false)) {
                val s = mark.stuck!!
                // What it held lies below the last page read with plays (none yet: below the read's bound); its lower end is the
                // next page with plays, or the start of the history
                if (mark.holes.orEmpty().none { it.open }) withHoles(mark.holes.orEmpty() + LastfmMark.Hole(0, mark.oldest ?: mark.to, nowS, open = true))
                mark = mark.copy(stuck = null)
                if (s.tail) {
                    // The tail is gone: a hole down to the start, and the whole read is complete
                    close(0)
                    mark = mark.copy(pages = page - 1, done = true, at = nowS)
                } else {
                    // Skipped, and the read goes on. Its page count is what Last.fm last said, even with no page read yet (a first
                    // page stuck), so a skip never ends a read that hasn't begun.
                    val pages = maxOf(mark.pages, s.pages ?: 0)
                    mark = mark.copy(pages = pages, next = page + 1, done = pages > 0 && page >= pages, at = nowS)
                    if (mark.done) close(0)
                }
                store.setMark(mark)
                continue
            }
            val p = try {
                steadily(read, page, mark.to, null, mark.pages, busyMs)
            } catch (e: LastfmError) {
                stuckOn(e, page, false, mark.to, null)
                throw e
            }
            if (mark.stuck?.let { !it.topup && it.page == page } == true) mark = mark.copy(stuck = null)
            val beside = (mark.base ?: 0) > 0
            val got = take(p.plays, own = beside)
            if (p.plays.isNotEmpty()) {
                close(got.newest)
                mark = mark.copy(oldest = minOf(mark.oldest ?: Long.MAX_VALUE, oldestOf(p.plays)))
            }
            // Beside another account's rows the count is this account's own reads; alone, the archive's (taken at the start) and what is new
            mark = mark.copy(
                added = mark.added + if (beside) got.own else got.added,
                covered = mark.covered + got.covered,
                newest = maxOf(mark.newest, got.newest),
                pages = p.pages,
                next = page + 1,
                done = page >= p.pages,
                at = nowS,
            )
            if (mark.done) close(0)
            store.setMark(mark)
            progress(LastfmProgress(page = minOf(page, p.pages), pages = p.pages, added = mark.added))
            if (!mark.done) delay(gapMs)
        }

        // Newer than the newest kept, and the two weeks below it: every page of that window, newest first
        if (mark.newest != 0L && mark.to < nowS) {
            val since = mark.newest
            val from = maxOf(1L, since - LATE_S)
            var newest = since
            var covered = 0
            var pages = 0
            // The oldest play this window has read (a page skipped next lies below it), and its hole whose lower end isn't known yet
            var top = nowS
            var open = -1
            var page = 1
            while (true) {
                if (hole(page, true)) {
                    val s = mark.stuck!!
                    // Kept down to the window's lower end, until a page below with plays says where it ends
                    if (open < 0) {
                        withHoles(mark.holes.orEmpty() + LastfmMark.Hole(from, top, nowS))
                        open = mark.holes!!.size - 1
                    }
                    mark = mark.copy(stuck = null)
                    store.setMark(mark)
                    // The window's tail gone: the top-up is complete
                    if (s.tail) break
                    val last = maxOf(pages, s.pages ?: 0)
                    if (last != 0 && page >= last) break
                    page++
                    continue
                }
                val p = try {
                    steadily(read, page, nowS, from, pages, busyMs)
                } catch (e: LastfmError) {
                    stuckOn(e, page, true, nowS, from)
                    throw e
                }
                if (mark.stuck?.let { it.topup && it.page == page } == true) mark = mark.copy(stuck = null)
                pages = maxOf(pages, p.pages)
                val got = take(p.plays, since)
                newest = maxOf(newest, got.newest)
                covered += got.covered
                if (p.plays.isNotEmpty()) {
                    if (open >= 0) {
                        val at = open
                        withHoles(mark.holes!!.mapIndexed { j, h -> if (j == at) h.copy(from = got.newest) else h })
                        open = -1
                    }
                    top = minOf(top, oldestOf(p.plays))
                }
                // What it added is saved every page; its window (newest, to) and what Spotify covered only once all of it is in, so
                // a top-up cut short reads the same window again, finds what it wrote kept already, and counts the covered ones once
                mark = mark.copy(added = mark.added + got.added)
                store.setMark(mark)
                progress(LastfmProgress(page = page, pages = p.pages, added = mark.added))
                if (page >= p.pages) break
                delay(gapMs)
                page++
            }
            mark = mark.copy(newest = newest, covered = mark.covered + covered, to = nowS, at = nowS)
            store.setMark(mark)
            // Then the holes found before this run: after what is new, so new scrobbles never wait on a hole
            recheckHoles()
        }
        return mark
    }

    /**
     * lastfm.ts `removeScrobbles`: every scrobble out of the archive again, the mark and the lengths pass's own; the export's
     * plays stay. The mark goes first (the web deletes it after): a removal cut short then leaves rows with no mark, which the
     * next run reads again from page 1, kept once each, never an "all in" mark over rows that are gone, whose top-up would read
     * only what is newer.
     */
    suspend fun remove(store: ScrobbleStore): Int {
        store.clearMark()
        return store.deleteScrobbles()
    }
}
