package com.stash.core.data.listening

import androidx.annotation.VisibleForTesting
import com.stash.core.data.db.dao.DayCount
import com.stash.core.data.db.dao.HistoryRow
import com.stash.core.data.db.dao.HourCount
import com.stash.core.data.db.dao.ListeningHistoryDao
import com.stash.core.data.db.dao.ListeningTotals
import com.stash.core.data.db.dao.ObsessionRow
import com.stash.core.data.db.dao.TopAlbumRow
import com.stash.core.data.db.dao.TopArtistRow
import com.stash.core.data.db.dao.TopTrackRow
import kotlinx.coroutines.flow.Flow
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Where the next History page starts: just older than this listen. */
data class HistoryCursor(val startedAt: Long, val eventId: Long)

/** One History page and the cursor for the next, null when this was the last page. */
data class HistoryPage(val rows: List<HistoryRow>, val next: HistoryCursor?)

/**
 * The single entry point the "Your listening" screens (History, Top songs, Stats, Your Year) read
 * through. It owns "today" (the phone's clock and zone), turns a [ListeningRange] into days, and
 * pages History. What counts: every listen, this phone's and other devices' (see
 * [ListeningHistoryDao]); none of this feeds mixes or play counts.
 *
 * Ranges are resolved once by the caller ([dayRange]) and passed to the observe calls, so a screen
 * that stays open past midnight decides itself when to move "today".
 */
@Singleton
class ListeningHistoryRepository @VisibleForTesting internal constructor(
    private val dao: ListeningHistoryDao,
    private val nowMs: () -> Long,
    private val zone: () -> ZoneId,
) {

    @Inject
    constructor(dao: ListeningHistoryDao) : this(dao, System::currentTimeMillis, { ZoneId.systemDefault() })

    /** Today's epoch-day where the phone is now. */
    fun today(): Long = ListeningDay.today(nowMs(), zone())

    suspend fun firstListenDay(): Long? = dao.firstListenDay()

    /** The days [range] covers today ([ListeningRange.AllTime] starts at the first listen). */
    suspend fun dayRange(range: ListeningRange): DayRange {
        val first = if (range == ListeningRange.AllTime) dao.firstListenDay() else null
        return range.toDayRange(today(), first)
    }

    /** The calendar year [year], up to today for the year in progress. */
    fun yearRange(year: Int): DayRange = ListeningRange.year(year, today())

    // ── History ──────────────────────────────────────────────────────

    /**
     * The History page after [cursor] (null = the newest listens), optionally filtered by [query]
     * on title, artist or album (taken literally, case-insensitive for ASCII).
     */
    suspend fun historyPage(cursor: HistoryCursor?, query: String? = null, limit: Int = PAGE_SIZE): HistoryPage {
        require(limit > 0) { "limit must be positive" }
        val rows = dao.historyPage(
            beforeStartedAt = cursor?.startedAt ?: Long.MAX_VALUE,
            beforeId = cursor?.eventId ?: Long.MAX_VALUE,
            pattern = ListeningHistoryDao.likePattern(query),
            limit = limit,
        )
        val next = if (rows.size < limit) null else rows.last().event.let { HistoryCursor(it.startedAt, it.id) }
        return HistoryPage(rows, next)
    }

    // ── Live aggregates ──────────────────────────────────────────────

    fun observeDayCounts(range: DayRange): Flow<List<DayCount>> = dao.observeDayCounts(range.fromDay, range.toDay)

    fun observeTopTracks(range: DayRange, limit: Int = TOP_LIMIT): Flow<List<TopTrackRow>> =
        dao.observeTopTracks(range.fromDay, range.toDay, limit)

    fun observeTopArtists(range: DayRange, limit: Int = TOP_LIMIT): Flow<List<TopArtistRow>> =
        dao.observeTopArtists(range.fromDay, range.toDay, limit)

    fun observeTopAlbums(range: DayRange, limit: Int = TOP_LIMIT): Flow<List<TopAlbumRow>> =
        dao.observeTopAlbums(range.fromDay, range.toDay, limit)

    fun observeTotals(range: DayRange): Flow<ListeningTotals> = dao.observeTotals(range.fromDay, range.toDay)

    fun observeHourHistogram(range: DayRange): Flow<List<HourCount>> =
        dao.observeHourHistogram(range.fromDay, range.toDay)

    // ── Your Year ────────────────────────────────────────────────────

    suspend fun yearsWithListens(): List<Int> = dao.yearsWithListens()

    suspend fun newTracksIn(range: DayRange, limit: Int = TOP_LIMIT): List<TopTrackRow> =
        dao.newTracksIn(range.fromDay, range.toDay, limit)

    suspend fun countNewTracksIn(range: DayRange): Int = dao.countNewTracksIn(range.fromDay, range.toDay)

    suspend fun obsession(range: DayRange): ObsessionRow? = dao.obsession(range.fromDay, range.toDay)

    // ── Privacy (this phone only) ────────────────────────────────────

    suspend fun deleteListen(eventId: Long): Int = dao.deleteEvent(eventId)

    suspend fun deleteDay(day: Long): Int = dao.deleteDay(day)

    suspend fun deleteRange(range: DayRange): Int = dao.deleteBetween(range.fromDay, range.toDay)

    suspend fun deleteAll(): Int = dao.deleteAll()

    companion object {
        /** History page size; the UI prefetches when it nears the end. */
        const val PAGE_SIZE = 200

        /** Top lists stop here. */
        const val TOP_LIMIT = 100
    }
}
