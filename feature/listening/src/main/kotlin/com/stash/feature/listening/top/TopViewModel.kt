package com.stash.feature.listening.top

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.dao.ArtistImageDao
import com.stash.core.data.db.dao.TopAlbumRow
import com.stash.core.data.db.dao.TopTrackRow
import com.stash.core.data.genre.ArtistGenreIndex
import com.stash.core.data.genre.ArtistGenreRepository
import com.stash.core.data.genre.GenreShare
import com.stash.core.data.genre.TopGenres
import com.stash.core.data.genre.TopGenresCalculator
import com.stash.core.data.listening.DayRange
import com.stash.core.data.listening.ListeningHistoryRepository
import com.stash.core.data.listening.ListeningRange
import com.stash.core.data.mapper.toDomain
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.PlaybackSource
import com.stash.feature.listening.PlayPlan
import com.stash.feature.listening.planTopSongs
import com.stash.feature.listening.stats.ListeningInsights
import com.stash.feature.listening.stats.TopArtistUi
import com.stash.feature.listening.stats.artistsWithPhotos
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

/** What the Top screen ranks. [key] is the nav argument. */
enum class TopKind(val key: String, val label: String) {
    SONGS("songs", "Songs"),
    ARTISTS("artists", "Artists"),
    ALBUMS("albums", "Albums"),
    GENRES("genres", "Genres"),
    ;

    companion object {
        /** The kind saved as [key]; songs when unknown. */
        fun fromKey(key: String?): TopKind = entries.firstOrNull { it.key == key } ?: SONGS
    }
}

/** A genre on the Top screen: its share, and the artists that give it the most listening time. */
data class TopGenreUi(val genre: GenreShare, val artists: List<String>)

/** One ranked list on the Top screen; only the chosen [kind]'s list is filled. */
data class TopUiState(
    val kind: TopKind = TopKind.SONGS,
    val range: ListeningRange = ListeningInsights.DEFAULT_RANGE,
    val days: DayRange? = null,
    val today: Long = 0,
    val loading: Boolean = true,
    val songs: List<TopTrackRow> = emptyList(),
    val artists: List<TopArtistUi> = emptyList(),
    val albums: List<TopAlbumRow> = emptyList(),
    val genres: List<TopGenreUi> = emptyList(),
    /** How much of the range's listening time has a known genre, 0..1 (genres list only). */
    val genreCoverage: Double = 0.0,
    /** The range has listens to take genres from (genres list only). */
    val genreHasListens: Boolean = false,
    /** The genre cache has any artist at all (no genre in the range then means "not enough", not "on its way"). */
    val genresCached: Boolean = false,
    /** Some time in the range is estimated from song length: the list ends in a footnote. */
    val estimated: Boolean = false,
) {
    val isEmpty: Boolean
        get() = !loading && when (kind) {
            TopKind.SONGS -> songs.isEmpty()
            TopKind.ARTISTS -> artists.isEmpty()
            TopKind.ALBUMS -> albums.isEmpty()
            TopKind.GENRES -> !genreHasListens
        }

    /** Too little of the listening has a genre to rank genres honestly (DESIGN.md: < 50% hides them). */
    val genresTooThin: Boolean get() = kind == TopKind.GENRES && genreCoverage < TopGenres.MIN_COVERAGE_TO_SHOW
}

