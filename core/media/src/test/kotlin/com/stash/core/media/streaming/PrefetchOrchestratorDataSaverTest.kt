package com.stash.core.media.streaming

import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Data saver on mobile data: the 60 % look-ahead resolve of the next song is skipped (the player
 * resolves it when its own buffering reaches it). Off, or on Wi-Fi, it runs as before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrefetchOrchestratorDataSaverTest {

    private val streamResolver: StreamSourceRegistry = mockk {
        coEvery { resolve(any(), any(), any(), any()) } returns null
    }
    private val streamUrlCache: StreamUrlCache = mockk<StreamUrlCache>(relaxUnitFun = true).also { cache ->
        every { cache.get(any()) } returns null
    }
    private val track = TrackEntity(
        id = 5L, title = "Next Up", artist = "Artist", durationMs = 200_000L, youtubeId = "abc123", isStreamable = true,
    )
    private val trackDao: TrackDao = mockk { coEvery { getById(5L) } returns track }

    private fun orchestrator(dataSaver: Boolean) = PrefetchOrchestrator(
        streamResolver = streamResolver,
        streamUrlCache = streamUrlCache,
        trackDao = trackDao,
        qualityPolicy = mockk { coEvery { dataSaverApplies() } returns dataSaver },
    )

    @Test
    fun `on mobile data under Data saver the next song is not looked up ahead`() = runTest {
        orchestrator(dataSaver = true).onPlaybackProgress(this, nextTrackId = 5L, positionMs = 70_000L, durationMs = 100_000L)
        advanceUntilIdle()

        coVerify(exactly = 0) { streamResolver.resolve(any(), any(), any(), any()) }
        // Asked before the cache: a link saved on Wi-Fi isn't even read.
        verify(exactly = 0) { streamUrlCache.get(any()) }
    }

    @Test
    fun `where Data saver does not apply the next song is looked up as before`() = runTest {
        orchestrator(dataSaver = false).onPlaybackProgress(this, nextTrackId = 5L, positionMs = 70_000L, durationMs = 100_000L)
        advanceUntilIdle()

        coVerify(exactly = 1) { streamResolver.resolve(track, allowYouTube = true, allowYtDlp = true, speculative = true) }
    }
}
