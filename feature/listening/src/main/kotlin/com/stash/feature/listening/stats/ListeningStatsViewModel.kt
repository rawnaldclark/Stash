package com.stash.feature.listening.stats

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.dao.ArtistImageDao
import com.stash.core.data.db.dao.DayCount
import com.stash.core.data.db.dao.ListeningTotals
import com.stash.core.data.db.dao.ObsessionRow
import com.stash.core.data.db.dao.TopAlbumRow
import com.stash.core.data.db.dao.TopArtistRow
import com.stash.core.data.db.dao.TopTrackRow
import com.stash.core.data.db.entity.ArtistImageEntity
import com.stash.core.data.listening.DayRange
import com.stash.core.data.listening.ListeningHistoryRepository
import com.stash.core.data.listening.ListeningRange
import com.stash.core.data.listening.ListeningStatsCalculator
import com.stash.core.data.listening.ListeningStatsCalculator.Bucket
import com.stash.core.data.listening.ListeningStatsCalculator.Granularity
import com.stash.core.data.listening.ListeningStatsCalculator.HeatCell
import com.stash.core.data.listening.ListeningStatsCalculator.Streak
import com.stash.core.data.mapper.toDomain
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.PlaybackSource
import com.stash.feature.listening.PlayPlan
import com.stash.feature.listening.planTopSongs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import javax.inject.Inject

/** A top artist as the screens show it: the numbers, plus a photo when the Library has one. */
data class TopArtistUi(
    val name: String,
    val plays: Int,
    val songs: Int,
    val listenedMs: Long,
    /** The artist's photo (artist_images), else null: fall back to [artPath] / [artUrl]. */
    val photoUrl: String?,
    val artPath: String?,
    val artUrl: String?,
)

/** Everything "Your listening" shows for one range. */
data class ListeningStatsState(
    val range: ListeningRange = ListeningInsights.DEFAULT_RANGE,
    /** The days [range] covers; null while loading. */
    val days: DayRange? = null,
    val today: Long = 0,
    val loading: Boolean = true,
    val totals: ListeningTotals = ListeningTotals.EMPTY,
    /** Listening time vs the period before, in percent; null = not shown (see [ListeningInsights.comparablePrevious]). */
    val deltaPercent: Int? = null,
    /** The hero chart: listening time per day / week / month across the range. */
    val buckets: List<Bucket> = emptyList(),
    val granularity: Granularity = Granularity.DAY,
    /** Consecutive days ending today (or yesterday), whatever the range. */
    val currentStreak: Int = 0,
    /** The longest run of listening days within the range. */
    val longestStreak: Streak? = null,
    /** The range's day with the most listens. */
    val busiestDay: DayCount? = null,
    val topArtists: List<TopArtistUi> = emptyList(),
    /** The range's top songs (up to 100): the screen lists five, Play plays them all. */
    val topSongs: List<TopTrackRow> = emptyList(),
    val topAlbums: List<TopAlbumRow> = emptyList(),
    /** Listens per hour 0..23. */
    val hourly: List<Int> = List(24) { 0 },
    val peakHour: Int? = null,
    val daySplit: DaySplit? = null,
    /** Week columns for ranges of 3 months or more, else empty. */
    val heatmap: List<List<HeatCell?>> = emptyList(),
    /** The song played most times in one day, when that's 3 times or more. */
    val obsession: ObsessionRow? = null,
    /** Songs first heard in the range (0 when the range is the whole history: everything is new). */
    val newSongs: Int = 0,
    /** The most played of [newSongs]. */
    val bestNewFind: TopTrackRow? = null,
    val firstListenDay: Long? = null,
) {
    /** Too few listens for charts: the screen shows the total and one line. */
    val coldStart: Boolean get() = !loading && totals.plays < ListeningInsights.COLD_START_PLAYS

    /** Some listening time is estimated from song length: "about" and a footnote. */
    val estimated: Boolean get() = totals.estimatedPlays > 0

    /** The range reaches back to the first listen (or there is none): "new to you" means nothing. */
    val coversWholeHistory: Boolean
        get() = firstListenDay == null || days == null || firstListenDay >= days.fromDay
}

