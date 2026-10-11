package com.stash.feature.nowplaying

import android.content.Context
import app.cash.turbine.test
import com.stash.core.data.db.entity.LyricsEntity
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.social.LikeCoordinator
import com.stash.core.media.PlayerRepository
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import com.stash.data.lyrics.LyricsRepository
import com.stash.data.lyrics.source.LyricsResult
import com.stash.feature.nowplaying.ui.LyricsViewState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * When Now Playing looks lyrics up, and that it never looks the same song up twice: the cases
 * behind "lyrics load slowly" (owner report, 2026-10-11).
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

    private fun newViewModel(appForeground: AppForeground = AppForeground.Never) = NowPlayingViewModel(
        playerRepository = mockk<PlayerRepository>(relaxed = true) {
            every { this@mockk.playerState } returns this@NowPlayingLyricsLoadTest.playerState
            every { currentPosition } returns MutableStateFlow(0L)
        },
        musicRepository = musicRepository,
        likeCoordinator = mockk<LikeCoordinator>(relaxed = true) {
            every { mirrorFailures } returns MutableSharedFlow()
        },
        losslessUpgrader = mockk(relaxed = true),
        lyricsRepository = lyricsRepository,
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

    /** Lets the lyrics chain (which runs on Dispatchers.Default) settle in real time. */
    private suspend fun settle() = withContext(Dispatchers.Default) { delay(300) }

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
        coVerify(exactly = 0) { lyricsRepository.resolveAndStore(any(), any()) }
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
        coVerify(exactly = 0) { lyricsRepository.resolveAndStore(any(), any()) }
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
        coEvery { lyricsRepository.resolveAndStore(any(), any()) } coAnswers {
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
        coVerify(exactly = 1) { lyricsRepository.resolveAndStore(match { it.trackId == 990_459L }, any()) }
    }

    @Test fun `a lookup already in memory shows without a Loading frame`() = runTest(dispatcher) {
        val stream = Track(id = 0L, title = "POP", artist = "Lil Uzi Vert", youtubeId = "J7JXgKfBqzM")
        every { lyricsRepository.cached(any()) } returns com.stash.data.lyrics.CachedLyrics(hit)
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

    @Test fun `in the foreground the playing track's lyrics are fetched before Now Playing opens`() = runTest(dispatcher) {
        val stream = Track(id = 0L, title = "POP", artist = "Lil Uzi Vert", youtubeId = "J7JXgKfBqzM")
        coEvery { lyricsRepository.resolveTransient(any(), any()) } returns hit
        playerState.value = PlayerState(currentTrack = stream)
        newViewModel(appForeground = object : AppForeground { override val foreground = flowOf(true) })
        settle()
        // Nobody collects lyricsViewState: this is the prefetch alone.
        coVerify(exactly = 1) { lyricsRepository.resolveTransient(match { it.youtubeVideoId == "J7JXgKfBqzM" }, any()) }
    }

    @Test fun `in the background nothing is prefetched`() = runTest(dispatcher) {
        val stream = Track(id = 0L, title = "POP", artist = "Lil Uzi Vert", youtubeId = "J7JXgKfBqzM")
        playerState.value = PlayerState(currentTrack = stream)
        newViewModel(appForeground = object : AppForeground { override val foreground = flowOf(false) })
        settle()
        coVerify(exactly = 0) { lyricsRepository.resolveTransient(any(), any()) }
        coVerify(exactly = 0) { lyricsRepository.resolveAndStore(any(), any()) }
    }

    @Test fun `a library track already stamped is not prefetched`() = runTest(dispatcher) {
        val track = Track(id = 42L, title = "Nights", artist = "Frank Ocean", lyricsFetchedAt = 1L)
        rowsById[42L] = flowOf(track)
        playerState.value = PlayerState(currentTrack = track)
        newViewModel(appForeground = object : AppForeground { override val foreground = flowOf(true) })
        settle()
        coVerify(exactly = 0) { lyricsRepository.resolveAndStore(any(), any()) }
    }

    @Test fun `a library track is never looked up as a stream while Room is still answering`() = runTest(dispatcher) {
        // Room answers a moment after the player moves on; until then the track's kind is unknown.
        val row = MutableSharedFlow<Track?>()
        rowsById[42L] = row
        val track = Track(id = 42L, title = "Attention", artist = "Ataxia", youtubeId = "WH2oeGIx39M", lyricsFetchedAt = 1L)
        playerState.value = PlayerState(currentTrack = track)
        newViewModel(appForeground = object : AppForeground { override val foreground = flowOf(true) })
        settle()
        coVerify(exactly = 0) { lyricsRepository.resolveTransient(any(), any()) }
        row.emit(track) // stored, already stamped: nothing to look up at all
        settle()
        coVerify(exactly = 0) { lyricsRepository.resolveTransient(any(), any()) }
        coVerify(exactly = 0) { lyricsRepository.resolveAndStore(any(), any()) }
    }
}
