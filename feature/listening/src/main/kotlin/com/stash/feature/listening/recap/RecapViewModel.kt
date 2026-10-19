package com.stash.feature.listening.recap

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.dao.ArtistImageDao
import com.stash.core.data.db.dao.TopTrackRow
import com.stash.core.data.genre.ArtistGenreIndex
import com.stash.core.data.genre.ArtistGenreRepository
import com.stash.core.data.genre.TopGenresCalculator
import com.stash.core.data.listening.ListeningHistoryRepository
import com.stash.core.data.listening.ListeningStatsCalculator
import com.stash.core.data.listening.RecapPreference
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.PlaybackSource
import com.stash.feature.listening.PlayPlan
import com.stash.feature.listening.planTopSongs
import com.stash.feature.listening.stats.ListeningInsights
import com.stash.feature.listening.stats.artistsWithPhotos
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What the Recap screen shows. */
sealed interface RecapUiState {
    data object Loading : RecapUiState

    /** Too few listens in the period for a story (or the route named no period). */
    data class Empty(val title: String) : RecapUiState

    data class Ready(
        val period: RecapPeriod,
        /** "Your October so far". */
        val title: String,
        /** "October 2026": the share images' footer and the saved playlist's name. */
        val longName: String,
        val cards: List<RecapCard>,
        /** The music under each card, index for index. */
        val cues: List<SongCue>,
        /** Songs "Play your top songs" plays and "Save as playlist" saves, #1 first. */
        val topSongs: List<TopTrackRow>,
    ) : RecapUiState
}

/**
 * Feeds the Recap story (RecapRoute): reads the period's history once, builds the cards
 * ([RecapDeck]) and drives the story's music ([RecapStoryAudio], owner decision: music on, queue
 * restored after; story audio never counts as listening — RecapAudio plays on its own player).
 *
 * Audio contract: [startAudio] once the story is on screen (not while muted), [onCard] for every
 * card shown, [setHeld] for press-and-hold, [closeAudio] from the close button AND the screen's
 * onDispose (idempotent).
 */