/**
 * Feeds the Top screen (TopRoute): the range's songs, artists or albums, ranked, up to 100, with
 * plays and listening time per row. Kind and range come from the route and survive process death.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TopViewModel @VisibleForTesting internal constructor(
    private val savedStateHandle: SavedStateHandle,
    private val history: ListeningHistoryRepository,
    private val artistImages: ArtistImageDao,
    private val playerRepository: PlayerRepository,
    private val connectivityMonitor: ConnectivityMonitor,
    private val random: Random,
    computeDispatcher: CoroutineDispatcher,
    /** Cached artist genres ([ArtistGenreRepository.genreIndex]). */
    private val genreIndex: Flow<ArtistGenreIndex> = flowOf(ArtistGenreIndex.EMPTY),
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
        savedStateHandle, history, artistImages, playerRepository, connectivityMonitor, Random.Default, Dispatchers.Default,
        genres.genreIndex,
    )

    private val _kind = MutableStateFlow(TopKind.fromKey(savedStateHandle.get<String>(ARG_KIND)))
    private val _range = MutableStateFlow(ListeningInsights.rangeOf(savedStateHandle.get<String>(ARG_RANGE)))
    private val today = MutableStateFlow(history.today())

    /** The chosen kind and range, for the chips (move at once; [state] follows). */
    val kind: StateFlow<TopKind> = _kind.asStateFlow()
    val range: StateFlow<ListeningRange> = _range.asStateFlow()

    /** The song playing now, so its row is marked like in every other list. */
    val nowPlayingTrackId: StateFlow<Long?> = playerRepository.playerState
        .map { it.currentTrack?.id }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** One-off messages for a toast. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val state: StateFlow<TopUiState> = combine(_kind, _range, today) { k, r, t -> Triple(k, r, t) }
        .flatMapLatest { (k, r, t) ->
            // Per list: a failed read empties this list, and the next kind or range reads again.
            flow { emitAll(load(k, r, t)) }.catch { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "top list failed", e)
                emit(TopUiState(kind = k, range = r, today = t, loading = false))
            }
        }
        .flowOn(computeDispatcher)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            TopUiState(kind = _kind.value, range = _range.value, today = today.value),
        )

    fun selectKind(kind: TopKind) {
        savedStateHandle[ARG_KIND] = kind.key
        _kind.value = kind
    }

    fun selectRange(range: ListeningRange) {
        if (range !in ListeningInsights.RANGES) return
        savedStateHandle[ARG_RANGE] = range.key
        _range.value = range
    }

    fun refreshToday() {
        today.value = history.today()
    }

    /** Play: the songs from #1. */
    fun play() = playSongs(null, shuffle = false)

    /** Shuffle: the same songs in a random order. */
    fun shuffle() = playSongs(null, shuffle = true)

    /**
     * A tapped song: the list from that song on. By song id, not position: the list may have
     * moved (a listen landed) between drawing the row and the tap.
     */
    fun playFrom(trackId: Long) = playSongs(trackId, shuffle = false)

    private fun playSongs(trackId: Long?, shuffle: Boolean) {
        val s = state.value
        if (s.kind != TopKind.SONGS || s.songs.isEmpty()) return
        val tracks = s.songs.map { it.track.toDomain() }
        val index = if (trackId == null) 0 else tracks.indexOfFirst { it.id == trackId }.takeIf { it >= 0 } ?: return
        val tapped = trackId != null
        viewModelScope.launch {
            when (val plan = planTopSongs(tracks, index, connectivityMonitor.isConnected(), tapped)) {
                is PlayPlan.Play -> {
                    // Shuffle after the offline filter, so it only mixes songs that can play.
                    val queue = if (shuffle) plan.tracks.shuffled(random) else plan.tracks
                    playerRepository.setQueue(
                        queue, if (shuffle) 0 else plan.startIndex,
                        PlaybackSource.Listening(ListeningInsights.topSongsLabel(s.range)),
                    )
                }
                is PlayPlan.Blocked -> _messages.tryEmit(plan.message)
            }
        }
    }

    private suspend fun load(kind: TopKind, range: ListeningRange, today: Long): Flow<TopUiState> {
        val days = range.toDayRange(today, history.firstListenDay())
        val base = TopUiState(kind = kind, range = range, days = days, today = today, loading = false)
        val estimated = history.observeTotals(days).map { it.estimatedPlays > 0 }
        return when (kind) {
            // Each list reads more rows than it shows, groups them like the Library (one row per
            // song, artists and albums by main act), then keeps the top 100.
            TopKind.SONGS -> combine(history.observeTopTracks(days, ListeningInsights.SCAN_LIMIT), estimated) { rows, est ->
                base.copy(songs = ListeningInsights.mergeSameSongs(rows).take(ListeningInsights.TOP_LIMIT), estimated = est)
            }
            TopKind.ARTISTS -> combine(
                history.observeTopArtists(days, ListeningInsights.ARTIST_SCAN_LIMIT),
                artistImages.observeAll(),
                estimated,
            ) { rows, photos, est ->
                base.copy(
                    artists = artistsWithPhotos(ListeningInsights.byMainArtist(rows).take(ListeningInsights.TOP_LIMIT), photos),
                    estimated = est,
                )
            }
            TopKind.ALBUMS -> combine(history.observeTopAlbums(days, ListeningInsights.SCAN_LIMIT), estimated) { rows, est ->
                base.copy(albums = ListeningInsights.byMainAlbum(rows).take(ListeningInsights.TOP_LIMIT), estimated = est)
            }
            // Genres of every main act in the range, weighted by listening time, as Your listening
            // weighs them; each with the acts that give it the most time.
            TopKind.GENRES -> combine(
                history.observeTopArtists(days, ListeningInsights.ARTIST_SCAN_LIMIT),
                genreIndex.catch { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, "genre index failed", e)
                    emit(ArtistGenreIndex.EMPTY)
                },
                estimated,
            ) { rows, index, est ->
                // Raw credits, keyed like the genre cache (see ListeningInsights.artistWeights).
                val top = TopGenresCalculator.compute(ListeningInsights.artistWeights(rows), index, GENRES_LIMIT)
                val artists = ListeningInsights.genreTopArtists(rows, index, top.items.map { it.key })
                base.copy(
                    genres = top.items.map { TopGenreUi(it, artists[it.key].orEmpty()) },
                    genreCoverage = top.coverage,
                    genreHasListens = rows.any { it.listenedMs > 0 },
                    genresCached = index.size > 0,
                    estimated = est,
                )
            }
        }
    }

    companion object {
        private const val TAG = "ListeningTop"

        /** Nav arguments, by the route's property names. */
        const val ARG_KIND = "kind"
        const val ARG_RANGE = "range"

        /** Genres on the Genres list (the whitelist keeps the long tail short anyway). */
        const val GENRES_LIMIT = 30
    }
}
