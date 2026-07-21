package com.stash.core.media

import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.radio.RadioSeed
import com.stash.core.data.radio.RadioSession
import com.stash.core.data.radio.RadioStationGenerator
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.model.RadioStartResult
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryRadioTest {

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
    private val radioGenerator: RadioStationGenerator = mockk()

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
            playbackResumer = PlaybackResumer(playbackStateStore, trackDao),
            radioGenerator = radioGenerator,
        )
        repo.controllerDeferred = controller
    }

    private fun track(id: Long) = Track(id = id, title = "t$id", artist = "a", youtubeId = "v$id", isStreamable = true)

    @Test fun `startRadio returns StreamingOff and does not arm when streaming is off`() = runTest {
        coEvery { streamingPreference.current() } returns false

        val started = repo.startRadio(RadioSeed.Artist("My Bloody Valentine", "id"))

        assertThat(started).isEqualTo(RadioStartResult.StreamingOff)
        assertThat(repo.radioSeedLabel.value).isNull()
        coVerify(exactly = 0) { radioGenerator.start(any()) }
    }

    @Test fun `startRadio returns PlayerNotReady when the controller cannot connect`() = runTest {
        coEvery { streamingPreference.current() } returns true
        // Fresh repo with NO injected controller and a context whose bindService
        // fails: ensureController()'s real build path yields null.
        val deadContext = object : android.content.ContextWrapper(
            ApplicationProvider.getApplicationContext(),
        ) {
            override fun bindService(
                service: android.content.Intent,
                conn: android.content.ServiceConnection,
                flags: Int,
            ): Boolean = false
        }
        val coldRepo = PlayerRepositoryImpl(
            context = deadContext,
            playbackStateStore = playbackStateStore,
            musicRepository = musicRepository,
            streamingPreference = streamingPreference,
            streamResolver = streamResolver,
            streamUrlCache = streamUrlCache,
            connectivity = connectivity,
            trackDao = trackDao,
            playbackResumer = PlaybackResumer(playbackStateStore, trackDao),
            radioGenerator = radioGenerator,
        )

        val started = coldRepo.startRadio(RadioSeed.Song("t", "a"))

        assertThat(started).isEqualTo(RadioStartResult.PlayerNotReady)
        coVerify(exactly = 0) { radioGenerator.start(any()) }
    }

    @Test fun `startRadio sets queue, plays, arms, and exposes the seed label`() = runTest {
        coEvery { streamingPreference.current() } returns true
        val session = mockk<RadioSession>(relaxed = true)
        coEvery { radioGenerator.start(any()) } returns (session to listOf(track(1), track(2)))
        val items = slot<List<MediaItem>>()
        every { controller.setMediaItems(capture(items), any<Int>(), any<Long>()) } returns Unit

        val started = repo.startRadio(RadioSeed.Artist("My Bloody Valentine", "id"))

        assertThat(started).isEqualTo(RadioStartResult.Started)
        verify { controller.setMediaItems(any<List<MediaItem>>(), 0, 0L) }
        verify { controller.play() }
        assertThat(repo.radioSeedLabel.value).isEqualTo("My Bloody Valentine")
        // Streaming radio tracks have no filePath — every MediaItem MUST carry a
        // stash-resolve:// placeholder URI, else Media3's DefaultMediaSourceFactory
        // NPEs on the missing localConfiguration and nothing plays (regression guard).
        assertThat(items.captured).hasSize(2)
        items.captured.forEach { item ->
            assertThat(item.localConfiguration?.uri?.scheme).isEqualTo("stash-resolve")
        }
    }

    @Test fun `startRadio returns NoStation when the seed yields an empty batch`() = runTest {
        coEvery { streamingPreference.current() } returns true
        val session = mockk<RadioSession>(relaxed = true)
        coEvery { radioGenerator.start(any()) } returns (session to emptyList())

        assertThat(repo.startRadio(RadioSeed.Song("t", "a"))).isEqualTo(RadioStartResult.NoStation)
        assertThat(repo.radioSeedLabel.value).isNull()
    }

    @Test fun `startRadio rejects a batch when Online mode turns off during generation`() = runTest {
        var online = true
        coEvery { streamingPreference.current() } answers { online }
        val session = mockk<RadioSession>(relaxed = true)
        coEvery { radioGenerator.start(any()) } answers {
            online = false
            session to listOf(track(1))
        }

        val started = repo.startRadio(RadioSeed.Artist("MBV", "id"))

        assertThat(started).isFalse()
        assertThat(repo.radioSeedLabel.value).isNull()
        verify(exactly = 0) {
            controller.setMediaItems(any<List<MediaItem>>(), any<Int>(), any<Long>())
        }
    }

    @Test fun `setQueue disarms the station`() = runTest {
        coEvery { streamingPreference.current() } returns true
        val session = mockk<RadioSession>(relaxed = true)
        coEvery { radioGenerator.start(any()) } returns (session to listOf(track(1)))
        repo.startRadio(RadioSeed.Artist("MBV", "id"))
        assertThat(repo.radioSeedLabel.value).isEqualTo("MBV")

        repo.setQueue(listOf(track(9)))

        assertThat(repo.radioSeedLabel.value).isNull()
    }

    @Test fun `startRadio(keepCurrent) splices around the playing track without restarting it`() = runTest {
        // Now Playing "Start radio": keepCurrent=true means the seed IS the playing
        // track. startRadio must NOT setMediaItems/prepare/play (that restarts it) —
        // it keeps the current item playing and splices discoveries around it. The
        // seed is matched by normalized title|artist (NOT videoId), because the
        // playing item and the freshly-resolved seed track may have different ids.
        coEvery { streamingPreference.current() } returns true
        val session = mockk<RadioSession>(relaxed = true)
        // firstBatch has the seed (title/artist match) + one discovery. Give the
        // seed track a DIFFERENT videoId than any id-based guess to prove matching
        // is by title/artist, not id.
        val seedTrack = Track(id = 1L, title = "Pancake", artist = "The Swirlies",
            youtubeId = "freshlySearchedId", isStreamable = true)
        val discovery = Track(id = 2L, title = "Bell", artist = "Slowdive",
            youtubeId = "v2", isStreamable = true)
        coEvery { radioGenerator.start(any()) } returns (session to listOf(seedTrack, discovery))
        // Current item has NO youtube-id extras at all (Spotify-synced case).
        every { controller.currentMediaItem } returns MediaItem.Builder().setMediaId("999").build()
        every { controller.mediaItemCount } returns 3
        every { controller.currentMediaItemIndex } returns 1
        val appended = slot<List<MediaItem>>()
        every { controller.addMediaItems(capture(appended)) } returns Unit

        val started = repo.startRadio(
            RadioSeed.Song("Pancake", "The Swirlies", "v1"), keepCurrent = true,
        )

        assertThat(started).isEqualTo(RadioStartResult.Started)
        // Spliced: removed items after (2..3) and before (0..1) the current one.
        verify { controller.removeMediaItems(2, 3) }
        verify { controller.removeMediaItems(0, 1) }
        // Appended ONLY the discoveries (seed dropped — it's already playing).
        assertThat(appended.captured).hasSize(1)
        // Did NOT tear down / restart the seed track.
        verify(exactly = 0) { controller.setMediaItems(any<List<MediaItem>>(), any<Int>(), any<Long>()) }
        verify(exactly = 0) { controller.play() }
    }

    @Test fun `growRadio appends the next batch while a station is active`() = runTest {
        coEvery { streamingPreference.current() } returns true
        val session = mockk<RadioSession>(relaxed = true)
        coEvery { radioGenerator.start(any()) } returns (session to listOf(track(1)))
        coEvery { radioGenerator.nextBatch(session) } returns listOf(track(2), track(3))
        repo.startRadio(RadioSeed.Artist("MBV", "id"))

        repo.growRadio()

        coVerify { radioGenerator.nextBatch(session) }
        verify { controller.addMediaItems(any<List<MediaItem>>()) }
    }

    @Test fun `growRadio disarms without appending when Online mode was turned off`() = runTest {
        coEvery { streamingPreference.current() } returns true
        val session = mockk<RadioSession>(relaxed = true)
        coEvery { radioGenerator.start(any()) } returns (session to listOf(track(1)))
        coEvery { radioGenerator.nextBatch(session) } returns listOf(track(2))
        repo.startRadio(RadioSeed.Artist("MBV", "id"))
        coEvery { streamingPreference.current() } returns false

        repo.growRadio()

        assertThat(repo.radioSeedLabel.value).isNull()
        coVerify(exactly = 0) { radioGenerator.nextBatch(session) }
        verify(exactly = 0) { controller.addMediaItems(any<List<MediaItem>>()) }
    }

    @Test fun `growRadio disarms when Online mode turns off during generation`() = runTest {
        var online = true
        coEvery { streamingPreference.current() } answers { online }
        val session = mockk<RadioSession>(relaxed = true)
        coEvery { radioGenerator.start(any()) } returns (session to listOf(track(1)))
        coEvery { radioGenerator.nextBatch(session) } answers {
            online = false
            listOf(track(2))
        }
        repo.startRadio(RadioSeed.Artist("MBV", "id"))

        repo.growRadio()

        assertThat(repo.radioSeedLabel.value).isNull()
        verify(exactly = 0) { controller.addMediaItems(any<List<MediaItem>>()) }
    }

    @Test fun `growRadio is a no-op when no station is active`() = runTest {
        repo.growRadio()

        coVerify(exactly = 0) { radioGenerator.nextBatch(any()) }
        verify(exactly = 0) { controller.addMediaItems(any<List<MediaItem>>()) }
    }
}
