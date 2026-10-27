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
    /** The newest scrobble kept (epoch s): a top-up reads from just after it. */
    val newest: Long,
    /** Scrobbles kept, and ones another source already had. */
    val added: Int,
    val covered: Int,
    /** When it last ran (epoch s, the run's start). */
    val at: Long,
)

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

/** lastfm.ts `LastfmError`: a page that couldn't be read, and why. */
class LastfmError(val kind: LastfmFailure) : Exception("Last.fm: $kind")

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

    /** A page's rows, written. */
    suspend fun add(rows: List<ArchivePlay>)

    /** Every scrobble out; returns how many went. */
    suspend fun deleteScrobbles(): Int
}

/**
 * Your Last.fm scrobbles into Your record (stash-player `src/lib/spotify/lastfm.ts`, spec §7), page by page, newest first, into
 * the same archive as the Spotify export. Resumable: where it stopped is kept ([LastfmMark]), so a run stopped (by the person,
 * the system, or the app's death) carries on from that page. Once the whole history is in, a top-up reads only what is newer
 * than the newest scrobble kept. Paced to stay well inside Last.fm's limits: one page at a time, [PAGE_GAP_MS] apart, and a
 * busy answer waits [BUSY_WAIT_MS] times the attempt before asking again, [BUSY_TRIES] attempts at most.
 */
object ImportScrobbles {
    const val PAGE_GAP_MS = 350L
    const val BUSY_WAIT_MS = 10_000L
    const val BUSY_TRIES = 6

    /** A page, asked again while Last.fm is busy (lastfm.ts `patiently`). */
    private suspend fun patiently(read: PageReader, page: Int, to: Long, from: Long?, wait: Long): HistoryPage {
        var i = 0
        while (true) {
            try {
                return read.read(page, to, from)
            } catch (e: LastfmError) {
                if (e.kind != LastfmFailure.BUSY || i >= BUSY_TRIES - 1) throw e
                delay(wait * (i + 1))
            }
            i++
        }
    }

    /**
     * lastfm.ts `importScrobbles`: [user]'s scrobbles into [store], the rest of the whole history, then (or once that's done)
     * what is newer. Each page is planned against the archive as it is and written before the next is asked for; the mark is
     * saved after its page's plays. The caller holds the archive's write lock for the whole run (nothing else writes meanwhile).
     * A cancelled coroutine stops at the next page or pause; what was written and marked stays.
     */
    suspend fun run(
        store: ScrobbleStore,
        read: PageReader,
        user: String,
        nowS: Long,
        progress: (LastfmProgress) -> Unit = {},
        gapMs: Long = PAGE_GAP_MS,
        busyMs: Long = BUSY_WAIT_MS,
    ): LastfmMark {
        val prev = store.mark()
        var mark = if (prev != null && prev.user == user) {
            prev
        } else {
            LastfmMark(user = user, to = nowS, next = 1, pages = 0, done = false, newest = 0, added = 0, covered = 0, at = 0)
        }
        // Known once: scrobbles never count towards a song's length, so the run's own writes can't change them
        var lengths: Map<String, Long>? = null
        suspend fun take(plays: List<Scrobble>) {
            val plan = Scrobbles.plan(store, plays, lengths ?: store.knownLengths().also { lengths = it })
            store.add(plan.add)
            mark = mark.copy(
                added = mark.added + plan.add.size,
                covered = mark.covered + plan.covered,
                newest = maxOf(mark.newest, plays.maxOfOrNull { it.uts } ?: mark.newest),
            )
        }

        // The whole history, from where it stopped (pages are newest first, below a fixed `to`)
        while (!mark.done) {
            val p = patiently(read, mark.next, mark.to, null, busyMs)
            take(p.plays)
            val page = mark.next
            mark = mark.copy(pages = p.pages, next = page + 1, done = p.plays.isEmpty() || page >= p.pages, at = nowS)
            store.setMark(mark)
            progress(LastfmProgress(page = minOf(page, p.pages), pages = p.pages, added = mark.added))
            if (!mark.done) delay(gapMs)
        }

        // Newer than the newest kept: every page of the window above it, newest first
        if (mark.newest != 0L && mark.to < nowS) {
            val from = mark.newest + 1
            var page = 1
            while (true) {
                val p = patiently(read, page, nowS, from, busyMs)
                take(p.plays)
                progress(LastfmProgress(page = page, pages = p.pages, added = mark.added))
                if (p.plays.isEmpty() || page >= p.pages) break
                delay(gapMs)
                page++
            }
            mark = mark.copy(to = nowS, at = nowS)
            store.setMark(mark)
        }
        return mark
    }

    /** lastfm.ts `removeScrobbles`: every scrobble out of the archive again, and the mark; the export's plays stay. */
    suspend fun remove(store: ScrobbleStore): Int {
        val n = store.deleteScrobbles()
        store.clearMark()
        return n
    }
}
