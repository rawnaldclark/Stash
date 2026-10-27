package com.stash.feature.record.lastfm

import com.stash.feature.record.archive.ArchivePlay
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
    /** A page before the last that listed nothing, and how many runs in a row stopped on it (the whole read's, or a top-up's). */
    val stuck: Stuck? = null,
    /** Pages skipped as holes ([ImportScrobbles.STUCK_RUNS]): their scrobbles, if any, never came in. */
    val skipped: Int? = null,
) {
    /** lastfm.ts `stuck`: [page] stopped [runs] runs in a row as a page before the last that listed nothing; [topup]: in a top-up. */
    data class Stuck(val page: Int, val runs: Int, val topup: Boolean = false)
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

/**
 * lastfm.ts `LastfmError`: a page (or a length) that couldn't be read, and why. [empty]: busy because a page before the last listed
 * nothing (a hole in the paging, not an outage: counted, [ImportScrobbles.STUCK_RUNS]). [limited]: a rate limit or the daily cap,
 * not to be asked again before [retryAt] (epoch ms): never waited out in place.
 */
class LastfmError(
    val kind: LastfmFailure,
    val empty: Boolean = false,
    val limited: Boolean = false,
    val retryAt: Long? = null,
) : Exception("Last.fm: $kind")

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
     * times over minutes, is a hole in Last.fm's paging, not a stumble: the next run skips it (and says so in the mark) so the
     * import can finish. Three runs are three separate tries at different times; more would leave the import stuck for the
     * listener, who taps Carry on each time.
     */
    const val STUCK_RUNS = 3

    /**
     * lastfm.ts `patiently`: [ask], asked again while Last.fm is busy (at most [tries] times, waiting [wait] × the attempt between).
     * A limit ([LastfmError.limited]) is never waited out here: it says when to come back.
     */
    internal suspend fun <T> patiently(wait: Long, tries: Int = BUSY_TRIES, ask: suspend () -> T): T {
        var i = 0
        while (true) {
            try {
                return ask()
            } catch (e: LastfmError) {
                if (e.kind != LastfmFailure.BUSY || e.limited || i >= tries - 1) throw e
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
        if (prev.done && prev.newest == 0L) return prev.copy(to = nowS, next = 1, pages = 0, done = false, added = added, stuck = null)
        return prev.copy(added = added)
    }

    /** What one page brought: rows written, the page's scrobbles the archive holds now, ones Spotify had, and its newest start. */
    private class Took(val added: Int, val own: Int, val covered: Int, val newest: Long)

    /**
     * lastfm.ts `importScrobbles`: [user]'s scrobbles into [store], the rest of the whole history, then (or once that's done)
     * what is newer. Each page is planned against the archive as it is and written before the next is asked for; the mark is
     * saved after every page's plays, in the whole read and in a top-up alike. Only a page at or past Last.fm's last ends a read:
     * an empty page before it is Last.fm stumbling, which [LastfmPages] answers as busy ([LastfmError.empty]); one that stays so
     * [STUCK_RUNS] runs in a row is skipped ([LastfmMark.skipped]).
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

        /** The run stopped on an empty page before the last: one more run for it ([topup]: in a top-up), kept with the mark. */
        suspend fun stuckOn(e: LastfmError, page: Int, topup: Boolean) {
            if (!e.empty) return
            val s = mark.stuck
            val runs = if (s != null && s.page == page && s.topup == topup) s.runs + 1 else 1
            mark = mark.copy(stuck = LastfmMark.Stuck(page, runs, topup))
            store.setMark(mark)
        }

        /** [page] has stopped [STUCK_RUNS] runs in a row: a hole, skipped. */
        fun hole(page: Int, topup: Boolean) = mark.stuck?.let { it.page == page && it.topup == topup && it.runs >= STUCK_RUNS } == true

        // The whole history, from where it stopped (pages are newest first, below a fixed `to`)
        while (!mark.done) {
            val page = mark.next
            if (hole(page, false)) {
                mark = mark.copy(stuck = null, skipped = (mark.skipped ?: 0) + 1, next = page + 1, done = page >= mark.pages, at = nowS)
                store.setMark(mark)
                continue
            }
            val p = try {
                steadily(read, page, mark.to, null, mark.pages, busyMs)
            } catch (e: LastfmError) {
                stuckOn(e, page, false)
                throw e
            }
            if (mark.stuck?.let { !it.topup && it.page == page } == true) mark = mark.copy(stuck = null)
            val beside = (mark.base ?: 0) > 0
            val got = take(p.plays, own = beside)
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
            var page = 1
            while (true) {
                if (hole(page, true)) {
                    mark = mark.copy(stuck = null, skipped = (mark.skipped ?: 0) + 1)
                    store.setMark(mark)
                    if (pages != 0 && page >= pages) break
                    page++
                    continue
                }
                val p = try {
                    steadily(read, page, nowS, from, pages, busyMs)
                } catch (e: LastfmError) {
                    stuckOn(e, page, true)
                    throw e
                }
                if (mark.stuck?.let { it.topup && it.page == page } == true) mark = mark.copy(stuck = null)
                pages = maxOf(pages, p.pages)
                val got = take(p.plays, since)
                newest = maxOf(newest, got.newest)
                covered += got.covered
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
