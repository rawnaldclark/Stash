package com.stash.core.data.social

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.lastfm.LastFmApiException
import com.stash.core.data.prefs.LikePreferences
import com.stash.core.data.social.spotify.SpotifyAuthException
import com.stash.core.data.social.spotify.SpotifyRateLimitException
import com.stash.core.data.social.stash.StashLikedPlaylistRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * LikeCoordinator contract:
 *  - the local Stash like is synchronous, sacred, and independent of mirroring
 *  - a mirrored heart is written to the outbox first, so a heart made offline (or before the app died) is never lost
 *  - mirror prefs and the song's row are read when an op is sent (gating + convergence); a switch turned off drops its ops
 *  - per-destination skip when the track lacks that platform id (and none is found)
 *  - ops are serialized with a min gap; a Spotify Retry-After rests Spotify alone, capped
 *  - a failed try is the worker's (back-off), never hammered by the next heart; a sign-in that expired is said once
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LikeCoordinatorTest {

    private val stashLiked = mockk<StashLikedPlaylistRepository>(relaxed = true)
    private val prefs = mockk<LikePreferences>()
    private val dispatcher = mockk<LikeDestinationDispatcher>()
    private val trackDao = mockk<TrackDao>()
    private val resolver = mockk<CrossPlatformLikeResolver>()
    private val storage = MemoryLikeOutboxStorage()
    private val outbox = LikeOutbox(storage)
    private val scheduler = FakeLikeMirrorScheduler()

    @Before fun stubResolverNoBackfill() {
        // Default: resolver finds nothing (relaxed would return "" for String?, which the backfill would treat as a real id).
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

    /** Last.fm defaults off so the Spotify/YouTube cases assert exactly what they mean. */
    private fun mirrorPrefs(spotify: Boolean, yt: Boolean, lastFm: Boolean = false) {
        coEvery { prefs.mirrorLikesSpotifyNow() } returns spotify
        coEvery { prefs.mirrorLikesYtMusicNow() } returns yt
        coEvery { prefs.mirrorLikesLastFmNow() } returns lastFm
    }

    private fun TestScope.coordinator(minGapMs: Long = 1_500L) = LikeCoordinator(
        stashLiked, prefs, dispatcher, trackDao, resolver, outbox, scheduler, backgroundScope, minGapMs,
        now = { testScheduler.currentTime },
    )

    private fun ok(vararg destinations: Destination) = destinations.associateWith { Result.success(Unit) }

    // -- Local first ------------------------------------------------------------------------------------------------------

    @Test fun `local like always happens, mirroring off touches nothing external`() = runTest {
        mirrorPrefs(spotify = false, yt = false)
        val c = coordinator()

        c.setLiked(7L, liked = true)
        runCurrent()

        coVerify { stashLiked.add(7L) }
        coVerify(exactly = 0) { dispatcher.like(any(), any()) }
        assertThat(storage.now.ops).isEmpty()
        assertThat(scheduler.scheduled).isEmpty()
    }

    @Test fun `local failure propagates to caller and nothing is queued`() = runTest {
        mirrorPrefs(spotify = true, yt = false)
        coEvery { stashLiked.add(7L) } throws RuntimeException("db full")
        val c = coordinator()

        try {
            c.setLiked(7L, liked = true)
            fail("expected local failure to propagate")
        } catch (_: RuntimeException) { }
        runCurrent()

        coVerify(exactly = 0) { dispatcher.like(any(), any()) }
        assertThat(storage.now.ops).isEmpty()
    }

    // -- Sending ----------------------------------------------------------------------------------------------------------

    @Test fun `mirrors like to enabled destinations the track has ids for, and the outbox empties`() = runTest {
        mirrorPrefs(spotify = true, yt = true)
        coEvery { trackDao.getById(7L) } returns entity(youtubeId = null) // no YT id, none found → skip YT
        coEvery { dispatcher.like(any(), any()) } returns ok(Destination.SPOTIFY)
        val c = coordinator()

        c.setLiked(7L, liked = true)
        advanceTimeBy(10_000L); runCurrent()

        coVerify(exactly = 1) { dispatcher.like(match { it.id == 7L }, setOf(Destination.SPOTIFY)) }
        coVerify(exactly = 0) { dispatcher.unlike(any(), any()) }
        assertThat(storage.now.ops).isEmpty()
        // The worker is only a safety net for a process that dies first
        assertThat(scheduler.scheduled).containsExactly(LikeCoordinator.SAFETY_NET_DELAY_MS)
    }

    @Test fun `like backfills a missing platform id so both destinations fire`() = runTest {
        mirrorPrefs(spotify = true, yt = true)
        coEvery { trackDao.getById(7L) } returns entity(id = 7L, spotifyUri = "spotify:track:abc", youtubeId = null)
        coEvery { resolver.ensureYoutubeId(any()) } returns "resolvedVid"
        coEvery { dispatcher.like(any(), any()) } returns ok(Destination.SPOTIFY, Destination.YT_MUSIC)
        val c = coordinator()

        c.setLiked(7L, liked = true)
        advanceTimeBy(10_000L); runCurrent()

        coVerify { resolver.ensureYoutubeId(match { it.id == 7L }) }
        coVerify {
            dispatcher.like(match { it.youtubeId == "resolvedVid" }, setOf(Destination.SPOTIFY, Destination.YT_MUSIC))
        }
    }

    @Test fun `unlike never backfills`() = runTest {
        mirrorPrefs(spotify = true, yt = true)
        coEvery { trackDao.getById(7L) } returns entity(id = 7L, spotifyUri = "spotify:track:abc", youtubeId = null)
        coEvery { dispatcher.unlike(any(), any()) } returns ok(Destination.SPOTIFY)
        val c = coordinator()

        c.setLiked(7L, liked = false)
        advanceTimeBy(10_000L); runCurrent()

        coVerify(exactly = 0) { resolver.ensureYoutubeId(any()) }
        coVerify(exactly = 0) { resolver.ensureSpotifyUri(any()) }
    }

    @Test fun `un-heart routes to dispatcher unlike`() = runTest {
        mirrorPrefs(spotify = true, yt = false)
        coEvery { trackDao.getById(7L) } returns entity()
        coEvery { dispatcher.unlike(any(), any()) } returns ok(Destination.SPOTIFY)
        val c = coordinator()

        c.setLiked(7L, liked = false)
        advanceTimeBy(10_000L); runCurrent()

        coVerify { stashLiked.remove(7L) }
        coVerify { dispatcher.unlike(any(), setOf(Destination.SPOTIFY)) }
    }

    // -- A browser's likes, through the link -------------------------------------------------------------------------------

    @Test fun `a browser's like and unlike go on as a heart made here does, and the like here is left alone`() = runTest {
        mirrorPrefs(spotify = true, yt = false, lastFm = true)
        coEvery { trackDao.getById(7L) } returns entity()
        coEvery { dispatcher.like(any(), any()) } returns ok(Destination.SPOTIFY, Destination.LAST_FM)
        coEvery { dispatcher.unlike(any(), any()) } returns ok(Destination.SPOTIFY, Destination.LAST_FM)
        val c = coordinator()

        c.mirrorOnly(7L, liked = true)
        advanceTimeBy(10_000L); runCurrent()
        c.mirrorOnly(7L, liked = false)
        advanceTimeBy(10_000L); runCurrent()

        // "Love on Last.fm too" counts as for a heart made here
        coVerify(exactly = 1) { dispatcher.like(match { it.id == 7L }, setOf(Destination.SPOTIFY, Destination.LAST_FM)) }
        coVerify(exactly = 1) { dispatcher.unlike(match { it.id == 7L }, setOf(Destination.SPOTIFY, Destination.LAST_FM)) }
        coVerify(exactly = 0) { stashLiked.add(any()) }
        coVerify(exactly = 0) { stashLiked.remove(any()) }
        assertThat(storage.now.ops).isEmpty()
    }

    @Test fun `with every switch off, a browser's like goes nowhere`() = runTest {
        mirrorPrefs(spotify = false, yt = false, lastFm = false)
        val c = coordinator()

        c.mirrorOnly(7L, liked = true)
        c.mirrorOnly(8L, liked = false)
        advanceTimeBy(10_000L); runCurrent()

        coVerify(exactly = 0) { dispatcher.like(any(), any()) }
        coVerify(exactly = 0) { dispatcher.unlike(any(), any()) }
        assertThat(storage.now.ops).isEmpty()
        assertThat(scheduler.scheduled).isEmpty()
    }

    @Test fun `ops are serialized with the min gap between external calls`() = runTest {
        mirrorPrefs(spotify = true, yt = false)
        coEvery { trackDao.getById(any()) } answers { entity(id = firstArg()) }
        coEvery { dispatcher.like(any(), any()) } returns ok(Destination.SPOTIFY)
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

    @Test fun `liking a cloud song never looks it up on Spotify or YouTube, Last_fm still fires`() = runTest {
        mirrorPrefs(spotify = true, yt = true, lastFm = true)
        coEvery { trackDao.getById(7L) } returns entity(spotifyUri = null, youtubeId = null)
            .copy(source = com.stash.core.model.MusicSource.CLOUD)
        coEvery { dispatcher.like(any(), any()) } returns ok(Destination.LAST_FM)
        val c = coordinator()

        c.setLiked(7L, liked = true)
        advanceTimeBy(10_000L); runCurrent()

        coVerify(exactly = 0) { resolver.ensureSpotifyUri(any()) }
        coVerify(exactly = 0) { resolver.ensureYoutubeId(any()) }
        coVerify(exactly = 1) { dispatcher.like(any(), setOf(Destination.LAST_FM)) }
    }

    @Test fun `SoundCloud never resolves missing ids by words for like mirroring`() = runTest {
        mirrorPrefs(spotify = true, yt = true, lastFm = true)
        coEvery { trackDao.getById(7L) } returns entity(spotifyUri = null, youtubeId = null)
            .copy(source = com.stash.core.model.MusicSource.SOUNDCLOUD, soundCloudId = "123")
        coEvery { dispatcher.like(any(), any()) } returns ok(Destination.LAST_FM)
        coordinator().setLiked(7L, liked = true)
        advanceTimeBy(10_000L); runCurrent()
        coVerify(exactly = 0) { resolver.ensureSpotifyUri(any()) }
        coVerify(exactly = 0) { resolver.ensureYoutubeId(any()) }
        coVerify(exactly = 1) { dispatcher.like(any(), setOf(Destination.LAST_FM)) }
    }

    @Test fun `track gone from DB is a silent skip, and nothing is left waiting`() = runTest {
        mirrorPrefs(spotify = true, yt = false)
        coEvery { trackDao.getById(7L) } returns null
        val c = coordinator()

        c.setLiked(7L, liked = true)
        advanceTimeBy(10_000L); runCurrent()

        coVerify(exactly = 0) { dispatcher.like(any(), any()) }
        assertThat(storage.now.ops).isEmpty()
    }

    /**
     * Last.fm needs no platform id (it matches on artist + title), so it must fire for a track that has neither a spotifyUri
     * nor a youtubeId. The other two skip such a track entirely.
     */
    @Test fun `Last_fm mirrors even when the track has no platform ids`() = runTest {
        mirrorPrefs(spotify = false, yt = false, lastFm = true)
        coEvery { trackDao.getById(7L) } returns entity(spotifyUri = null, youtubeId = null)
        coEvery { dispatcher.like(any(), any()) } returns ok(Destination.LAST_FM)

        coordinator().setLiked(7L, liked = true)
        runCurrent()

        coVerify { dispatcher.like(any(), setOf(Destination.LAST_FM)) }
    }

    // -- Offline, and the outbox ------------------------------------------------------------------------------------------

    @Test fun `a heart made offline waits, says so, and goes the moment the connection is back`() = runTest {
        mirrorPrefs(spotify = true, yt = false)
        coEvery { trackDao.getById(7L) } returns entity()
        coEvery { dispatcher.like(any(), any()) } returns ok(Destination.SPOTIFY)
        scheduler.isOnline = false
        val c = coordinator()

        c.setLiked(7L, liked = true)
        runCurrent()

        coVerify(exactly = 0) { dispatcher.like(any(), any()) }
        val waiting = storage.now.ops.single()
        assertThat(waiting.lastError).isEqualTo(LikeMirrorError.OFFLINE)
        assertThat(waiting.attempts).isEqualTo(0) // the connection is never a try
        assertThat(c.status(Destination.SPOTIFY).first().state).isEqualTo(LikeMirrorStatus.State.OFFLINE)
        assertThat(scheduler.scheduled).contains(0L) // the worker waits for the network too, in case the app is gone by then

        scheduler.backOnline()
        runCurrent()

        coVerify(exactly = 1) { dispatcher.like(match { it.id == 7L }, setOf(Destination.SPOTIFY)) }
        assertThat(storage.now.ops).isEmpty()
        assertThat(c.status(Destination.SPOTIFY).first()).isEqualTo(LikeMirrorStatus.UP_TO_DATE)
    }

    @Test fun `like then unlike made offline coalesce into one unlike, and no like is ever sent`() = runTest {
        mirrorPrefs(spotify = true, yt = false, lastFm = true)
        coEvery { trackDao.getById(7L) } returns entity()
        coEvery { dispatcher.unlike(any(), any()) } returns ok(Destination.SPOTIFY, Destination.LAST_FM)
        scheduler.isOnline = false
        val c = coordinator()

        c.setLiked(7L, liked = true)
        c.setLiked(7L, liked = false)
        runCurrent()
        assertThat(storage.now.ops).hasSize(2) // one per service, not four

        scheduler.backOnline()
        runCurrent()

        coVerify(exactly = 0) { dispatcher.like(any(), any()) }
        coVerify(exactly = 1) { dispatcher.unlike(match { it.id == 7L }, setOf(Destination.SPOTIFY, Destination.LAST_FM)) }
        assertThat(storage.now.ops).isEmpty()
    }

    @Test fun `what a killed process left waiting is sent when the next one starts`() = runTest {
        mirrorPrefs(spotify = true, yt = false)
        coEvery { trackDao.getById(7L) } returns entity()
        coEvery { dispatcher.like(any(), any()) } returns ok(Destination.SPOTIFY)
        storage.update { LikeOutbox.put(it, 7L, liked = true, destinations = setOf(Destination.SPOTIFY), now = 0L) }

        coordinator()
        runCurrent()

        coVerify(exactly = 1) { dispatcher.like(match { it.id == 7L }, setOf(Destination.SPOTIFY)) }
        assertThat(storage.now.ops).isEmpty()
    }

    @Test fun `a switch turned off drops what was waiting for it`() = runTest {
        mirrorPrefs(spotify = true, yt = false)
        scheduler.isOnline = false
        val c = coordinator()
        c.setLiked(7L, liked = true)
        runCurrent()
        assertThat(storage.now.ops).hasSize(1)

        mirrorPrefs(spotify = false, yt = false)
        scheduler.backOnline()
        runCurrent()

        coVerify(exactly = 0) { dispatcher.like(any(), any()) }
        assertThat(storage.now.ops).isEmpty()
    }

    @Test fun `an id that couldn't be looked up offline waits for the network instead of being dropped`() = runTest {
        mirrorPrefs(spotify = false, yt = true)
        coEvery { trackDao.getById(7L) } returns entity(spotifyUri = null, youtubeId = null)
        // The search fails because the connection just dropped
        coEvery { resolver.ensureYoutubeId(any()) } answers { scheduler.isOnline = false; null }
        val c = coordinator()

        c.setLiked(7L, liked = true)
        runCurrent()
        assertThat(storage.now.ops.single().lastError).isEqualTo(LikeMirrorError.OFFLINE)

        coEvery { resolver.ensureYoutubeId(any()) } returns "found"
        coEvery { dispatcher.like(any(), any()) } returns ok(Destination.YT_MUSIC)
        scheduler.backOnline()
        runCurrent()

        coVerify(exactly = 1) { dispatcher.like(match { it.youtubeId == "found" }, setOf(Destination.YT_MUSIC)) }
        assertThat(storage.now.ops).isEmpty()
    }

    // -- Failures ---------------------------------------------------------------------------------------------------------

    @Test fun `a refusal stays waiting for the worker's back-off, and the next heart doesn't hammer it`() = runTest {
        mirrorPrefs(spotify = true, yt = false)
        coEvery { trackDao.getById(any()) } answers { entity(id = firstArg()) }
        coEvery { dispatcher.like(match { it.id == 1L }, any()) } returns
            mapOf(Destination.SPOTIFY to Result.failure(RuntimeException("down")))
        coEvery { dispatcher.like(match { it.id == 2L }, any()) } returns ok(Destination.SPOTIFY)
        val c = coordinator(minGapMs = 0L)

        c.setLiked(1L, liked = true)
        runCurrent()
        val failed = storage.now.ops.single()
        assertThat(failed.attempts).isEqualTo(1)
        assertThat(failed.lastError).isEqualTo(LikeMirrorError.REFUSED)
        assertThat(scheduler.scheduled).contains(0L) // the worker takes it from here
        assertThat(c.status(Destination.SPOTIFY).first().state).isEqualTo(LikeMirrorStatus.State.RETRYING)

        c.setLiked(2L, liked = true)
        runCurrent()
        coVerify(exactly = 1) { dispatcher.like(match { it.id == 1L }, any()) } // not tried again in-process

        // The worker's run tries it again
        coEvery { dispatcher.like(match { it.id == 1L }, any()) } returns ok(Destination.SPOTIFY)
        val report = c.drainAll(LikeMirrorWorker.MAX_GROUPS_PER_RUN)
        assertThat(report.sent).isEqualTo(1)
        assertThat(report.retryable).isEqualTo(0)
        assertThat(storage.now.ops).isEmpty()
    }

    @Test fun `enough refusals park the op until tap to retry, which sends it at once`() = runTest {
        mirrorPrefs(spotify = true, yt = false)
        coEvery { trackDao.getById(7L) } returns entity()
        coEvery { dispatcher.like(any(), any()) } returns mapOf(Destination.SPOTIFY to Result.failure(RuntimeException("no")))
        val c = coordinator(minGapMs = 0L)
        c.setLiked(7L, liked = true)
        runCurrent()
        repeat(LikeOutbox.MAX_ATTEMPTS - 1) { c.drainAll(LikeMirrorWorker.MAX_GROUPS_PER_RUN) }

        assertThat(storage.now.ops.single().parked).isTrue()
        assertThat(c.drainAll(LikeMirrorWorker.MAX_GROUPS_PER_RUN).retryable).isEqualTo(0) // the worker stops
        val status = c.status(Destination.SPOTIFY).first()
        assertThat(status.state).isEqualTo(LikeMirrorStatus.State.NEEDS_YOU)
        assertThat(status.error).isEqualTo(LikeMirrorError.REFUSED)

        coEvery { dispatcher.like(any(), any()) } returns ok(Destination.SPOTIFY)
        c.retry(Destination.SPOTIFY)
        runCurrent()

        assertThat(storage.now.ops).isEmpty()
        coVerify(exactly = LikeOutbox.MAX_ATTEMPTS + 1) { dispatcher.like(any(), any()) }
    }

    @Test fun `a refusal holds that service back for the rest of the drain, and the others go on`() = runTest {
        mirrorPrefs(spotify = true, yt = true)
        coEvery { trackDao.getById(any()) } answers { entity(id = firstArg()) }
        coEvery { dispatcher.like(any(), any()) } answers {
            secondArg<Set<Destination>>().associateWith {
                if (it == Destination.SPOTIFY) Result.failure<Unit>(RuntimeException("no")) else Result.success(Unit)
            }
        }
        scheduler.isOnline = false
        val c = coordinator(minGapMs = 0L)
        (1L..3L).forEach { c.setLiked(it, liked = true) }
        runCurrent()
        scheduler.isOnline = true

        c.drainAll(LikeMirrorWorker.MAX_GROUPS_PER_RUN)

        // Spotify said no to the first song and was asked nothing more; YouTube Music took all three
        coVerify(exactly = 1) { dispatcher.like(any(), match { Destination.SPOTIFY in it }) }
        coVerify(exactly = 3) { dispatcher.like(any(), match { Destination.YT_MUSIC in it }) }
        assertThat(storage.now.ops.map { it.trackId to it.destination }).containsExactly(
            1L to Destination.SPOTIFY, 2L to Destination.SPOTIFY, 3L to Destination.SPOTIFY,
        )
    }

    @Test fun `a song a service keeps refusing goes after the new hearts, never in front of them`() = runTest {
        mirrorPrefs(spotify = true, yt = false)
        coEvery { trackDao.getById(any()) } answers { entity(id = firstArg()) }
        coEvery { dispatcher.like(match { it.id == 1L }, any()) } returns
            mapOf(Destination.SPOTIFY to Result.failure(RuntimeException("no")))
        coEvery { dispatcher.like(match { it.id == 2L }, any()) } returns ok(Destination.SPOTIFY)
        scheduler.isOnline = false
        val c = coordinator(minGapMs = 0L)
        c.setLiked(1L, liked = true)
        c.setLiked(2L, liked = true)
        runCurrent()
        storage.update { s -> s.copy(ops = s.ops.map { if (it.trackId == 1L) it.copy(attempts = 3, lastError = LikeMirrorError.REFUSED) else it }) }
        scheduler.isOnline = true

        c.drainAll(LikeMirrorWorker.MAX_GROUPS_PER_RUN)

        // The new heart went first, so the refusal after it held nothing back
        coVerify(exactly = 1) { dispatcher.like(match { it.id == 2L }, any()) }
        coVerify(exactly = 1) { dispatcher.like(match { it.id == 1L }, any()) }
        assertThat(storage.now.ops.single().attempts).isEqualTo(4)
    }

    @Test fun `a parked refusal gets one more try when the service takes a like again`() = runTest {
        mirrorPrefs(spotify = true, yt = false)
        coEvery { trackDao.getById(any()) } answers { entity(id = firstArg()) }
        coEvery { dispatcher.like(match { it.id == 1L }, any()) } returns
            mapOf(Destination.SPOTIFY to Result.failure(RuntimeException("no")))
        coEvery { dispatcher.like(match { it.id == 2L }, any()) } returns ok(Destination.SPOTIFY)
        val c = coordinator(minGapMs = 0L)
        c.setLiked(1L, liked = true)
        runCurrent()
        repeat(LikeOutbox.MAX_ATTEMPTS - 1) { c.drainAll(LikeMirrorWorker.MAX_GROUPS_PER_RUN) }
        assertThat(storage.now.ops.single().parked).isTrue()

        c.setLiked(2L, liked = true) // Spotify takes this one
        runCurrent()
        assertThat(storage.now.ops.single().parked).isFalse()
        assertThat(c.drainAll(LikeMirrorWorker.MAX_GROUPS_PER_RUN).retryable).isEqualTo(0) // refused once more: parked again

        coVerify(exactly = LikeOutbox.MAX_ATTEMPTS + 1) { dispatcher.like(match { it.id == 1L }, any()) }
        assertThat(c.status(Destination.SPOTIFY).first().state).isEqualTo(LikeMirrorStatus.State.NEEDS_YOU)
    }

    @Test fun `a backlog trickles - the in-process sender sends 20 a wake and leaves the rest to the worker`() = runTest {
        mirrorPrefs(spotify = true, yt = false)
        coEvery { trackDao.getById(any()) } answers { entity(id = firstArg()) }
        coEvery { dispatcher.like(any(), any()) } returns ok(Destination.SPOTIFY)
        scheduler.isOnline = false
        val c = coordinator(minGapMs = 0L)
        (1L..25L).forEach { c.setLiked(it, liked = true) }
        runCurrent()
        scheduler.scheduled.clear()

        scheduler.backOnline()
        runCurrent()

        coVerify(exactly = LikeCoordinator.IN_PROCESS_MAX_GROUPS) { dispatcher.like(any(), any()) }
        assertThat(storage.now.ops).hasSize(25 - LikeCoordinator.IN_PROCESS_MAX_GROUPS)
        assertThat(scheduler.scheduled).contains(0L) // the worker takes the rest, on its back-off
    }

    @Test fun `a song the service will never know is dropped, not retried`() = runTest {
        mirrorPrefs(spotify = false, yt = false, lastFm = true)
        coEvery { trackDao.getById(7L) } returns entity()
        coEvery { dispatcher.like(any(), any()) } returns
            mapOf(Destination.LAST_FM to Result.failure(LastFmApiException(6, "Track not found")))
        val c = coordinator()

        c.setLiked(7L, liked = true)
        runCurrent()

        assertThat(storage.now.ops).isEmpty()
    }

    @Test fun `a Spotify Retry-After rests Spotify alone, and YouTube Music goes on`() = runTest {
        mirrorPrefs(spotify = true, yt = true)
        coEvery { trackDao.getById(any()) } answers { entity(id = firstArg()) }
        coEvery { dispatcher.like(match { it.id == 1L }, any()) } returns mapOf(
            Destination.SPOTIFY to Result.failure(SpotifyRateLimitException(30)),
            Destination.YT_MUSIC to Result.success(Unit),
        )
        coEvery { dispatcher.like(match { it.id == 2L }, any()) } answers { secondArg<Set<Destination>>().associateWith { Result.success(Unit) } }
        val c = coordinator(minGapMs = 1_500L)

        c.setLiked(1L, liked = true)
        c.setLiked(2L, liked = true)
        advanceTimeBy(5_000L); runCurrent()

        // Song 2 reached YouTube Music; Spotify rests
        coVerify(exactly = 1) { dispatcher.like(match { it.id == 2L }, setOf(Destination.YT_MUSIC)) }
        assertThat(storage.now.ops.map { it.trackId to it.destination })
            .containsExactly(1L to Destination.SPOTIFY, 2L to Destination.SPOTIFY)

        // Inside the Retry-After the worker leaves Spotify alone…
        coEvery { dispatcher.like(match { it.id == 1L }, any()) } returns ok(Destination.SPOTIFY)
        assertThat(c.drainAll(LikeMirrorWorker.MAX_GROUPS_PER_RUN).sent).isEqualTo(0)
        // …and after it, both go
        advanceTimeBy(30_000L)
        assertThat(c.drainAll(LikeMirrorWorker.MAX_GROUPS_PER_RUN).sent).isEqualTo(2)
        assertThat(storage.now.ops).isEmpty()
    }

    @Test fun `an absurd Retry-After is capped`() = runTest {
        // Spotify has handed out 86400 s (24 h). Obeying that verbatim once froze every like for a day.
        mirrorPrefs(spotify = true, yt = false)
        coEvery { trackDao.getById(any()) } answers { entity(id = firstArg()) }
        coEvery { dispatcher.like(any(), any()) } returns mapOf(Destination.SPOTIFY to Result.failure(SpotifyRateLimitException(86_400)))
        val c = coordinator(minGapMs = 0L)
        c.setLiked(1L, liked = true)
        runCurrent()

        coEvery { dispatcher.like(any(), any()) } returns ok(Destination.SPOTIFY)
        advanceTimeBy(LikeCoordinator.MAX_BACKOFF_MS - 1_000L)
        assertThat(c.drainAll(LikeMirrorWorker.MAX_GROUPS_PER_RUN).sent).isEqualTo(0)
        advanceTimeBy(2_000L)
        assertThat(c.drainAll(LikeMirrorWorker.MAX_GROUPS_PER_RUN).sent).isEqualTo(1)
    }

    @Test fun `an expired sign-in holds that service back and is said once, only to someone listening`() = runTest {
        mirrorPrefs(spotify = true, yt = false)
        coEvery { trackDao.getById(any()) } answers { entity(id = firstArg()) }
        coEvery { dispatcher.like(any(), any()) } returns mapOf(Destination.SPOTIFY to Result.failure(SpotifyAuthException()))
        val c = coordinator(minGapMs = 0L)

        // Nobody listening (the app in the background): nothing said, and nothing spent
        c.setLiked(1L, liked = true)
        runCurrent()

        c.mirrorFailures.test {
            c.setLiked(2L, liked = true)
            runCurrent()
            assertThat(awaitItem()).isEqualTo(Destination.SPOTIFY)

            c.setLiked(3L, liked = true)
            runCurrent()
            expectNoEvents() // once per service per session: no nagging
        }
        val status = c.status(Destination.SPOTIFY).first()
        assertThat(status.state).isEqualTo(LikeMirrorStatus.State.NEEDS_YOU)
        assertThat(status.error).isEqualTo(LikeMirrorError.SIGNED_OUT)
        assertThat(status.waiting).isEqualTo(3)

        // The worker tries the first one, then holds the rest back: one request, not three, against a dead sign-in
        coVerify(exactly = 3) { dispatcher.like(any(), any()) }
        c.drainAll(LikeMirrorWorker.MAX_GROUPS_PER_RUN)
        coVerify(exactly = 4) { dispatcher.like(any(), any()) }
    }

    @Test fun `once a service takes a like again, what its sign-in held back goes too`() = runTest {
        mirrorPrefs(spotify = true, yt = false)
        coEvery { trackDao.getById(any()) } answers { entity(id = firstArg()) }
        coEvery { dispatcher.like(any(), any()) } returns mapOf(Destination.SPOTIFY to Result.failure(SpotifyAuthException()))
        val c = coordinator(minGapMs = 0L)
        c.setLiked(1L, liked = true)
        runCurrent()
        assertThat(storage.now.ops.single().attempts).isEqualTo(1)

        // The listener reconnects Spotify and hearts another song
        coEvery { dispatcher.like(any(), any()) } returns ok(Destination.SPOTIFY)
        c.setLiked(2L, liked = true)
        runCurrent()

        assertThat(storage.now.ops).isEmpty()
        // Song 1: the try that met the expired sign-in, then the one after song 2 proved Spotify takes likes again
        coVerify(exactly = 2) { dispatcher.like(match { it.id == 1L }, setOf(Destination.SPOTIFY)) }
    }
}
