package com.stash.feature.nowplaying

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.stash.core.data.backcover.AlbumBackCovers
import com.stash.core.data.backcover.AlbumCoverArt
import com.stash.core.data.backcover.BackCoverAlbum
import com.stash.core.data.prefs.DataSaver
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.social.LikeCoordinator
import com.stash.core.media.PlayerRepository
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Data saver on mobile data: Now Playing does not look the playing album's back cover, inside pages
 * or Discogs scans up (they wait for Wi-Fi). On Wi-Fi, or with the switch off, it does as before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingBackCoverDataSaverTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val viewModels = mutableListOf<NowPlayingViewModel>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Thread.sleep(200) // let Default-dispatcher work (the lyrics chain) see the cancellation
        Dispatchers.resetMain()
    }

    private val track = Track(id = 42L, title = "Nights", artist = "Frank Ocean", album = "Blonde")
    private val playerState = MutableStateFlow(PlayerState(currentTrack = track))
    private val musicRepository: MusicRepository = mockk(relaxed = true) {
        every { observeTrackById(42L) } returns flowOf(track)
        every { getUserCreatedPlaylists() } returns flowOf(emptyList())
    }

    /** Counts look-ups; nothing is ever found (no image to fetch), and [observe] never completes, like Room. */
    private class CountingBackCovers : AlbumBackCovers {
        val resolves = AtomicInteger()
        override fun observe(album: BackCoverAlbum): Flow<AlbumCoverArt?> = MutableStateFlow(null)
        override suspend fun resolve(album: BackCoverAlbum): AlbumCoverArt? {
            resolves.incrementAndGet()
            return null
        }
    }

    private fun newViewModel(backCovers: AlbumBackCovers, dataSaver: DataSaver) = NowPlayingViewModel(
        playerRepository = mockk<PlayerRepository>(relaxed = true) {
            every { this@mockk.playerState } returns this@NowPlayingBackCoverDataSaverTest.playerState
            every { currentPosition } returns MutableStateFlow(0L)
        },
        musicRepository = musicRepository,
        likeCoordinator = mockk<LikeCoordinator>(relaxed = true) { every { mirrorFailures } returns MutableSharedFlow() },
        losslessUpgrader = mockk(relaxed = true),
        lyricsRepository = mockk<com.stash.data.lyrics.LyricsRepository>(relaxed = true) {
            every { cached(any()) } returns null
        },
        lyricsPreference = mockk(relaxed = true),
        nowPlayingPreference = mockk(relaxed = true),
        lyricsSidecarWriter = mockk(relaxed = true),
        appContext = mockk<Context>(relaxed = true),
        sleepTimerController = mockk(relaxed = true),
        ytMusicApiClient = mockk(relaxed = true),
        appForeground = object : AppForeground { override val foreground = flowOf(true) },
        backCovers = backCovers,
        dataSaver = dataSaver,
    ).also { viewModels += it }

    /** Past the prefetch's 1.8 s dwell (virtual time: Main is the test dispatcher). */
    private val pastDwell = 2_000L

    @Test fun `on mobile data under Data saver the back cover is not looked up`() = runTest(dispatcher) {
        val covers = CountingBackCovers()
        newViewModel(covers, DataSaver(switchOn = { true }, metered = { true }))

        advanceTimeBy(pastDwell)

        assertEquals(0, covers.resolves.get())
    }

    @Test fun `on Wi-Fi with Data saver on the back cover is looked up as before`() = runTest(dispatcher) {
        val covers = CountingBackCovers()
        newViewModel(covers, DataSaver(switchOn = { true }, metered = { false }))

        advanceTimeBy(pastDwell)

        assertTrue("looked up at least once", covers.resolves.get() >= 1)
    }
}