/**
 * Feeds "Your listening" (YourListeningRoute): totals, the chart, top artists, songs and albums,
 * the clock, days and highlights for the chosen range, live (every new or removed listen
 * re-reads; each read is tens of milliseconds on the covering day index, see ListeningHistorySql).
 *
 * The range comes from the route ([ARG_RANGE]) and is kept across process death. "Today" is read
 * when the screen opens and again on [refreshToday] (the screen calls it on resume).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ListeningStatsViewModel @VisibleForTesting internal constructor(
    private val savedStateHandle: SavedStateHandle,
    private val history: ListeningHistoryRepository,
    private val artistImages: ArtistImageDao,
    private val playerRepository: PlayerRepository,
    private val connectivityMonitor: ConnectivityMonitor,
    private val firstDayOfWeek: DayOfWeek,
) : ViewModel() {

    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        history: ListeningHistoryRepository,
        artistImages: ArtistImageDao,
        playerRepository: PlayerRepository,
        connectivityMonitor: ConnectivityMonitor,
    ) : this(
        savedStateHandle, history, artistImages, playerRepository, connectivityMonitor,
        ListeningStatsCalculator.firstDayOfWeek(),
    )

    private val _range = MutableStateFlow(ListeningInsights.rangeOf(savedStateHandle.get<String>(ARG_RANGE)))

    /** The chosen range, for the chips (moves at once; [state] follows when the range is read). */
    val range: StateFlow<ListeningRange> = _range.asStateFlow()

    private val today = MutableStateFlow(history.today())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** One-off messages for a toast ("None of your top songs are downloaded…"). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val state: StateFlow<ListeningStatsState> = combine(_range, today) { r, t -> r to t }
        .flatMapLatest { (r, t) -> flow { emitAll(load(r, t)) } }
        .catch { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "listening stats failed", e)
            emit(ListeningStatsState(range = _range.value, today = today.value, loading = false))
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ListeningStatsState(range = _range.value, today = today.value),
        )

    fun selectRange(range: ListeningRange) {
        if (range !in ListeningInsights.RANGES) return
        savedStateHandle[ARG_RANGE] = range.key
        _range.value = range
    }

    /** Re-reads today (after midnight, or when the screen comes back). */
    fun refreshToday() {
        today.value = history.today()
    }

    /** Play on the Top songs card: the range's top songs from #1. */
    fun playTopSongs() = playTop(0, tapped = false)

    /** A tapped top song: the list from that song on. */
    fun playTopSong(index: Int) = playTop(index, tapped = true)

    private fun playTop(index: Int, tapped: Boolean) {
        val s = state.value
        val tracks = s.topSongs.map { it.track.toDomain() }
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            when (val plan = planTopSongs(tracks, index, connectivityMonitor.isConnected(), tapped)) {
                is PlayPlan.Play -> playerRepository.setQueue(
                    plan.tracks, plan.startIndex, PlaybackSource.Listening(ListeningInsights.topSongsLabel(s.range)),
                )
                is PlayPlan.Blocked -> _messages.tryEmit(plan.message)
            }
        }
    }

    // ── Loading ──────────────────────────────────────────────────────

    private suspend fun load(range: ListeningRange, today: Long): Flow<ListeningStatsState> {
        val first = history.firstListenDay()
        val days = range.toDayRange(today, first)
        val previous = ListeningInsights.comparablePrevious(range, today, first)
        // One day-count read covers the range and the current streak (which may reach back past it).
        val countsSpan = DayRange(minOf(first ?: days.fromDay, days.fromDay), maxOf(today, days.toDay))

        val counts = combine(
            history.observeTotals(days),
            previous?.let { history.observeTotals(it) } ?: flowOf(null),
            history.observeDayCounts(countsSpan),
        ) { totals, before, dayCounts -> Counts(totals, before, dayCounts) }

        val tops = combine(
            history.observeTopTracks(days),
            history.observeTopArtists(days),
            history.observeTopAlbums(days, ALBUMS_SHOWN),
            artistImages.observeAll(),
        ) { songs, artists, albums, photos -> Tops(songs, artistsWithPhotos(ListeningInsights.byMainArtist(artists), photos), albums) }

        // The highlights are one-off reads; read again whenever the range's listens change.
        val highlights = history.observeTotals(days)
            .map { it.plays }
            .distinctUntilChanged()
            .mapLatest { readHighlights(days, first) }

        return combine(counts, tops, history.observeHourHistogram(days), highlights) { c, t, hours, h ->
            val inRange = c.dayCounts.filter { it.day in days }
            val hourly = ListeningInsights.hourly(hours)
            ListeningStatsState(
                range = range,
                days = days,
                today = today,
                loading = false,
                totals = c.totals,
                deltaPercent = c.before?.let { ListeningInsights.deltaPercent(c.totals.listenedMs, it.listenedMs) },
                buckets = ListeningStatsCalculator.bucketSeries(inRange, days, firstDayOfWeek),
                granularity = ListeningStatsCalculator.granularityFor(days),
                currentStreak = ListeningStatsCalculator.currentStreak(c.dayCounts, today)?.days ?: 0,
                longestStreak = ListeningStatsCalculator.longestStreak(inRange),
                busiestDay = ListeningStatsCalculator.busiestDay(inRange),
                topArtists = t.artists.take(ARTISTS_SHOWN),
                topSongs = t.songs,
                topAlbums = t.albums,
                hourly = hourly.toList(),
                peakHour = ListeningInsights.peakHour(hourly),
                daySplit = ListeningInsights.daySplit(hourly),
                heatmap = if (days.length >= ListeningInsights.HEATMAP_MIN_DAYS) {
                    ListeningStatsCalculator.heatmapWeeks(inRange, days, firstDayOfWeek)
                } else {
                    emptyList()
                },
                obsession = h.obsession,
                newSongs = h.newSongs,
                bestNewFind = h.bestNewFind,
                firstListenDay = first,
            )
        }
    }

    private suspend fun readHighlights(days: DayRange, first: Long?): Highlights {
        return try {
            val obsession = history.obsession(days)?.takeIf { it.plays >= OBSESSION_MIN_PLAYS }
            // Over the whole history every song is "new": say nothing rather than something empty.
            val wholeHistory = first == null || first >= days.fromDay
            val newSongs = if (wholeHistory) 0 else history.countNewTracksIn(days)
            val best = if (newSongs > 0) history.newTracksIn(days, 1).firstOrNull() else null
            Highlights(obsession, newSongs, best)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "listening highlights failed", e)
            Highlights(null, 0, null)
        }
    }

    private data class Counts(val totals: ListeningTotals, val before: ListeningTotals?, val dayCounts: List<DayCount>)

    private data class Tops(val songs: List<TopTrackRow>, val artists: List<TopArtistUi>, val albums: List<TopAlbumRow>)

    private data class Highlights(val obsession: ObsessionRow?, val newSongs: Int, val bestNewFind: TopTrackRow?)

    companion object {
        private const val TAG = "ListeningStats"

        /** Nav argument: the range key ([ListeningRange.key]); absent = 30 days. */
        const val ARG_RANGE = "range"

        /** Artists on the screen's circle row. */
        const val ARTISTS_SHOWN = 10

        /** Albums on the screen's cover row. */
        const val ALBUMS_SHOWN = 10

        /** "On repeat" needs a song played at least this often in one day. */
        const val OBSESSION_MIN_PLAYS = 3
    }
}

/** [rows] with the Library's artist photos, matched by main act, ignoring case. */
internal fun artistsWithPhotos(
    rows: List<TopArtistRow>,
    photos: List<ArtistImageEntity>,
): List<TopArtistUi> {
    val byName = photos.asSequence().filter { it.imageUrl != null }.associate { it.artistName.lowercase() to it.imageUrl }
    return rows.map { r ->
        TopArtistUi(
            name = r.artist,
            plays = r.plays,
            songs = r.songs,
            listenedMs = r.listenedMs,
            photoUrl = byName[r.artist.lowercase()],
            artPath = r.albumArtPath,
            artUrl = r.albumArtUrl,
        )
    }
}
