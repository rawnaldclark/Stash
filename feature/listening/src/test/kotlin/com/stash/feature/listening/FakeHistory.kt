package com.stash.feature.listening

import com.stash.core.data.db.dao.DayCount
import com.stash.core.data.db.dao.HistoryRow
import com.stash.core.data.db.dao.HourCount
import com.stash.core.data.db.dao.ListeningTotals
import com.stash.core.data.db.dao.ObsessionRow
import com.stash.core.data.db.dao.TopAlbumRow
import com.stash.core.data.db.dao.TopArtistRow
import com.stash.core.data.db.dao.TopTrackRow
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.listening.DayRange
import com.stash.core.data.listening.HistoryCursor
import com.stash.core.data.listening.HistoryPage
import com.stash.core.data.listening.ListeningDay
import com.stash.core.data.listening.ListeningHistoryRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * An in-memory history behind a mocked [ListeningHistoryRepository], paging with the same keyset
 * rules as the DAO (`started_at DESC, id DESC`), so ViewModel tests exercise real paging.
 */
internal class FakeHistory(val today: Long = TODAY) {
    private val rows = ArrayList<HistoryRow>()
    private val version = MutableStateFlow(0)
    private var nextId = 1L

    /** While set, historyPage waits for it (to hold a read in flight). */
    var pageGate: CompletableDeferred<Unit>? = null

    /** While set, historyPageNewer waits for it. */
    var newerGate: CompletableDeferred<Unit>? = null

    /** The next historyPage call throws (a failed read). */
    var failNextPage = false

    /** Every historyPage / historyPageNewer call, as (cursor, query). */
    val pageCalls = ArrayList<Pair<HistoryCursor?, String?>>()

    fun track(
        id: Long,
        title: String = "Song $id",
        artist: String = "Artist $id",
        filePath: String? = null,
        album: String = "",
        durationMs: Long = 200_000,
        isrc: String? = null,
    ) = TrackEntity(
        id = id, title = title, artist = artist, album = album, durationMs = durationMs, isrc = isrc,
        canonicalTitle = title.lowercase(), canonicalArtist = artist.lowercase(), filePath = filePath,
    )

    /** Adds a listen of [track] on [day] at [minute]; returns its row. */
    fun add(track: TrackEntity, day: Long, minute: Int, origin: String? = null, listenedMs: Long? = null): HistoryRow {
        val startedAt = day * ListeningDay.MS_PER_DAY + minute * 60_000L
        val row = HistoryRow(
            ListeningEventEntity(
                id = nextId++, trackId = track.id, startedAt = startedAt, completedAt = startedAt + 30_000,
                localDay = day, localMinute = minute, originDevice = origin, listenedMs = listenedMs,
            ),
            track,
        )
        rows += row
        version.value++
        return row
    }

    /** [perDay] listens on each of the [days] days ending today, a different song each. */
    fun fill(days: Int, perDay: Int) {
        for (d in days - 1 downTo 0) for (i in 0 until perDay) add(track(1000L + i), today - d, 60 + i)
    }

    private val newestFirst = compareByDescending<HistoryRow> { it.event.startedAt }.thenByDescending { it.event.id }

    private fun matches(row: HistoryRow, query: String?): Boolean =
        query.isNullOrBlank() || row.track.title.contains(query.trim(), ignoreCase = true) ||
            row.track.artist.contains(query.trim(), ignoreCase = true)

    private fun HistoryRow.isBefore(c: HistoryCursor) =
        event.startedAt < c.startedAt || (event.startedAt == c.startedAt && event.id < c.eventId)

    private fun HistoryRow.isAfter(c: HistoryCursor) =
        event.startedAt > c.startedAt || (event.startedAt == c.startedAt && event.id > c.eventId)

