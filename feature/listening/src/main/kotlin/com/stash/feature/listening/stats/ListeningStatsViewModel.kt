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
import com.stash.core.data.genre.ArtistGenreIndex
import com.stash.core.data.genre.ArtistGenreRepository
import com.stash.core.data.genre.ArtistWeight
import com.stash.core.data.genre.TopGenres
import com.stash.core.data.genre.TopGenresCalculator
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import com.stash.feature.listening.recap.PastRecap
import com.stash.feature.listening.recap.RecapAvailability
import com.stash.feature.listening.recap.RecapOffer
import com.stash.feature.listening.recap.readRecapOffer
import java.time.LocalDate
import kotlinx.coroutines.flow.transformLatest
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
) {
    /** The list key: one per main act, folded like the grouping. */
    val key: String get() = ListeningInsights.artistKey(name)
}

/** Everything "Your listening" shows for one range. */
data class ListeningStatsState(
    val range: ListeningRange = ListeningInsights.DEFAULT_RANGE,
    /** The days [range] covers; null while loading. */
    val days: DayRange? = null,
    val today: Long = 0,
    val loading: Boolean = true,
    val totals: ListeningTotals = ListeningTotals.EMPTY,
    /** Main acts heard in the range: the number the Top artists list has. */
    val artistCount: Int = 0,
    /** Listening time vs the period before, in percent; null = not shown (see [ListeningInsights.deltaWindows]). */
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
    /** The range's top songs, one row per song (up to 100): the screen lists five, Play plays them all. */
    val topSongs: List<TopTrackRow> = emptyList(),
    val topAlbums: List<TopAlbumRow> = emptyList(),
    /**
     * The range's top genres, weighted by each main act's listening time (Last.fm artist tags).
     * Shown only when [TopGenres.isShowable] (enough of the listening has a genre).
     */
    val topGenres: TopGenres = TopGenres.EMPTY,
    /** What [topGenres] is computed from (raw credits' listening time); not shown. */
    internal val genreWeights: List<ArtistWeight> = emptyList(),
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
 * Five queries per range: totals, day counts (chart, streaks, the delta), the three top lists
 * (one Room flow each) and the hour histogram; the highlights are one-off reads, repeated only when
 * the range's play count changes. Grouping and the derived numbers run off Main.
 *
 * The range comes from the route ([ARG_RANGE]) and is kept across process death. "Today" is read
 * when the screen opens and again on [refreshToday] (on resume and at midnight).
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
    computeDispatcher: CoroutineDispatcher,
    /** Cached artist genres ([ArtistGenreRepository.genreIndex]); none in tests that don't care. */
    private val genreIndex: Flow<ArtistGenreIndex> = flowOf(ArtistGenreIndex.EMPTY),
    /** Asks for genres of newly listened artists ([ArtistGenreRepository.requestRefresh]). */
    requestGenreRefresh: () -> Unit = {},
) : ViewModel() {

    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        history: ListeningHistoryRepository,
        artistImages: ArtistImageDao,
        playerRepository: PlayerRepository,
        connectivityMonitor: ConnectivityMonitor,
        genres: ArtistGenreRepository,
    ) : this(
        savedStateHandle, history, artistImages, playerRepository, connectivityMonitor,
        ListeningStatsCalculator.firstDayOfWeek(), Dispatchers.Default,
        genres.genreIndex, genres::requestRefresh,
    )

    init {
        // Opening Your listening fetches genres for artists heard since the last run (a unique,
        // KEEP one-off job: cheap to ask for again).
        runCatching(requestGenreRefresh).onFailure { Log.w(TAG, "genre refresh request failed", it) }
    }

    private val _range = MutableStateFlow(ListeningInsights.rangeOf(savedStateHandle.get<String>(ARG_RANGE)))

    /** The chosen range, for the chips (moves at once; [state] follows when the range is read). */
    val range: StateFlow<ListeningRange> = _range.asStateFlow()

    private val today = MutableStateFlow(history.today())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** One-off messages for a toast ("None of these songs are downloaded…"). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val state: StateFlow<ListeningStatsState> = combine(_range, today) { r, t -> r to t }
        .flatMapLatest { (r, t) ->
            // The catch is per range: a failed read shows an empty screen for this range, and
            // the next range (or "today" moving on) reads again.
            flow { emitAll(load(r, t)) }.catch { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "listening stats failed", e)
                emit(ListeningStatsState(range = r, today = t, loading = false))
            }
        }
        .flowOn(computeDispatcher)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ListeningStatsState(range = _range.value, today = today.value),
        )

    /**
     * The recap the banner above the hero offers for the chosen range ("Watch your October recap";
     * the year for 12 months and All time), or null when none has enough listens.
     */
    val recapOffer: StateFlow<RecapOffer?> = combine(_range, today) { r, t -> r to t }
        .flatMapLatest { (r, t) ->
            // Read again as listens land (the week's play count moves), like Home's Recap card.
            history.observeTotals(DayRange(t - 6, t)).map { it.plays }.distinctUntilChanged().mapLatest {
                try {
                    readRecapOffer(history, RecapAvailability.bannerCandidates(LocalDate.ofEpochDay(t), r), t)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "recap offer failed", e)
                    null
                }
            }
        }
        .flowOn(computeDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * "Past recaps" at the bottom of the screen: every month with enough listens (newest first)
     * and every finished year, from one day-count read over the whole history (live).
     */
    val pastRecaps: StateFlow<List<PastRecap>> = today
        .flatMapLatest { t ->
            flow {
                val first = history.firstListenDay() ?: t
                emitAll(history.observeDayCounts(DayRange(minOf(first, t), t)))
            }.map { counts -> RecapAvailability.pastRecaps(counts.map { it.day to it.plays }, LocalDate.ofEpochDay(t)) }
        }
        .catch { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "past recaps failed", e)
            emit(emptyList())
        }
        .flowOn(computeDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
    fun playTopSongs() = playTop(null)

    /**
     * A tapped top song: the list from that song on. By song id, not position: the list may have
     * moved (a listen landed) between drawing the row and the tap.
     */
    fun playTopSong(trackId: Long) = playTop(trackId)

    private fun playTop(trackId: Long?) {
        val s = state.value
        val tracks = s.topSongs.map { it.track.toDomain() }
        if (tracks.isEmpty()) return
        val index = if (trackId == null) 0 else tracks.indexOfFirst { it.id == trackId }.takeIf { it >= 0 } ?: return
        viewModelScope.launch {
            when (val plan = planTopSongs(tracks, index, connectivityMonitor.isConnected(), tapped = trackId != null)) {
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
        val windows = ListeningInsights.deltaWindows(range, today, first)
        // One day-count read covers the range, the current streak (back to the first listen, the
        // same as Home) and the delta's earlier window.
        val countsFrom = minOf(first ?: days.fromDay, days.fromDay, windows?.previous?.fromDay ?: days.fromDay)
        val countsSpan = DayRange(countsFrom, maxOf(today, days.toDay))

        val counts = combine(history.observeTotals(days), history.observeDayCounts(countsSpan), ::Counts)
        val tops = combine(
            history.observeTopTracks(days, ListeningInsights.SCAN_LIMIT),
            history.observeTopArtists(days, ListeningInsights.ARTIST_SCAN_LIMIT),
            history.observeTopAlbums(days, ListeningInsights.SCAN_LIMIT),
            artistImages.observeAll(),
        ) { songs, artists, albums, photos ->
            val acts = ListeningInsights.byMainArtist(artists)
            Tops(
                songs = ListeningInsights.mergeSameSongs(songs).take(ListeningInsights.TOP_LIMIT),
                artists = artistsWithPhotos(acts.take(ARTISTS_SHOWN), photos),
                artistCount = acts.size,
                albums = ListeningInsights.byMainAlbum(albums).take(ALBUMS_SHOWN),
                // Every credit of the range (not just the shown ten) weighs in, by listening time.
                genreWeights = ListeningInsights.artistWeights(artists),
            )
        }

        val base = combine(counts, tops, history.observeHourHistogram(days)) { c, t, hours ->
            val inRange = c.dayCounts.filter { it.day in days }
            val hourly = ListeningInsights.hourly(hours)
            ListeningStatsState(
                range = range,
                days = days,
                today = today,
                loading = false,
                totals = c.totals,
                artistCount = t.artistCount,
                deltaPercent = windows?.let { ListeningInsights.deltaFromDays(c.dayCounts, it) },
                buckets = ListeningStatsCalculator.bucketSeries(inRange, days, firstDayOfWeek),
                granularity = ListeningStatsCalculator.granularityFor(days),
                currentStreak = ListeningStatsCalculator.currentStreak(c.dayCounts, today)?.days ?: 0,
                longestStreak = ListeningStatsCalculator.longestStreak(inRange),
                busiestDay = ListeningStatsCalculator.busiestDay(inRange),
                topArtists = t.artists,
                topSongs = t.songs,
                topAlbums = t.albums,
                genreWeights = t.genreWeights,
                hourly = hourly.toList(),
                peakHour = ListeningInsights.peakHour(hourly),
                daySplit = ListeningInsights.daySplit(hourly),
                heatmap = if (days.length >= ListeningInsights.HEATMAP_MIN_DAYS) {
                    ListeningStatsCalculator.heatmapWeeks(inRange, days, firstDayOfWeek)
                } else {
                    emptyList()
                },
                firstListenDay = first,
            )
        }

        // Genres join last, in their own small step: a genre-cache change (the worker filling
        // in artists) recomputes only this, not the songs/artists grouping above, and a failing
        // genre read leaves the rest of the screen standing (no genres instead).
        val safeIndex = genreIndex.catch { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "genre index failed", e)
            emit(ArtistGenreIndex.EMPTY)
        }
        val withGenres = combine(base, safeIndex) { s, index ->
            s.copy(topGenres = TopGenresCalculator.compute(s.genreWeights, index, GENRES_SHOWN))
        }

        // The highlights are one-off reads: kept while the range's play count stays the same,
        // read again when it changes (the screen shows the last ones meanwhile).
        var highlightsFor = -1
        var highlights = Highlights.NONE
        return withGenres.transformLatest { s ->
            emit(s.with(highlights))
            if (s.totals.plays != highlightsFor) {
                highlights = readHighlights(days, first)
                highlightsFor = s.totals.plays
                emit(s.with(highlights))
            }
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
            Highlights.NONE
        }
    }

    private fun ListeningStatsState.with(h: Highlights) =
        copy(obsession = h.obsession, newSongs = h.newSongs, bestNewFind = h.bestNewFind)

    private data class Counts(val totals: ListeningTotals, val dayCounts: List<DayCount>)

    private data class Tops(
        val songs: List<TopTrackRow>,
        val artists: List<TopArtistUi>,
        val artistCount: Int,
        val albums: List<TopAlbumRow>,
        val genreWeights: List<ArtistWeight>,
    )

    private data class Highlights(val obsession: ObsessionRow?, val newSongs: Int, val bestNewFind: TopTrackRow?) {
        companion object {
            val NONE = Highlights(null, 0, null)
        }
    }

    companion object {
        private const val TAG = "ListeningStats"

        /** Nav argument: the range key ([ListeningRange.key]); absent = 30 days. */
        const val ARG_RANGE = "range"

        /** Artists on the screen's circle row. */
        const val ARTISTS_SHOWN = 10

        /** Albums on the screen's cover row. */
        const val ALBUMS_SHOWN = 10

        /** Genre bars on the screen. */
        const val GENRES_SHOWN = 5

        /** "On repeat" needs a song played at least this often in one day. */
        const val OBSESSION_MIN_PLAYS = 3
    }
}

/** [rows] with the Library's artist photos, matched by main act, ignoring case. */
internal fun artistsWithPhotos(rows: List<TopArtistRow>, photos: List<ArtistImageEntity>): List<TopArtistUi> {
    val byName = HashMap<String, String>()
    for (p in photos) {
        val url = p.imageUrl ?: continue
        byName[ListeningInsights.artistKey(p.artistName)] = url
    }
    return rows.map { r ->
        TopArtistUi(
            name = r.artist,
            plays = r.plays,
            songs = r.songs,
            listenedMs = r.listenedMs,
            photoUrl = byName[ListeningInsights.artistKey(r.artist)],
            artPath = r.albumArtPath,
            artUrl = r.albumArtUrl,
        )
    }
}
