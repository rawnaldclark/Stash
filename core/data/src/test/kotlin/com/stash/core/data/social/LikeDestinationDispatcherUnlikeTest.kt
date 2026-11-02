package com.stash.core.data.social

import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.social.spotify.SpotifyLibraryApiClient
import com.stash.core.data.social.stash.StashLikedPlaylistRepository
import com.stash.core.data.social.ytmusic.YtMusicLibraryApiClient
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.every
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.52 symmetric un-like. The load-bearing rule: NEVER un-Like
 * what Stash never Liked — a destination only fires when its
 * `*_saved_at` timestamp is non-null, and a successful remote remove
 * clears that timestamp so a future re-heart re-fires.
 */
class LikeDestinationDispatcherUnlikeTest {

    private val spotify = mockk<SpotifyLibraryApiClient>(relaxed = true)
    private val ytMusic = mockk<YtMusicLibraryApiClient>(relaxed = true)
    private val stashLiked = mockk<StashLikedPlaylistRepository>(relaxed = true)
    private val trackDao = mockk<TrackDao>(relaxed = true)
    private val lastFm = mockk<com.stash.core.data.lastfm.LastFmApiClient>(relaxed = true)
    private val lastFmSession =
        mockk<com.stash.core.data.lastfm.LastFmSessionPreference>(relaxed = true)
    /** Nothing was liked there before Stash: the old behaviour, which these cases assert. */
    private val likedThere = mockk<LikedThereCheck> {
        coEvery { onSpotify(any()) } returns LikedThere.NO
        coEvery { onYouTubeMusic(any()) } returns LikedThere.NO
        coEvery { onLastFm(any(), any()) } returns LikedThere.NO
    }
    /** Stash's own likes, stamped by this build: at or after its stamp epoch, and not the listener's own. */
    private val ownership = mockk<LikeOwnership>(relaxed = true) {
        coEvery { isLikedByService(any(), any()) } returns false
        coEvery { epoch() } returns 100L
    }
    private val dispatcher =
        LikeDestinationDispatcher(spotify, ytMusic, stashLiked, trackDao, lastFm, lastFmSession, likedThere, ownership)

    private fun track(
        spotifySavedAt: Long? = null,
        ytMusicSavedAt: Long? = null,
        spotifyUri: String? = "spotify:track:abc",
        youtubeId: String? = "vid123",
    ) = Track(
        id = 7L,
        title = "t",
        artist = "a",
        spotifyUri = spotifyUri,
        youtubeId = youtubeId,
        spotifySavedAt = spotifySavedAt,
        ytMusicSavedAt = ytMusicSavedAt,
    )

    @Test fun `skips destination Stash never saved`() = runBlocking<Unit> {
        val result = dispatcher.unlike(track(spotifySavedAt = null), setOf(Destination.SPOTIFY))

        assertTrue(result[Destination.SPOTIFY]!!.isSuccess)
        coVerify(exactly = 0) { spotify.removeTracks(any()) }
        coVerify(exactly = 0) { trackDao.clearSpotifySaved(any()) }
    }

    @Test fun `removes on Spotify and clears dedup column when saved`() = runBlocking<Unit> {
        val result = dispatcher.unlike(track(spotifySavedAt = 111L), setOf(Destination.SPOTIFY))

        assertTrue(result[Destination.SPOTIFY]!!.isSuccess)
        coVerify { spotify.removeTracks(listOf("spotify:track:abc")) }
        coVerify { trackDao.clearSpotifySaved(7L) }
    }

    @Test fun `removes on YT Music and clears dedup column when saved`() = runBlocking<Unit> {
        val result = dispatcher.unlike(track(ytMusicSavedAt = 222L), setOf(Destination.YT_MUSIC))

        assertTrue(result[Destination.YT_MUSIC]!!.isSuccess)
        coVerify { ytMusic.removeLike("vid123") }
        coVerify { trackDao.clearYtMusicSaved(7L) }
    }

    @Test fun `API failure leaves dedup column untouched for organic retry`() = runBlocking<Unit> {
        coEvery { spotify.removeTracks(any()) } throws RuntimeException("boom")

        val result = dispatcher.unlike(track(spotifySavedAt = 111L), setOf(Destination.SPOTIFY))

        assertTrue(result[Destination.SPOTIFY]!!.isFailure)
        coVerify(exactly = 0) { trackDao.clearSpotifySaved(any()) }
    }

    @Test fun `unlike routes STASH to local repository remove`() = runBlocking<Unit> {
        val t = track().copy(stashLikedAt = 333L)

        val result = dispatcher.unlike(t, setOf(Destination.STASH))

        assertTrue(result[Destination.STASH]!!.isSuccess)
        coVerify { stashLiked.remove(7L) }
    }

    @Test fun `empty destination set is a no-op`() = runBlocking<Unit> {
        assertTrue(dispatcher.unlike(track(), emptySet()).isEmpty())
    }
    /**
     * Loves must use the SAME artist string as scrobbles.
     *
     * The first-artist toggle (#392) was applied to scrobbles only; loves still sent
     * the raw `track.artist`. With the toggle on that scrobbles "Calvin Harris" and
     * loves "Calvin Harris, Dua Lipa" — two different Last.fm catalog entries for one
     * track, which is exactly the mismatch the toggle exists to prevent.
     */
    @Test fun `love uses the primary artist when the toggle is on`() = runBlocking<Unit> {
        every { lastFmSession.session } returns kotlinx.coroutines.flow.flowOf(
            com.stash.core.data.lastfm.LastFmSession(username = "u", sessionKey = "k"),
        )
        every { lastFmSession.firstArtistOnly } returns kotlinx.coroutines.flow.flowOf(true)
        coEvery { lastFm.setLoved(any(), any(), any(), any()) } returns Result.success(Unit)

        dispatcher.like(
            track(spotifyUri = null, youtubeId = null).copy(artist = "Calvin Harris, Dua Lipa"),
            setOf(Destination.LAST_FM),
        )

        coVerify { lastFm.setLoved(any(), "Calvin Harris", any(), true) }
    }

    /** Toggle off: the full credit is preserved, unchanged behaviour. */
    @Test fun `love keeps the full artist when the toggle is off`() = runBlocking<Unit> {
        every { lastFmSession.session } returns kotlinx.coroutines.flow.flowOf(
            com.stash.core.data.lastfm.LastFmSession(username = "u", sessionKey = "k"),
        )
        every { lastFmSession.firstArtistOnly } returns kotlinx.coroutines.flow.flowOf(false)
        coEvery { lastFm.setLoved(any(), any(), any(), any()) } returns Result.success(Unit)

        dispatcher.like(
            track(spotifyUri = null, youtubeId = null).copy(artist = "Calvin Harris, Dua Lipa"),
            setOf(Destination.LAST_FM),
        )

        coVerify { lastFm.setLoved(any(), "Calvin Harris, Dua Lipa", any(), true) }
    }

}