    fun page(cursor: HistoryCursor?, query: String?, limit: Int): HistoryPage {
        pageCalls += cursor to query
        val list = rows.sortedWith(newestFirst).filter { matches(it, query) && (cursor == null || it.isBefore(cursor)) }.take(limit)
        return HistoryPage(list, if (list.size < limit) null else list.last().event.let { HistoryCursor(it.startedAt, it.id) })
    }

    fun newer(cursor: HistoryCursor, query: String?, limit: Int): HistoryPage {
        pageCalls += cursor to query
        val asc = rows.sortedWith(newestFirst.reversed()).filter { matches(it, query) && it.isAfter(cursor) }.take(limit)
        return HistoryPage(asc.reversed(), if (asc.size < limit) null else asc.last().event.let { HistoryCursor(it.startedAt, it.id) })
    }

    /** A listen's time, the DAO's rule: the real time, else the song's length. */
    private fun HistoryRow.listened(): Long = event.listenedMs ?: track.durationMs

    fun dayCounts(): List<DayCount> =
        rows.groupBy { it.event.localDay!! }.map { (day, r) -> DayCount(day, r.size, r.sumOf { it.listened() }) }.sortedBy { it.day }

    private fun inRange(range: DayRange) = rows.filter { it.event.localDay!! in range }

    fun totals(range: DayRange): ListeningTotals {
        val r = inRange(range)
        return ListeningTotals(
            plays = r.size,
            songs = r.distinctBy { it.track.id }.size,
            artists = r.distinctBy { it.track.artist.lowercase() }.size,
            days = r.distinctBy { it.event.localDay }.size,
            listenedMs = r.sumOf { it.listened() },
            estimatedPlays = r.count { it.event.listenedMs == null },
        )
    }

    private val topOrder = compareByDescending<TopTrackRow> { it.plays }.thenByDescending { it.lastPlayedAt }.thenBy { it.track.id }

    private fun trackRows(r: List<HistoryRow>): List<TopTrackRow> =
        r.groupBy { it.track.id }.map { (_, g) ->
            TopTrackRow(g.first().track, g.size, g.maxOf { it.event.startedAt }, g.sumOf { it.listened() })
        }.sortedWith(topOrder)

    fun topTracks(range: DayRange, limit: Int): List<TopTrackRow> = trackRows(inRange(range)).take(limit)

    fun topArtists(range: DayRange, limit: Int): List<TopArtistRow> =
        inRange(range).groupBy { it.track.artist.lowercase() }.map { (_, g) ->
            val latest = g.maxBy { it.event.startedAt }
            TopArtistRow(
                artist = latest.track.artist, plays = g.size, songs = g.distinctBy { it.track.id }.size,
                lastPlayedAt = latest.event.startedAt, albumArtUrl = latest.track.albumArtUrl,
                albumArtPath = latest.track.albumArtPath, listenedMs = g.sumOf { it.listened() },
            )
        }.sortedWith(compareByDescending<TopArtistRow> { it.plays }.thenByDescending { it.lastPlayedAt }).take(limit)

    fun topAlbums(range: DayRange, limit: Int): List<TopAlbumRow> =
        inRange(range).filter { it.track.album.isNotEmpty() }
            .groupBy { it.track.album.lowercase() to it.track.artist.lowercase() }.map { (_, g) ->
                val latest = g.maxBy { it.event.startedAt }
                TopAlbumRow(
                    album = latest.track.album, artist = latest.track.artist, plays = g.size,
                    songs = g.distinctBy { it.track.id }.size, lastPlayedAt = latest.event.startedAt,
                    albumArtUrl = latest.track.albumArtUrl, albumArtPath = latest.track.albumArtPath,
                    listenedMs = g.sumOf { it.listened() },
                )
            }.sortedWith(compareByDescending<TopAlbumRow> { it.plays }.thenByDescending { it.lastPlayedAt }).take(limit)

    fun hours(range: DayRange): List<HourCount> =
        inRange(range).groupBy { it.event.localMinute!! / 60 }.map { (h, g) -> HourCount(h, g.size) }.sortedBy { it.hour }