@HiltViewModel
class RecapViewModel @VisibleForTesting internal constructor(
    savedStateHandle: SavedStateHandle,
    private val history: ListeningHistoryRepository,
    private val artistImages: ArtistImageDao,
    private val genreIndex: Flow<ArtistGenreIndex>,
    private val audio: RecapStoryAudio,
    mutedPreference: Flow<Boolean>,
    private val saveMuted: suspend (Boolean) -> Unit,
    private val playerRepository: PlayerRepository,
    private val musicRepository: MusicRepository,
    private val connectivityMonitor: ConnectivityMonitor,
    private val computeDispatcher: CoroutineDispatcher,
) : ViewModel() {

    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        history: ListeningHistoryRepository,
        artistImages: ArtistImageDao,
        genres: ArtistGenreRepository,
        audio: RecapStoryAudio,
        recapPreference: RecapPreference,
        playerRepository: PlayerRepository,
        musicRepository: MusicRepository,
        connectivityMonitor: ConnectivityMonitor,
    ) : this(
        savedStateHandle, history, artistImages, genres.genreIndex, audio,
        recapPreference.muted, recapPreference::setMuted, playerRepository, musicRepository, connectivityMonitor,
        Dispatchers.Default,
    )

    private val period: RecapPeriod? = RecapPeriod.fromRoute(
        savedStateHandle.get<String>(ARG_KIND),
        savedStateHandle.get<Int>(ARG_YEAR) ?: 0,
        savedStateHandle.get<Int>(ARG_MONTH) ?: 0,
    )

    private val _state = MutableStateFlow<RecapUiState>(RecapUiState.Loading)
    val state: StateFlow<RecapUiState> = _state.asStateFlow()

    private val _muted = MutableStateFlow(false)

    /** Story music muted (remembered). Muted, the main player is never touched. */
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private val _quizAnswer = MutableStateFlow<Int?>(null)

    /** The quiz choice the user tapped, kept across rotation; null = not answered (or skipped: -1). */
    val quizAnswer: StateFlow<Int?> = _quizAnswer.asStateFlow()

    private val _saved = MutableStateFlow(false)

    /** "Save as playlist" has saved this recap's playlist. */
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _closeRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The story should close (after "Play your top songs" handed the player its queue). */
    val closeRequests: SharedFlow<Unit> = _closeRequests.asSharedFlow()

    // ── Audio state (main thread) ────────────────────────────────────
    private var audioOn = false
    private var closed = false
    private var currentCard = -1
    private var userHeld = false
    private var silenced = false
    private var mutedLoaded = false
    private var startingPlayback = false

    init {
        viewModelScope.launch {
            _muted.value = runCatching { mutedPreference.first() }.getOrDefault(false)
            mutedLoaded = true
            load()
        }
    }

    private suspend fun load() {
        val p = period ?: run {
            _state.value = RecapUiState.Empty("Your recap")
            return
        }
        val today = history.today()
        val title = p.title(today)
        _state.value = try {
            val data = withContext(computeDispatcher) { readData(p, today) }
            val cards = RecapDeck.build(data, title)
            if (cards.isEmpty()) {
                RecapUiState.Empty(title)
            } else {
                RecapUiState.Ready(p, title, p.longName(), cards, RecapDeck.cues(cards, data), data.topSongs)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "recap failed", e)
            RecapUiState.Empty(title)
        }
    }

    /** Every number the story tells, read once (one-off reads of the same queries Your listening uses). */
    private suspend fun readData(p: RecapPeriod, today: Long): RecapData {
        val days = p.dayRange(today)
        val totals = history.observeTotals(days).first()
        if (totals.plays < RecapDeck.MIN_PLAYS) {
            return RecapData(p, days, today, totals, emptyList(), emptyList(), 0)
        }
        val scan = history.observeTopTracks(days, ListeningInsights.SCAN_LIMIT).first()
        val songs = ListeningInsights.mergeSameSongs(scan)
        val credits = history.observeTopArtists(days, ListeningInsights.ARTIST_SCAN_LIMIT).first()
        val acts = ListeningInsights.byMainArtist(credits)
        val photos = artistImages.observeAll().first()
        val topArtists = artistsWithPhotos(acts.take(RecapDeck.RANKED), photos)
        // A broken genre cache costs the genres card, never the story.
        val index = runCatching { genreIndex.first() }
            .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "genre index failed", it) }
            .getOrDefault(ArtistGenreIndex.EMPTY)
        // Raw credits keyed like the genre cache (same numbers as Your listening).
        val genres = TopGenresCalculator.compute(ListeningInsights.artistWeights(credits), index, RecapDeck.GENRES_SHOWN)

        // An artist's most played song in the period, never the #1: the cards that play these come
        // before the quiz and must not give its answer away (songs are most played first).
        val no1Key = songs.firstOrNull()?.let { ListeningInsights.songKey(it.track.title, it.track.artist) }
        fun topSongOf(artistName: String): TopTrackRow? {
            val key = ListeningInsights.artistKey(artistName)
            return songs.firstOrNull {
                ListeningInsights.artistKey(it.track.artist) == key &&
                    ListeningInsights.songKey(it.track.title, it.track.artist) != no1Key
            }
        }
        val artistTopSongs = topArtists.mapNotNull { a -> topSongOf(a.name)?.let { a.key to it } }.toMap()
        val genreSong = genres.items.firstOrNull()?.let { top ->
            ListeningInsights.genreTopArtists(credits, index, listOf(top.key), perGenre = 1)[top.key]?.firstOrNull()
        }?.let(::topSongOf)

        val topArtistKey = topArtists.firstOrNull()?.key
        val topArtistDays = if (topArtistKey == null) {
            emptySet()
        } else {
            history.artistDays(days).filter { ListeningInsights.artistKey(it.artist) == topArtistKey }.map { it.day }.toSet()
        }

        val hourly = ListeningInsights.hourly(history.observeHourHistogram(days).first()).toList()
        val dayCounts = history.observeDayCounts(days).first()
        val busiest = ListeningStatsCalculator.busiestDay(dayCounts)
        // The busiest day's top songs (one row per song, as everywhere), most played first.
        val busiestSongs = busiest?.let { b ->
            history.listensOn(b.day)
                .groupBy { ListeningInsights.songKey(it.track.title, it.track.artist) }
                .map { (_, rows) -> TopTrackRow(rows.first().track, rows.size, rows.maxOf { it.event.startedAt }) }
                .sortedWith(compareByDescending<TopTrackRow> { it.plays }.thenByDescending { it.lastPlayedAt })
                .take(RecapDeck.DAY_TOP)
        }.orEmpty()
        val first = history.firstListenDay()
        val wholeHistory = first == null || first >= days.fromDay
        val newSongs = if (wholeHistory) 0 else history.countNewTracksIn(days)
        val newFinds = if (newSongs > 0) ListeningInsights.mergeSameSongs(history.newTracksIn(days, NEW_FINDS)) else emptyList()

        // The #1's first listen across every library row of that song (merged rows share the key).
        val no1 = songs.firstOrNull()
        val no1Ids = no1?.let { lead ->
            val key = ListeningInsights.songKey(lead.track.title, lead.track.artist)
            (scan.filter { ListeningInsights.songKey(it.track.title, it.track.artist) == key }.map { it.track.id } + lead.track.id)
        }.orEmpty()

        return RecapData(
            period = p,
            days = days,
            today = today,
            totals = totals,
            topSongs = songs.take(PLAYLIST_SIZE),
            topArtists = topArtists,
            artistCount = acts.size,
            genres = genres,
            artistTopSongs = artistTopSongs,
            genreSong = genreSong,
            topArtistDays = topArtistDays,
            hourly = hourly,
            longestStreak = ListeningStatsCalculator.longestStreak(dayCounts),
            busiestDay = busiest,
            busiestDaySongs = busiestSongs,
            obsession = history.obsession(days),
            newSongs = newSongs,
            newFinds = newFinds,
            topSongFirstListen = history.firstListen(no1Ids),
        )
    }

    // ── Story audio ──────────────────────────────────────────────────

    /** The story is on screen: its music starts (unless muted). Safe to call again. */
    fun startAudio() {
        if (closed || audioOn || _muted.value || !mutedLoaded || _state.value !is RecapUiState.Ready) return
        audioOn = true
        audio.begin()
        applyCue()
    }

    /** Card [index] is showing: its song (or silence for the quiz). */
    fun onCard(index: Int) {
        currentCard = index
        applyCue()
    }

    /** Press-and-hold: the story and its song pause; letting go carries on. */
    fun setHeld(held: Boolean) {
        if (userHeld == held) return
        userHeld = held
        applyCue()
    }

    private fun applyCue() {
        if (!audioOn) return
        val ready = _state.value as? RecapUiState.Ready ?: return
        val cue = ready.cues.getOrNull(currentCard) ?: return
        val shouldBeSilent = userHeld || cue is SongCue.Silence
        if (cue is SongCue.Play) audio.play(cue.song.track.toDomain())
        if (shouldBeSilent != silenced) {
            silenced = shouldBeSilent
            if (shouldBeSilent) audio.pause() else audio.resume()
        }
    }

    /** The mute button: muting closes the story's music (the main player comes back); unmuting starts it. */
    fun toggleMute() {
        val nowMuted = !_muted.value
        _muted.value = nowMuted
        viewModelScope.launch { runCatching { saveMuted(nowMuted) }.onFailure { Log.w(TAG, "mute not saved", it) } }
        if (nowMuted) {
            if (audioOn) {
                audioOn = false
                silenced = false
                audio.end(restoreMain = true)
            }
        } else {
            startAudio()
        }
    }

    /** The story closes (close button, swipe down, back, or the screen leaving). Idempotent. */
    fun closeAudio() {
        if (closed) return
        closed = true
        if (audioOn) {
            audioOn = false
            audio.end(restoreMain = true)
        }
    }

    override fun onCleared() {
        closeAudio()
        super.onCleared()
    }

    // ── Quiz ─────────────────────────────────────────────────────────

    fun answerQuiz(choice: Int) {
        if (_quizAnswer.value == null) _quizAnswer.value = choice
    }

    fun skipQuiz() {
        if (_quizAnswer.value == null) _quizAnswer.value = QUIZ_SKIPPED
    }

    // ── End actions ──────────────────────────────────────────────────

    /** "Play your top songs": the story's music ends without bringing the old queue back, then the top songs play. */
    fun playTopSongs() {
        val ready = _state.value as? RecapUiState.Ready ?: return
        val tracks = ready.topSongs.map { it.track.toDomain() }
        if (tracks.isEmpty() || startingPlayback) return
        // A double tap must not set the queue twice (or close the story twice).
        startingPlayback = true
        viewModelScope.launch {
            when (val plan = planTopSongs(tracks, 0, connectivityMonitor.isConnected(), tapped = false)) {
                is PlayPlan.Play -> {
                    closed = true
                    if (audioOn) {
                        audioOn = false
                        audio.endAndWait(restoreMain = false)
                    }
                    playerRepository.setQueue(plan.tracks, plan.startIndex, PlaybackSource.Listening(ready.title))
                    _closeRequests.tryEmit(Unit)
                }
                is PlayPlan.Blocked -> {
                    startingPlayback = false
                    _messages.tryEmit(plan.message)
                }
            }
        }
    }

    /**
     * "Save as playlist": the top songs as a playlist named "Your October 2026". Saving the same
     * recap again (here or a later visit) adds to that playlist rather than making a second one.
     */
    fun saveAsPlaylist() {
        val ready = _state.value as? RecapUiState.Ready ?: return
        if (_saved.value) return
        val ids = ready.topSongs.map { it.track.id }.distinct()
        if (ids.isEmpty()) return
        _saved.value = true
        viewModelScope.launch {
            try {
                val name = playlistName(ready.period)
                val existing = musicRepository.getUserCreatedPlaylists().first()
                    .firstOrNull { it.name.equals(name, ignoreCase = true) }
                if (existing != null) {
                    // Adds only what isn't there yet: the songs already in it keep their places.
                    val have = musicRepository.getTracksByPlaylist(existing.id).first().map { it.id }.toSet()
                    val missing = ids.filter { it !in have }
                    if (missing.isNotEmpty()) musicRepository.addTracksToPlaylist(missing, existing.id)
                    _messages.tryEmit("Updated your playlist")
                } else {
                    musicRepository.createPlaylistWithTracks(name, ids)
                    _messages.tryEmit("Saved to your playlists")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "save as playlist failed", e)
                _saved.value = false
                _messages.tryEmit("Couldn't save the playlist. Try again.")
            }
        }
    }

    companion object {
        private const val TAG = "Recap"

        /** Route arguments, by RecapRoute's property names. */
        const val ARG_KIND = "kind"
        const val ARG_YEAR = "year"
        const val ARG_MONTH = "month"

        /** [quizAnswer] for a skipped quiz. */
        const val QUIZ_SKIPPED = -1

        /** Songs "Play your top songs" queues and "Save as playlist" saves. */
        const val PLAYLIST_SIZE = 50

        /** New songs read for the best find, so one already shown on an earlier card can be skipped. */
        const val NEW_FINDS = 5

        /** "Your October 2026" / "Your 2026". */
        fun playlistName(period: RecapPeriod): String = "Your ${period.longName()}"
    }
}
