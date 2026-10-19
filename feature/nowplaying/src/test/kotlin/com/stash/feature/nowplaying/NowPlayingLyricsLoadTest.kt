package com.stash.feature.nowplaying

import android.content.Context
import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.stash.core.common.Clock
import com.stash.core.data.db.dao.LyricsDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.LyricsEntity
import com.stash.core.data.prefs.LyricsPreference
import com.stash.core.data.prefs.LyricsSourcePreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.social.LikeCoordinator
import com.stash.core.media.PlayerRepository
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import com.stash.data.lyrics.CachedLyrics
import com.stash.data.lyrics.LyricsRepository
import com.stash.data.lyrics.sidecar.LyricsSidecarWriter
import com.stash.data.lyrics.source.LyricsQuery
import com.stash.data.lyrics.source.LyricsResult
import com.stash.data.lyrics.source.LyricsSource
import com.stash.feature.nowplaying.ui.LyricsViewState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * When Now Playing looks lyrics up, and that it never looks the same song up (or stores it)
 * twice: the cases behind "lyrics load slowly" (owner report, 2026-10-11) and its review.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingLyricsLoadTest {

    private val dispatcher = UnconfinedTestDispatcher()

    /** Every ViewModel made here: its scope is cancelled before Main is reset (the lyrics chain runs on Default). */
    private val viewModels = mutableListOf<NowPlayingViewModel>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Thread.sleep(200) // let Default-dispatcher work see the cancellation before Main goes away
        Dispatchers.resetMain()
    }

    private val lrc = "[00:01.00]one\n[00:02.00]two"
    private val playerState = MutableStateFlow(PlayerState())
    private val byYoutubeId = MutableStateFlow<Track?>(null)
    private val rowsById = HashMap<Long, Flow<Track?>>()

    private val musicRepository: MusicRepository = mockk(relaxed = true) {
        every { observeTrackById(any()) } answers { rowsById[firstArg()] ?: flowOf(null) }
        every { observeTrackByYoutubeId(any()) } returns byYoutubeId
        every { getUserCreatedPlaylists() } returns flowOf(emptyList())
    }
    private val lyricsRepository: LyricsRepository = mockk(relaxed = true) {
        every { cached(any()) } returns null
    }
    private val foreground = object : AppForeground { override val foreground = flowOf(true) }

    private fun newViewModel(
        appForeground: AppForeground = AppForeground.Never,
        repository: LyricsRepository = lyricsRepository,
    ) = NowPlayingViewModel(
        playerRepository = mockk<PlayerRepository>(relaxed = true) {
            every { this@mockk.playerState } returns this@NowPlayingLyricsLoadTest.playerState
            every { currentPosition } returns MutableStateFlow(0L)
        },
        musicRepository = musicRepository,
        likeCoordinator = mockk<LikeCoordinator>(relaxed = true) {
            every { mirrorFailures } returns MutableSharedFlow()
        },
        losslessUpgrader = mockk(relaxed = true),
        downloadModeReader = mockk(relaxed = true),
        lyricsRepository = repository,
        lyricsPreference = mockk(relaxed = true),
        nowPlayingPreference = mockk(relaxed = true),
        lyricsSidecarWriter = mockk(relaxed = true),
        appContext = mockk<Context>(relaxed = true),
        sleepTimerController = mockk(relaxed = true),
        ytMusicApiClient = mockk(relaxed = true),
        appForeground = appForeground,
    ).also { viewModels += it }

    private fun row(trackId: Long) = LyricsEntity(
        trackId = trackId, plainText = "one two", syncedLrc = lrc, instrumental = false,
        language = null, source = "lrclib", sourceLyricsId = "1", fetchedAt = 1L,
    )

    private val hit = LyricsResult("lrclib", "one two", lrc, false, null, "1")

    /** Past the prefetch's dwell (virtual time: the ViewModel's Main is the test dispatcher). */
    private val pastDwell = 2_000L

    /** Lets work on Dispatchers.Default / IO settle in real time. */
    private suspend fun settle() = withContext(Dispatchers.Default) { delay(300) }

    // ── opening lyrics ───────────────────────────────────────────────────────────────────────

    @Test fun `opening lyrics mode on a track with lyrics loaded doesn't look them up again`() = runTest(dispatcher) {
        val track = Track(id = 42L, title = "Nights", artist = "Frank Ocean", lyricsFetchedAt = 1L)
        rowsById[42L] = flowOf(track)
        every { lyricsRepository.observe(42L) } returns flowOf(row(42L))
        every { lyricsRepository.observeFetchedAt(42L) } returns flowOf(1L)
        playerState.value = PlayerState(currentTrack = track)
        val vm = newViewModel()

        vm.lyricsViewState.test {
            var state = awaitItem()
            while (state !is LyricsViewState.Synced) state = awaitItem()
            vm.onShowLyrics()
            vm.onToggleLyrics()
            vm.onToggleLyrics()
            settle()
            expectNoEvents() // no fall back to Loading
            assertTrue(vm.lyricsMode.value)
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 0) { lyricsRepository.resolveAndStore(any(), any(), any()) }
        coVerify(exactly = 0) { lyricsRepository.resolveTransient(any(), any()) }
    }

    @Test fun `a stream under a negative transient id is looked up at once`() = runTest(dispatcher) {
        // Radio and queue streams play as videoId.hashCode() until a listen persists them.
        val stream = Track(id = -609_880_768L, title = "wokeuplikethis*", artist = "Playboi Carti", youtubeId = "zHXwcFLKrhE")
        coEvery { lyricsRepository.resolveTransient(any(), any()) } returns hit
        playerState.value = PlayerState(currentTrack = stream)
        val vm = newViewModel()

        vm.lyricsViewState.test {
            var state = awaitItem()
            while (state !is LyricsViewState.Synced) state = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { lyricsRepository.resolveTransient(any(), any()) }
        coVerify(exactly = 0) { lyricsRepository.resolveAndStore(any(), any(), any()) }
    }

    @Test fun `when the stream is persisted its lyrics stay on screen`() = runTest(dispatcher) {
        val stream = Track(id = -609_880_768L, title = "wokeuplikethis*", artist = "Playboi Carti", youtubeId = "zHXwcFLKrhE")
        val persisted = stream.copy(id = 990_459L)
        coEvery { lyricsRepository.resolveTransient(any(), any()) } returns hit
        val rowFlow = MutableStateFlow<LyricsEntity?>(null)
        val stampFlow = MutableStateFlow<Long?>(null)
        every { lyricsRepository.observe(990_459L) } returns rowFlow
        every { lyricsRepository.observeFetchedAt(990_459L) } returns stampFlow
        // The repository fills the row from its memory of the stream (LyricsRepositoryCacheTest).
        coEvery { lyricsRepository.resolveAndStore(any(), any(), any()) } coAnswers {
            rowFlow.value = row(990_459L)
            stampFlow.value = 2L
            row(990_459L)
        }
        playerState.value = PlayerState(currentTrack = stream)
        val vm = newViewModel()

        vm.lyricsViewState.test {
            var state = awaitItem()
            while (state !is LyricsViewState.Synced) state = awaitItem()
            // A listen persists the stream: it now answers from the library under a new id.
            byYoutubeId.value = persisted
            settle()
            expectNoEvents() // no "Looking for lyrics" in between
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { lyricsRepository.resolveTransient(any(), any()) }
        coVerify(exactly = 1) { lyricsRepository.resolveAndStore(match { it.trackId == 990_459L }, any(), any()) }
    }

    @Test fun `a lookup already in memory shows without a Loading frame`() = runTest(dispatcher) {
        val stream = Track(id = 0L, title = "POP", artist = "Lil Uzi Vert", youtubeId = "J7JXgKfBqzM")
        every { lyricsRepository.cached(any()) } returns CachedLyrics(hit)
        playerState.value = PlayerState(currentTrack = stream)
        val vm = newViewModel()

        vm.lyricsViewState.test {
            val first = awaitItem()
            val shown = if (first is LyricsViewState.Synced) first else awaitItem()
            assertTrue(shown is LyricsViewState.Synced)
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 0) { lyricsRepository.resolveTransient(any(), any()) }
    }

    // ── the prefetch ─────────────────────────────────────────────────────────────────────────

    @Test fun `in the foreground the playing track is prefetched after the dwell, before Now Playing opens`() = runTest(dispatcher) {
        val stream = Track(id = 0L, title = "POP", artist = "Lil Uzi Vert", youtubeId = "J7JXgKfBqzM")
        playerState.value = PlayerState(currentTrack = stream)
        newViewModel(appForeground = foreground)
        verify(exactly = 0) { lyricsRepository.prefetch(isNull(inverse = true), any()) } // still dwelling
        advanceTimeBy(pastDwell)
        verify(exactly = 1) { lyricsRepository.prefetch(match { it.youtubeVideoId == "J7JXgKfBqzM" }, false) }
    }

    @Test fun `skipping through songs within the dwell prefetches only the one played`() = runTest(dispatcher) {
        newViewModel(appForeground = foreground)
        for ((i, yt) in listOf("a1", "b2", "c3").withIndex()) {
            playerState.value = PlayerState(currentTrack = Track(id = 0L, title = "Song $i", artist = "Artist", youtubeId = yt))
            advanceTimeBy(500) // a skip every half second
        }
        advanceTimeBy(pastDwell)
        verify(exactly = 1) { lyricsRepository.prefetch(isNull(inverse = true), any()) }
        verify(exactly = 1) { lyricsRepository.prefetch(match { it.youtubeVideoId == "c3" }, false) }
    }

    @Test fun `in the background nothing is prefetched`() = runTest(dispatcher) {
        val stream = Track(id = 0L, title = "POP", artist = "Lil Uzi Vert", youtubeId = "J7JXgKfBqzM")
        playerState.value = PlayerState(currentTrack = stream)
        newViewModel(appForeground = object : AppForeground { override val foreground = flowOf(false) })
        advanceTimeBy(pastDwell)
        verify(exactly = 0) { lyricsRepository.prefetch(isNull(inverse = true), any()) }
    }

    @Test fun `a library track already stamped is not prefetched`() = runTest(dispatcher) {
        val track = Track(id = 42L, title = "Nights", artist = "Frank Ocean", lyricsFetchedAt = 1L)
        rowsById[42L] = flowOf(track)
        playerState.value = PlayerState(currentTrack = track)
        newViewModel(appForeground = foreground)
        advanceTimeBy(pastDwell)
        verify(exactly = 0) { lyricsRepository.prefetch(isNull(inverse = true), any()) }
    }

    @Test fun `a library track is never looked up as a stream while Room is still answering`() = runTest(dispatcher) {
        // Room answers a moment after the player moves on; until then the track's kind is unknown.
        val row = MutableSharedFlow<Track?>()
        rowsById[42L] = row
        val track = Track(id = 42L, title = "Attention", artist = "Ataxia", youtubeId = "WH2oeGIx39M", lyricsFetchedAt = null)
        playerState.value = PlayerState(currentTrack = track)
        newViewModel(appForeground = foreground)
        advanceTimeBy(pastDwell)
        verify(exactly = 0) { lyricsRepository.prefetch(isNull(inverse = true), any()) }
        row.emit(track) // stored, not yet stamped: a library prefetch, never a stream one
        advanceTimeBy(pastDwell)
        verify(exactly = 0) { lyricsRepository.prefetch(isNull(inverse = true), false) }
        verify(exactly = 1) { lyricsRepository.prefetch(match { it.trackId == 42L }, true) }
    }

    @Test fun `a transient id equal to another row's id is not that row`() = runTest(dispatcher) {
        // videoId.hashCode() happened to equal an existing row's id, a different recording.
        val hashId = "J7JXgKfBqzM".hashCode().toLong()
        rowsById[hashId] = flowOf(Track(id = hashId, title = "Other", artist = "Someone", youtubeId = "otherVid"))
        val stream = Track(id = hashId, title = "POP", artist = "Lil Uzi Vert", youtubeId = "J7JXgKfBqzM")
        playerState.value = PlayerState(currentTrack = stream)
        val vm = newViewModel()
        settle()
        assertEquals("POP", vm.uiState.value.currentTrack?.title)
        assertEquals(false, vm.uiState.value.currentTrackStored)
    }

    // ── S1: two ViewModels, one real repository ──────────────────────────────────────────────

    @Test fun `the mini player's and Now Playing's ViewModels store a library track's lyrics once`() = runTest(dispatcher) {
        val calls = AtomicInteger()
        val source = object : LyricsSource {
            override val id = "lrclib"
            override val displayName = "LRCLIB"
            override suspend fun resolve(query: LyricsQuery): LyricsResult? { calls.incrementAndGet(); return hit }
        }
        val stamp = MutableStateFlow<Long?>(null)
        val trackDao: TrackDao = mockk(relaxed = true) {
            every { observeLyricsFetchedAt(77L) } returns stamp
            coEvery { setLyricsFetchedAt(77L, any()) } answers { stamp.value = secondArg() }
        }
        val lyricsDao: LyricsDao = mockk(relaxed = true) { coEvery { this@mockk.get(any()) } returns null }
        val sidecar: LyricsSidecarWriter = mockk(relaxed = true)
        val preference: LyricsPreference = mockk(relaxed = true) {
            every { sourcePreference } returns flowOf(LyricsSourcePreference.APPLE_MUSIC)
        }
        val repository = LyricsRepository(
            listOf(source), lyricsDao, trackDao, sidecar,
            object : Clock { override fun now() = 1_700_000_000_000L }, preference,
        )
        val track = Track(id = 77L, title = "Ain't It Fun", artist = "Paramore", youtubeId = "4c0GH34IQpw")
        rowsById[77L] = flowOf(track)
        playerState.value = PlayerState(currentTrack = track)

        newViewModel(appForeground = foreground, repository = repository) // the mini player's
        newViewModel(appForeground = foreground, repository = repository) // Now Playing's
        advanceTimeBy(pastDwell)
        withContext(Dispatchers.Default) {
            for (i in 0 until 40) { if (stamp.value != null) break; delay(50) }
        }
        settle()

        assertEquals(1, calls.get())
        coVerify(exactly = 1) { lyricsDao.upsert(any()) }
        coVerify(exactly = 1) { sidecar.write(77L, any()) }
    }

    // ── re-review ────────────────────────────────────────────────────────────────────────────

    @Test fun `a real id whose row has another YouTube id is still that library row`() = runTest(dispatcher) {
        // A match swap (or a Listen Together listener's copy) changed the row's YouTube id while
        // the queued item kept the old one. 42 is a real id, not "oldVid".hashCode().
        val row = Track(
            id = 42L, title = "Nights", artist = "Frank Ocean", youtubeId = "newVid",
            stashLikedAt = 5L, fileFormat = "flac", lyricsFetchedAt = 1L,
        )
        rowsById[42L] = flowOf(row)
        playerState.value = PlayerState(currentTrack = Track(id = 42L, title = "Nights", artist = "Frank Ocean", youtubeId = "oldVid"))
        val vm = newViewModel()
        settle()
        assertEquals(true, vm.uiState.value.currentTrackStored)
        assertEquals(5L, vm.uiState.value.currentTrack?.stashLikedAt)     // the heart
        assertEquals("flac", vm.uiState.value.currentTrack?.fileFormat)   // the format badge
    }

    @Test fun `a new ViewModel's first empty emission doesn't revoke the other's request`() = runTest(dispatcher) {
        val stream = Track(id = 0L, title = "POP", artist = "Lil Uzi Vert", youtubeId = "J7JXgKfBqzM")
        playerState.value = PlayerState(currentTrack = stream)
        newViewModel(appForeground = foreground) // the mini player's
        advanceTimeBy(500)
        newViewModel(appForeground = foreground) // Now Playing opens mid-dwell
        advanceTimeBy(pastDwell)
        verify(exactly = 0) { lyricsRepository.prefetch(null, any()) }
        verify(atLeast = 1) { lyricsRepository.prefetch(match { it.youtubeVideoId == "J7JXgKfBqzM" }, false) }
    }
}