    private fun newRows(range: DayRange): List<HistoryRow> {
        val before = rows.filter { it.event.localDay!! < range.fromDay }.map { it.track.id }.toSet()
        return inRange(range).filter { it.track.id !in before }
    }

    fun obsession(range: DayRange): ObsessionRow? =
        inRange(range).groupBy { it.event.localDay!! to it.track.id }
            .maxWithOrNull(compareBy<Map.Entry<Pair<Long, Long>, List<HistoryRow>>> { it.value.size }.thenBy { it.key.first })
            ?.let { (k, g) -> ObsessionRow(g.first().track, k.first, g.size) }

    fun delete(predicate: (HistoryRow) -> Boolean): Int {
        val n = rows.count(predicate)
        rows.removeAll(predicate)
        if (n > 0) version.value++
        return n
    }

    fun recent(limit: Int): List<TopTrackRow> =
        rows.sortedWith(newestFirst).distinctBy { it.track.id }.take(limit)
            .map { r -> TopTrackRow(r.track, rows.count { it.track.id == r.track.id }, r.event.startedAt) }

    val size: Int get() = rows.size

    fun all(): List<HistoryRow> = rows.sortedWith(newestFirst)

    /** The repository the ViewModels see, answering from this fake. */
    fun repository(): ListeningHistoryRepository = mockk<ListeningHistoryRepository>().also { repo ->
        every { repo.today() } returns today
        coEvery { repo.historyPage(any(), any(), any()) } coAnswers {
            pageGate?.await()
            if (failNextPage) {
                failNextPage = false
                throw IllegalStateException("read failed")
            }
            page(firstArg(), secondArg(), thirdArg())
        }
        coEvery { repo.historyPageNewer(any(), any(), any()) } coAnswers {
            newerGate?.await()
            newer(firstArg(), secondArg(), thirdArg())
        }
        every { repo.observeDayCounts(any()) } answers {
            val range = firstArg<DayRange>()
            version.map { dayCounts().filter { it.day in range } } as Flow<List<DayCount>>
        }
        every { repo.observeRecentTracks(any()) } answers { version.map { recent(firstArg()) } }
        coEvery { repo.listensOn(any()) } answers {
            val day = firstArg<Long>()
            rows.filter { it.event.localDay == day }.sortedWith(newestFirst.reversed())
        }
        coEvery { repo.firstListenDay() } answers { rows.minOfOrNull { it.event.localDay!! } }
        every { repo.observeTotals(any()) } answers { val r = firstArg<DayRange>(); version.map { totals(r) } }
        every { repo.observeTopTracks(any(), any()) } answers {
            val r = firstArg<DayRange>(); val n = secondArg<Int>(); version.map { topTracks(r, n) }
        }
        every { repo.observeTopArtists(any(), any()) } answers {
            val r = firstArg<DayRange>(); val n = secondArg<Int>(); version.map { topArtists(r, n) }
        }
        every { repo.observeTopAlbums(any(), any()) } answers {
            val r = firstArg<DayRange>(); val n = secondArg<Int>(); version.map { topAlbums(r, n) }
        }
        every { repo.observeHourHistogram(any()) } answers { val r = firstArg<DayRange>(); version.map { hours(r) } }
        coEvery { repo.obsession(any()) } answers { obsession(firstArg()) }
        coEvery { repo.countNewTracksIn(any()) } answers { newRows(firstArg()).distinctBy { it.track.id }.size }
        coEvery { repo.newTracksIn(any(), any()) } answers { trackRows(newRows(firstArg())).take(secondArg()) }
        coEvery { repo.deleteListen(any()) } answers { val id = firstArg<Long>(); delete { it.event.id == id } }
        coEvery { repo.deleteDay(any()) } answers { val day = firstArg<Long>(); delete { it.event.localDay == day } }
    }

    companion object {
        /** 2026-10-18. */
        const val TODAY = 20_744L
    }
}
