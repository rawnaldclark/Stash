package com.stash.core.media

import android.os.Looper
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.model.PlaybackSource
import com.stash.core.model.RepeatMode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * #462: after the OS kills the whole process, the app reopens to nothing —
 * the mini player only shows while a track is loaded, and the persisted queue
 * is rebuilt only by the Resume shortcut or by a play() there is no button for.
 *
 * The idle-stop design already survives a SERVICE death by keeping the last
 * player state on screen as a paused "ghost" that play() rebuilds from the
 * persisted queue. Process death is the one case with no ghost. These tests pin
 * the fix: seed that same ghost from the persisted session on a cold start,
 * without touching the service until the user presses play.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryColdStartTest {

    private val playbackStateStore: PlaybackStateStore = mockk(relaxed = true)
    private val trackDeletions = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    private val musicRepository: MusicRepository = mockk {
        every { this@mockk.trackDeletions } returns this@PlayerRepositoryColdStartTest.trackDeletions
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

    private fun entity(id: Long, title: String): TrackEntity = TrackEntity(
        id = id,
        title = title,
        artist = "Radiohead",
        album = "In Rainbows",
        durationMs = 290_000L,
        isDownloaded = false,
        isStreamable = true,
    )

    private val plan = PlaybackResumer.ResumePlan(
        tracks = listOf(entity(1L, "15 Step"), entity(2L, "Reckoner")),
        startIndex = 1,
        positionMs = 44_000L,
        isShuffled = true,
        repeatMode = RepeatMode.ALL,
        source = PlaybackSource.Unknown,
    )

    private lateinit var repo: PlayerRepositoryImpl

    @Before
    fun setUp() {
        // The seam mock reads as CONNECTED with an EMPTY timeline — a fresh
        // service after process death.
        every { controller.isConnected } returns true
        every { controller.mediaItemCount } returns 0
    }

    private fun build(): PlayerRepositoryImpl {
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
            playbackSessionBus = PlaybackSessionBus(),
        )
        repo.controllerDeferred = controller
        // init's coroutines run on the paused main looper: connect, then seed.
        shadowOf(Looper.getMainLooper()).idle()
        return repo
    }

    @Test
    fun `a cold start seeds the last session as a paused ghost`() = runTest {
        coEvery { playbackResumer.buildResumePlan() } returns plan

        val state = build().playerState.value

        assertThat(state.currentTrack?.id).isEqualTo(2L)
        assertThat(state.currentTrack?.title).isEqualTo("Reckoner")
        assertThat(state.isPlaying).isFalse()
        assertThat(state.positionMs).isEqualTo(44_000L)
        assertThat(state.durationMs).isEqualTo(290_000L)
        assertThat(state.queue.map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(state.currentIndex).isEqualTo(1)
        assertThat(state.isShuffleEnabled).isTrue()
        assertThat(state.repeatMode).isEqualTo(RepeatMode.ALL)
        // Nothing is fetched or prepared until the user presses play.
        verify(exactly = 0) { controller.setMediaItems(any<List<androidx.media3.common.MediaItem>>(), any(), any<Long>()) }
        verify(exactly = 0) { controller.prepare() }
        verify(exactly = 0) { controller.play() }
    }

    @Test
    fun `no ghost when nothing was persisted`() = runTest {
        coEvery { playbackResumer.buildResumePlan() } returns null

        assertThat(build().playerState.value.currentTrack).isNull()
    }

    @Test
    fun `no ghost when the service already holds a timeline`() = runTest {
        every { controller.mediaItemCount } returns 12
        coEvery { playbackResumer.buildResumePlan() } returns plan

        assertThat(build().playerState.value.currentTrack).isNull()
    }

    @Test
    fun `an empty-timeline state refresh does not wipe the ghost`() = runTest {
        coEvery { playbackResumer.buildResumePlan() } returns plan
        val repo = build()

        repo.updateState(controller) // what the player listener does on an idle empty player

        assertThat(repo.playerState.value.currentTrack?.id).isEqualTo(2L)
    }

    @Test
    fun `deleting the ghost's track drops the ghost`() = runTest {
        coEvery { playbackResumer.buildResumePlan() } returns plan
        val repo = build()

        assertThat(trackDeletions.tryEmit(2L)).isTrue()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(repo.playerState.value.currentTrack).isNull()
    }
}
