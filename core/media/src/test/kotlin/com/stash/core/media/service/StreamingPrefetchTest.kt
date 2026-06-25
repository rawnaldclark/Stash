package com.stash.core.media.service

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.PrefetchOrchestrator
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrl
import com.stash.core.media.streaming.StreamUrlCache
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PrefetchOrchestrator], the testable extract of the
 * "pre-fetch next streamable track at 60 % played" feature wired into
 * [StashPlaybackService]. The service itself only acts as the periodic
 * trigger (a 5 s position-poll); all decision logic lives in the
 * orchestrator so it can be exercised without booting a
 * [androidx.media3.session.MediaSessionService] / [androidx.media3
 * .exoplayer.ExoPlayer].
 *
 * Each test wires fresh mocks of [StreamingPreference], [TrackDao],
 * [KennyyStreamResolver], [StreamUrlCache] and a [TestScope] coroutine
 * scope so the prefetch coroutine completes deterministically under
 * `advanceUntilIdle()`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamingPrefetchTest {

    private val streamingPreference: StreamingPreference = mockk()
    private val connectivity: ConnectivityMonitor = mockk()
    private val streamResolver: StreamSourceRegistry = mockk()
    private val streamUrlCache: StreamUrlCache = mockk(relaxUnitFun = true)
    private val trackDao: TrackDao = mockk()

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private lateinit var orchestrator: PrefetchOrchestrator

    @Before
    fun setUp() {
        // Orchestrator launches into the scope the caller passes in.
        // We set Main so any nested Dispatchers.Main hops in production
        // code (none today) wouldn't deadlock the test.
        Dispatchers.setMain(dispatcher)
        every { connectivity.isCellular() } returns false
        orchestrator = PrefetchOrchestrator(
            streamingPreference = streamingPreference,
            connectivity = connectivity,
            streamResolver = streamResolver,
            streamUrlCache = streamUrlCache,
            trackDao = trackDao,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun progress_below60Percent_doesNothing() = runTest {
        // Below threshold — no DAO hit, no resolver call, even with
        // streaming on and a valid next id.
        coEvery { streamingPreference.current() } returns true

        orchestrator.onPlaybackProgress(
            scope = testScope,
            nextTrackId = 99L,
            positionMs = 30_000L,
            durationMs = 100_000L, // 30 % — well below 60 %
        )
        testScope.advanceUntilIdle()

        coVerify(exactly = 0) { trackDao.getById(any()) }
        coVerify(exactly = 0) { streamResolver.resolve(any()) }
        verify(exactly = 0) { streamUrlCache.put(any(), any()) }
    }

    @Test
    fun progress_above60Percent_withCacheHit_doesNotResolve() = runTest {
        // Already cached → skip resolve, skip put.
        coEvery { streamingPreference.current() } returns true
        coEvery { streamUrlCache.get(99L) } returns
            StreamUrl(url = "https://cdn/cached?etsp=9999999999", expiresAtMs = Long.MAX_VALUE)

        orchestrator.onPlaybackProgress(
            scope = testScope,
            nextTrackId = 99L,
            positionMs = 80_000L,
            durationMs = 100_000L, // 80 % — past threshold
        )
        testScope.advanceUntilIdle()

        coVerify(exactly = 0) { trackDao.getById(any()) }
        coVerify(exactly = 0) { streamResolver.resolve(any()) }
        verify(exactly = 0) { streamUrlCache.put(any(), any()) }
    }

    @Test
    fun progress_above60Percent_withCacheMiss_resolvesAndCaches() = runTest {
        val nextTrack = streamableTrack(id = 99L)
        val resolved = StreamUrl(url = "https://cdn/fresh?etsp=1", expiresAtMs = 1_000L)

        coEvery { streamingPreference.current() } returns true
        coEvery { streamUrlCache.get(99L) } returns null
        coEvery { trackDao.getById(99L) } returns nextTrack
        coEvery { streamResolver.resolve(nextTrack) } returns resolved

        orchestrator.onPlaybackProgress(
            scope = testScope,
            nextTrackId = 99L,
            positionMs = 70_000L,
            durationMs = 100_000L,
        )
        testScope.advanceUntilIdle()

        coVerify(exactly = 1) { streamResolver.resolve(nextTrack) }
        verify(exactly = 1) { streamUrlCache.put(99L, resolved) }
    }

    @Test
    fun progress_above60Percent_butNextDownloaded_doesNotResolve() = runTest {
        // Next track is local → no need to stream. The orchestrator
        // burns its "attempted" budget for this id but does NOT call
        // the resolver.
        val nextTrack = streamableTrack(id = 99L).copy(
            isDownloaded = true,
            filePath = "/storage/music/song.flac",
        )
        coEvery { streamingPreference.current() } returns true
        coEvery { streamUrlCache.get(99L) } returns null
        coEvery { trackDao.getById(99L) } returns nextTrack

        orchestrator.onPlaybackProgress(
            scope = testScope,
            nextTrackId = 99L,
            positionMs = 80_000L,
            durationMs = 100_000L,
        )
        testScope.advanceUntilIdle()

        coVerify(exactly = 0) { streamResolver.resolve(any()) }
        verify(exactly = 0) { streamUrlCache.put(any(), any()) }
    }

    @Test
    fun progress_above60Percent_butNotStreamable_doesNotResolve() = runTest {
        // Defense-in-depth — a row CONFIRMED unstreamable (checked and
        // false) is never sent to a resolver. Unchecked rows
        // (isStreamableCheckedAt = null) DO resolve: the synced library
        // sits entirely unchecked since AvailabilityCheckWorker was
        // removed, and gating on the bare flag silently killed
        // next-track prefetch for all of it.
        val nextTrack = streamableTrack(id = 99L).copy(
            isStreamable = false,
            isStreamableCheckedAt = 1_700_000_000_000L,
        )
        coEvery { streamingPreference.current() } returns true
        coEvery { streamUrlCache.get(99L) } returns null
        coEvery { trackDao.getById(99L) } returns nextTrack

        orchestrator.onPlaybackProgress(
            scope = testScope,
            nextTrackId = 99L,
            positionMs = 80_000L,
            durationMs = 100_000L,
        )
        testScope.advanceUntilIdle()

        coVerify(exactly = 0) { streamResolver.resolve(any()) }
    }

    @Test
    fun progress_above60Percent_butStreamingOff_doesNotResolve() = runTest {
        coEvery { streamingPreference.current() } returns false

        orchestrator.onPlaybackProgress(
            scope = testScope,
            nextTrackId = 99L,
            positionMs = 80_000L,
            durationMs = 100_000L,
        )
        testScope.advanceUntilIdle()

        coVerify(exactly = 0) { trackDao.getById(any()) }
        coVerify(exactly = 0) { streamResolver.resolve(any()) }
        verify(exactly = 0) { streamUrlCache.put(any(), any()) }
    }

    @Test
    fun progress_above60Percent_butAlreadyAttempted_doesNotResolve() = runTest {
        // Same id polled twice past the threshold — only the first
        // attempt should fire. Guards against the periodic poll spamming
        // Kennyy after a failed resolve.
        val nextTrack = streamableTrack(id = 99L)
        coEvery { streamingPreference.current() } returns true
        coEvery { streamUrlCache.get(99L) } returns null
        coEvery { trackDao.getById(99L) } returns nextTrack
        coEvery { streamResolver.resolve(nextTrack) } returns null // simulate failure

        orchestrator.onPlaybackProgress(
            scope = testScope,
            nextTrackId = 99L,
            positionMs = 70_000L,
            durationMs = 100_000L,
        )
        testScope.advanceUntilIdle()

        // Second tick of the periodic poll, still on the same next id.
        orchestrator.onPlaybackProgress(
            scope = testScope,
            nextTrackId = 99L,
            positionMs = 90_000L,
            durationMs = 100_000L,
        )
        testScope.advanceUntilIdle()

        coVerify(exactly = 1) { streamResolver.resolve(nextTrack) }
    }

    @Test
    fun resetSession_allowsRetryOfPreviouslyAttemptedId() = runTest {
        // After a track transition the service calls resetSession(); a
        // subsequent prefetch for the same id (e.g. REPEAT_ONE looping
        // the same song) must be allowed to fire again.
        val nextTrack = streamableTrack(id = 99L)
        coEvery { streamingPreference.current() } returns true
        coEvery { streamUrlCache.get(99L) } returns null
        coEvery { trackDao.getById(99L) } returns nextTrack
        coEvery { streamResolver.resolve(nextTrack) } returns
            StreamUrl(url = "https://cdn/x?etsp=1", expiresAtMs = 1_000L)

        orchestrator.onPlaybackProgress(
            scope = testScope,
            nextTrackId = 99L,
            positionMs = 70_000L,
            durationMs = 100_000L,
        )
        testScope.advanceUntilIdle()

        orchestrator.resetSession()

        orchestrator.onPlaybackProgress(
            scope = testScope,
            nextTrackId = 99L,
            positionMs = 80_000L,
            durationMs = 100_000L,
        )
        testScope.advanceUntilIdle()

        coVerify(exactly = 2) { streamResolver.resolve(nextTrack) }
    }

    @Test
    fun progress_withNullNextId_doesNothing() = runTest {
        // End of queue — no next item to prefetch.
        coEvery { streamingPreference.current() } returns true

        orchestrator.onPlaybackProgress(
            scope = testScope,
            nextTrackId = null,
            positionMs = 80_000L,
            durationMs = 100_000L,
        )
        testScope.advanceUntilIdle()

        coVerify(exactly = 0) { streamResolver.resolve(any()) }
    }

    @Test
    fun progress_withUnknownDuration_doesNothing() = runTest {
        // Player reports duration <= 0 while content is still loading.
        // We can't compute a percentage so we skip — the next poll tick
        // will reach a real duration.
        coEvery { streamingPreference.current() } returns true

        orchestrator.onPlaybackProgress(
            scope = testScope,
            nextTrackId = 99L,
            positionMs = 80_000L,
            durationMs = -1L,
        )
        testScope.advanceUntilIdle()

        coVerify(exactly = 0) { streamResolver.resolve(any()) }
    }

    private fun streamableTrack(id: Long): TrackEntity = TrackEntity(
        id = id,
        title = "Track $id",
        artist = "Artist $id",
        album = "Album $id",
        durationMs = 200_000L,
        isDownloaded = false,
        isStreamable = true,
    )
}
