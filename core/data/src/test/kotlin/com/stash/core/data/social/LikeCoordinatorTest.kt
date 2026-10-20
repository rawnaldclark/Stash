package com.stash.core.data.social

import app.cash.turbine.test
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.LikePreferences
import com.stash.core.data.social.spotify.SpotifyRateLimitException
import com.stash.core.data.social.stash.StashLikedPlaylistRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * v0.9.52 LikeCoordinator contract:
 *  - local Stash like is synchronous, sacred, and independent of mirroring
 *  - mirror prefs are read at FIRE time (gating + convergence)
 *  - per-destination skip when the track lacks that platform id
 *  - ops are serialized with a min gap; Spotify Retry-After stretches the gap
 *  - external failures signal at most one snackbar per session
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LikeCoordinatorTest {

    private val stashLiked = mockk<StashLikedPlaylistRepository>(relaxed = true)
    private val prefs = mockk<LikePreferences>()
    private val dispatcher = mockk<LikeDestinationDispatcher>()
    private val trackDao = mockk<TrackDao>()
    private val resolver = mockk<CrossPlatformLikeResolver>()

    @org.junit.Before fun stubResolverNoBackfill() {
        // Default: resolver finds nothing (relaxed would return "" for String?,
        // which the backfill would treat as a real id). Tests that exercise
        // backfill override these.
        kotlinx.coroutines.runBlocking {
            coEvery { resolver.ensureSpotifyUri(any()) } returns null
            coEvery { resolver.ensureYoutubeId(any()) } returns null
        }
    }

    private fun entity(
        id: Long = 7L,
        spotifyUri: String? = "spotify:track:abc",
        youtubeId: String? = "vid123",
    ) = TrackEntity(
        id = id,
        title = "Title $id",
        artist = "Artist $id",
        canonicalTitle = "title $id",
        canonicalArtist = "artist $id",
        spotifyUri = spotifyUri,
        youtubeId = youtubeId,
    )

    /** Last.fm defaults off so existing cases keep asserting exactly what they did. */
    private fun mirrorPrefs(spotify: Boolean, yt: Boolean, lastFm: Boolean = false) {
        coEvery { prefs.mirrorLikesSpotifyNow() } returns spotify
        coEvery { prefs.mirrorLikesYtMusicNow() } returns yt
        coEvery { prefs.mirrorLikesLastFmNow() } returns lastFm
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(minGapMs: Long = 1_500L) =
        LikeCoordinator(stashLiked, prefs, dispatcher, trackDao, resolver, backgroundScope, minGapMs)

    @Test fun `local like always happens, mirroring off touches nothing external`() = runTest {
        mirrorPrefs(spotify = false, yt = false)
        val c = coordinator()

        c.setLiked(7L, liked = true)
        runCurrent()

        coVerify { stashLiked.add(7L) }
        coVerify(exactly = 0) { dispatcher.like(any(), any()) }
    }

    @Test fun `local failure propagates to caller and nothing is enqueued`() = runTest {
        mirrorPrefs(spotify = true, yt = false)
        coEvery { stashLiked.add(7L) } throws RuntimeException("db full")
        val c = coordinator()

        try {
            c.setLiked(7L, liked = true)
            fail("expected local failure to propagate")
        } catch (_: RuntimeException) { }
        runCurrent()

        coVerify(exactly = 0) { dispatcher.like(any(), any()) }
    }

    @Test fun `mirrors like to enabled destinations the track has ids for`() = runTest {
        mirrorPrefs(spotify = true, yt = true)
        coEvery { trackDao.getById(7L) } returns entity(youtubeId = null) // no YT id → skip YT
        coEvery { dispatcher.like(any(), any()) } returns
            mapOf(Destination.SPOTIFY to Result.success(Unit))
        val c = coordinator()

        c.setLiked(7L, liked = true)
        advanceTimeBy(10_000L); runCurrent()

        coVerify(exactly = 1) { dispatcher.like(match { it.id == 7L }, setOf(Destination.SPOTIFY)) }
        coVerify(exactly = 0) { dispatcher.unlike(any(), any()) }
    }

    @Test fun `like backfills a missing platform id so both destinations fire`() = runTest {
        mirrorPrefs(spotify = true, yt = true)
        // Spotify-only track — the YouTube id is missing and gets resolved.
        coEvery { trackDao.getById(7L) } returns
            entity(id = 7L, spotifyUri = "spotify:track:abc", youtubeId = null)
        coEvery { resolver.ensureYoutubeId(any()) } returns "resolvedVid"
        coEvery { dispatcher.like(any(), any()) } returns mapOf(
            Destination.SPOTIFY to Result.success(Unit),
            Destination.YT_MUSIC to Result.success(Unit),
        )
        val c = coordinator()

        c.setLiked(7L, liked = true)
        advanceTimeBy(10_000L); runCurrent()

        coVerify { resolver.ensureYoutubeId(match { it.id == 7L }) }
        coVerify {
            dispatcher.like(
                match { it.youtubeId == "resolvedVid" },
                setOf(Destination.SPOTIFY, Destination.YT_MUSIC),
            )
        }
    }

    @Test fun `unlike never backfills`() = runTest {
        mirrorPrefs(spotify = true, yt = true)
        coEvery { trackDao.getById(7L) } returns
            entity(id = 7L, spotifyUri = "spotify:track:abc", youtubeId = null)
        coEvery { dispatcher.unlike(any(), any()) } returns
            mapOf(Destination.SPOTIFY to Result.success(Unit))
        val c = coordinator()

        c.setLiked(7L, liked = false)
        advanceTimeBy(10_000L); runCurrent()

        coVerify(exactly = 0) { resolver.ensureYoutubeId(any()) }
        coVerify(exactly = 0) { resolver.ensureSpotifyUri(any()) }
    }

    @Test fun `un-heart routes to dispatcher unlike`() = runTest {
        mirrorPrefs(spotify = true, yt = false)
        coEvery { trackDao.getById(7L) } returns entity()
        coEvery { dispatcher.unlike(any(), any()) } returns
            mapOf(Destination.SPOTIFY to Result.success(Unit))
        val c = coordinator()

        c.setLiked(7L, liked = false)
        advanceTimeBy(10_000L); runCurrent()

        coVerify { stashLiked.remove(7L) }
        coVerify { dispatcher.unlike(any(), setOf(Destination.SPOTIFY)) }
    }

    @Test fun `ops are serialized with the min gap between external calls`() = runTest {
        mirrorPrefs(spotify = true, yt = false)
        coEvery { trackDao.getById(any()) } answers { entity(id = firstArg()) }
        coEvery { dispatcher.like(any(), any()) } returns
            mapOf(Destination.SPOTIFY to Result.success(Unit))
        val c = coordinator(minGapMs = 1_500L)

        c.setLiked(1L, liked = true)
        c.setLiked(2L, liked = true)
        runCurrent() // first op fires immediately

        coVerify(exactly = 1) { dispatcher.like(any(), any()) }

        advanceTimeBy(1_400L); runCurrent() // still inside the gap
        coVerify(exactly = 1) { dispatcher.like(any(), any()) }

        advanceTimeBy(200L); runCurrent() // gap elapsed
        coVerify(exactly = 2) { dispatcher.like(any(), any()) }
    }

    @Test fun `Spotify Retry-After stretches the gap`() = runTest {
        mirrorPrefs(spotify = true, yt = false)
        coEvery { trackDao.getById(any()) } answers { entity(id = firstArg()) }
        coEvery { dispatcher.like(any(), any()) } returnsMany listOf(
            mapOf(Destination.SPOTIFY to Result.failure(SpotifyRateLimitException(30))),
            mapOf(Destination.SPOTIFY to Result.success(Unit)),
        )
        val c = coordinator(minGapMs = 1_500L)

        c.setLiked(1L, liked = true)
        c.setLiked(2L, liked = true)
        runCurrent()

        advanceTimeBy(29_000L); runCurrent() // inside Retry-After window
        coVerify(exactly = 1) { dispatcher.like(any(), any()) }

        advanceTimeBy(2_000L); runCurrent()
        coVerify(exactly = 2) { dispatcher.like(any(), any()) }
    }

    @Test fun `an absurd Retry-After is capped so the queue is not frozen`() = runTest {
        // Spotify has handed out 86400s (24h). Obeying that verbatim froze the
        // single drain loop — and every other queued op — for a day. The sleep
        // is capped at 5 min; the next op must fire once the cap elapses.
        mirrorPrefs(spotify = true, yt = false)
        coEvery { trackDao.getById(any()) } answers { entity(id = firstArg()) }
        coEvery { dispatcher.like(any(), any()) } returnsMany listOf(
            mapOf(Destination.SPOTIFY to Result.failure(SpotifyRateLimitException(86_400))),
            mapOf(Destination.SPOTIFY to Result.success(Unit)),
        )
        val c = coordinator(minGapMs = 1_500L)

        c.setLiked(1L, liked = true)
        c.setLiked(2L, liked = true)
        runCurrent()
        coVerify(exactly = 1) { dispatcher.like(any(), any()) }

        // Well past the 5-min cap but nowhere near 24h.
        advanceTimeBy(5 * 60 * 1_000L + 1_000L); runCurrent()
        coVerify(exactly = 2) { dispatcher.like(any(), any()) }
    }

    @Test fun `external failure emits at most one snackbar signal per session`() = runTest {
        mirrorPrefs(spotify = true, yt = false)
        coEvery { trackDao.getById(any()) } answers { entity(id = firstArg()) }
        coEvery { dispatcher.like(any(), any()) } returns
            mapOf(Destination.SPOTIFY to Result.failure(RuntimeException("down")))
        val c = coordinator()

        c.mirrorFailures.test {
            c.setLiked(1L, liked = true)
            advanceTimeBy(10_000L); runCurrent()
            assertEquals("Couldn't sync your like — will retry", awaitItem())

            c.setLiked(2L, liked = true)
            advanceTimeBy(10_000L); runCurrent()
            expectNoEvents() // second failure stays quiet — no nagging
        }
    }

    @Test fun `liking a cloud song never looks it up on Spotify or YouTube, Last_fm still fires`() = runTest {
        mirrorPrefs(spotify = true, yt = true, lastFm = true)
        coEvery { trackDao.getById(7L) } returns entity(spotifyUri = null, youtubeId = null)
            .copy(source = com.stash.core.model.MusicSource.CLOUD)
        coEvery { dispatcher.like(any(), any()) } returns mapOf(Destination.LAST_FM to Result.success(Unit))
        val c = coordinator()

        c.setLiked(7L, liked = true)
        advanceTimeBy(10_000L); runCurrent()

        coVerify(exactly = 0) { resolver.ensureSpotifyUri(any()) }
        coVerify(exactly = 0) { resolver.ensureYoutubeId(any()) }
        coVerify(exactly = 1) { dispatcher.like(any(), setOf(Destination.LAST_FM)) }
    }

    @Test fun `track gone from DB is a silent skip`() = runTest {
        mirrorPrefs(spotify = true, yt = false)
        coEvery { trackDao.getById(7L) } returns null
        val c = coordinator()

        c.setLiked(7L, liked = true)
        advanceTimeBy(10_000L); runCurrent()

        coVerify(exactly = 0) { dispatcher.like(any(), any()) }
    }
    /**
     * Last.fm needs no platform id — it matches on artist + title — so it must fire
     * for a track that has neither a spotifyUri nor a youtubeId. The other two
     * destinations skip such a track entirely, so a naive "add if id present" would
     * have silently dropped every Last.fm love.
     */
    @Test fun `Last_fm mirrors even when the track has no platform ids`() = runTest {
        mirrorPrefs(spotify = false, yt = false, lastFm = true)
        coEvery { trackDao.getById(7L) } returns entity(spotifyUri = null, youtubeId = null)
        coEvery { dispatcher.like(any(), any()) } returns
            mapOf(Destination.LAST_FM to Result.success(Unit))

        coordinator().setLiked(7L, liked = true)
        runCurrent()

        coVerify { dispatcher.like(any(), setOf(Destination.LAST_FM)) }
    }

}
