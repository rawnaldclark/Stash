package com.stash.core.media

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.media.service.StashPlaybackService
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrlCache
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Pins the repository half of the idle-stop handshake ([PlaybackSessionBus]).
 *
 * When the service announces it is stopping, the repository must release its
 * MediaController — that binding is what kept a stopped service (and the
 * process, and two ExoPlayers) alive around the clock. But releasing must
 * NOT wipe the last published player state: the mini player keeps showing
 * the paused track, and play() rebuilds from the persisted queue.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositorySessionBusTest {

    private val playbackStateStore: PlaybackStateStore = mockk(relaxed = true)
    private val musicRepository: MusicRepository = mockk {
        every { trackDeletions } returns MutableSharedFlow()
    }
    private val streamingPreference: StreamingPreference = mockk(relaxed = true)
    private val streamResolver: StreamSourceRegistry = mockk()
    private val streamUrlCache: StreamUrlCache = mockk(relaxUnitFun = true)
    private val connectivity: ConnectivityMonitor = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val controller: MediaController = mockk(relaxed = true)
    private val playbackResumer: PlaybackResumer = mockk(relaxed = true)
    private val trackIdentityEvents: TrackIdentityEvents = mockk {
        every { changes } returns MutableSharedFlow()
    }
    private val bus = PlaybackSessionBus()

    private lateinit var repo: PlayerRepositoryImpl

    @Before
    fun setUp() {
        repo = PlayerRepositoryImpl(
            context = ApplicationProvider.getApplicationContext(),
            playbackStateStore = playbackStateStore,
            musicRepository = musicRepository,
            streamingPreference = streamingPreference,
            streamResolver = streamResolver,
            streamUrlCache = streamUrlCache,
            connectivity = connectivity,
            trackDao = trackDao,
            playbackResumer = playbackResumer,
            radioGenerator = mockk(relaxed = true),
            trackIdentityEvents = trackIdentityEvents,
            playbackSessionBus = bus,
        )
        every { controller.isConnected } returns true
        repo.controllerDeferred = controller
    }

    @Test
    fun `service stopping releases the controller so the binding cannot pin the service`() = runTest {
        repo.onSessionAliveChanged(false)

        verify(exactly = 1) { controller.release() }
        assertThat(repo.controllerDeferred).isNull()
    }

    @Test
    fun `service stopping keeps the last player state for the UI`() = runTest {
        // Flush the init-time work while the seam mock is still installed —
        // draining AFTER the release would let the queued eager connect
        // build a real controller against Robolectric's fake binder.
        shadowOf(Looper.getMainLooper()).idle()
        val before = repo.playerState.value

        repo.onSessionAliveChanged(false)

        assertThat(repo.playerState.value).isEqualTo(before)
    }

    @Test
    fun `session alive with a live controller is a no-op`() = runTest {
        repo.onSessionAliveChanged(true)

        verify(exactly = 0) { controller.release() }
        assertThat(repo.controllerDeferred).isSameInstanceAs(controller)
    }

    @Test
    fun `the bus signal itself drives the release`() = runTest {
        // End-to-end through the init collector, not just the handler:
        // this is the wiring a green unit test failed to prove last time.
        bus.onServiceStopping()
        shadowOf(Looper.getMainLooper()).idle()

        verify(exactly = 1) { controller.release() }
        assertThat(repo.controllerDeferred).isNull()
    }

    /**
     * #462, second half: the service idles out, the app reopens, the service is
     * recreated and the fresh controller's first sync is EMPTY. That refresh used to
     * wipe the kept snapshot, so the app showed "Not Playing" and no mini player.
     */
    private suspend fun idleOutAndReopen(buffering: Boolean = false): MediaController {
        coEvery { playbackResumer.buildResumePlan() } returns null
        shadowOf(Looper.getMainLooper()).idle()
        repo.controllerDeferred = controller // init's "not alive" released the seam; re-seat it
        io.mockk.clearMocks(playbackResumer, answers = false)
        val extras = Bundle().apply { putLong(StashPlaybackService.EXTRA_TRACK_ID, 2L) }
        val item = MediaItem.Builder()
            .setMediaId("2")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Reckoner").setExtras(extras).build())
            .build()
        every { controller.mediaItemCount } returns 1
        every { controller.currentMediaItem } returns item
        every { controller.getMediaItemAt(0) } returns item
        every { controller.isPlaying } returns !buffering
        if (buffering) every { controller.playbackState } returns Player.STATE_BUFFERING
        every { streamUrlCache.get(any()) } returns null // a downloaded track: no live stream
        repo.updateState(controller)
        assertThat(repo.playerState.value.currentTrack?.id).isEqualTo(2L)
        assertThat(repo.playerState.value.isBuffering).isEqualTo(buffering)

        repo.onSessionAliveChanged(false) // the idle-stop
        val fresh: MediaController = mockk(relaxed = true)
        every { fresh.isConnected } returns true
        every { fresh.mediaItemCount } returns 0
        every { fresh.currentMediaItem } returns null
        repo.controllerDeferred = fresh
        repo.onSessionAliveChanged(true)
        repo.updateState(fresh) // what a fresh connect's first sync does
        return fresh
    }

    @Test
    fun `after an idle-out the paused song is still on screen when the app reopens`() = runTest {
        idleOutAndReopen()

        val state = repo.playerState.value
        assertThat(state.currentTrack?.id).isEqualTo(2L)
        assertThat(state.currentTrack?.title).isEqualTo("Reckoner")
        assertThat(state.isPlaying).isFalse()
    }

    @Test
    fun `a ghost made while the song was loading shows no spinner`() = runTest {
        idleOutAndReopen(buffering = true)

        assertThat(repo.playerState.value.currentTrack?.id).isEqualTo(2L)
        assertThat(repo.playerState.value.isBuffering).isFalse()
    }

    @Test
    fun `a refresh with real items after an idle-out retires the ghost`() = runTest {
        val fresh = idleOutAndReopen()
        // Android Auto or a media button started playback on the new service.
        val extras = Bundle().apply { putLong(StashPlaybackService.EXTRA_TRACK_ID, 3L) }
        val item = MediaItem.Builder()
            .setMediaId("3")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Nude").setExtras(extras).build())
            .build()
        every { fresh.mediaItemCount } returns 1
        every { fresh.currentMediaItem } returns item
        every { fresh.getMediaItemAt(0) } returns item
        every { fresh.isPlaying } returns true

        repo.updateState(fresh)

        assertThat(repo.playerState.value.currentTrack?.id).isEqualTo(3L)
        assertThat(repo.playerState.value.isPlaying).isTrue()
        // Retired: the next empty refresh is the player's truth again, not a ghost to keep.
        every { fresh.mediaItemCount } returns 0
        every { fresh.currentMediaItem } returns null
        repo.updateState(fresh)
        assertThat(repo.playerState.value.currentTrack).isNull()
    }

    @Test
    fun `play after an idle-out rebuilds the persisted queue`() = runTest {
        val fresh = idleOutAndReopen()
        coEvery { trackDao.getLastPlayedTrack() } returns null
        every { trackDao.getRecentlyAdded(any()) } returns flowOf(emptyList())

        repo.play()
        shadowOf(Looper.getMainLooper()).idle()

        coVerify(exactly = 1) { playbackResumer.buildResumePlan() }
        verify(exactly = 0) { fresh.play() }
    }
}
