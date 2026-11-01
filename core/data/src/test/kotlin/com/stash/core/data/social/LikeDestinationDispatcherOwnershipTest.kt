package com.stash.core.data.social

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmSession
import com.stash.core.data.lastfm.LastFmSessionPreference
import com.stash.core.data.social.spotify.SpotifyLibraryApiClient
import com.stash.core.data.social.stash.StashLikedPlaylistRepository
import com.stash.core.data.social.ytmusic.YtMusicLibraryApiClient
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Never remove a like Stash didn't make. Hearting a song already in the listener's Spotify Liked Songs, YouTube Music likes or
 * Last.fm loves records it as theirs, so un-hearting it later in Stash never deletes it. When Stash can't tell, the heart still
 * goes out, but it is never taken back (never delete on uncertainty). A like stamped by a build from before the stamp epoch, which
 * never asked first, is never taken back either.
 */
class LikeDestinationDispatcherOwnershipTest {

    private val spotify = mockk<SpotifyLibraryApiClient>(relaxed = true)
    private val ytMusic = mockk<YtMusicLibraryApiClient>(relaxed = true)
    private val trackDao = mockk<TrackDao>(relaxed = true)
    private val lastFm = mockk<LastFmApiClient>(relaxed = true) {
        coEvery { setLoved(any(), any(), any(), any()) } returns Result.success(Unit)
    }
    private val lastFmSession = mockk<LastFmSessionPreference> {
        every { session } returns flowOf(LastFmSession(username = "u", sessionKey = "k"))
        every { firstArtistOnly } returns flowOf(false)
    }
    private val likedThere = mockk<LikedThereCheck>()

    /** The ownership record and the stamp epoch, in memory. */
    private val owned = mutableSetOf<Pair<Destination, Long>>()
    private var epoch: Long? = null
    private val ownership = mockk<LikeOwnership> {
        coEvery { markLikedByService(any(), any()) } answers { owned += firstArg<Destination>() to secondArg<Long>() }
        coEvery { clear(any(), any()) } answers { owned -= firstArg<Destination>() to secondArg<Long>() }
        coEvery { isLikedByService(any(), any()) } answers { (firstArg<Destination>() to secondArg<Long>()) in owned }
        coEvery { stampEpoch(any()) } answers { epoch ?: firstArg<Long>().also { epoch = it } }
        coEvery { epoch() } answers { epoch }
    }

    private val dispatcher = LikeDestinationDispatcher(
        spotify, ytMusic, mockk<StashLikedPlaylistRepository>(relaxed = true), trackDao, lastFm, lastFmSession, likedThere, ownership,
    )

    private val song = Track(id = 7L, title = "t", artist = "a", spotifyUri = "spotify:track:abc", youtubeId = "vid")

    /** The row as this build's like left it: stamped at the epoch. */
    private fun stampedNow() = epoch!!.let { song.copy(spotifySavedAt = it, ytMusicSavedAt = it, lastFmLovedAt = it) }

    private fun answer(spotify: LikedThere, yt: LikedThere, lastFm: LikedThere) {
        coEvery { likedThere.onSpotify(any()) } returns spotify
        coEvery { likedThere.onYouTubeMusic(any()) } returns yt
        coEvery { likedThere.onLastFm(any(), any()) } returns lastFm
    }

    private val all = setOf(Destination.SPOTIFY, Destination.YT_MUSIC, Destination.LAST_FM)

    private fun verifyNothingUnliked() {
        coVerify(exactly = 0) { spotify.removeTracks(any()) }
        coVerify(exactly = 0) { ytMusic.removeLike(any()) }
        coVerify(exactly = 0) { lastFm.setLoved(any(), any(), any(), false) }
    }

    @Test fun `already liked there - nothing is sent, and the like is stamped as the listener's own`() = runBlocking<Unit> {
        answer(LikedThere.YES, LikedThere.YES, LikedThere.YES)

        val results = dispatcher.like(song, all)

        assertThat(results.values.all { it.isSuccess }).isTrue()
        coVerify(exactly = 0) { spotify.saveTracks(any()) }
        coVerify(exactly = 0) { ytMusic.likeVideo(any()) }
        coVerify(exactly = 0) { lastFm.setLoved(any(), any(), any(), true) }
        // Stamped, so a second heart neither asks nor sends again
        coVerify(exactly = 1) { trackDao.markSpotifySaved(7L, any()) }
        coVerify(exactly = 1) { trackDao.markYtMusicSaved(7L, any()) }
        coVerify(exactly = 1) { trackDao.markLastFmLoved(7L, any()) }
        assertThat(owned).containsExactly(Destination.SPOTIFY to 7L, Destination.YT_MUSIC to 7L, Destination.LAST_FM to 7L)
    }

    @Test fun `and un-hearting it later never un-likes it, and the next heart asks again`() = runBlocking<Unit> {
        answer(LikedThere.YES, LikedThere.YES, LikedThere.YES)
        dispatcher.like(song, all)

        dispatcher.unlike(stampedNow(), all)

        verifyNothingUnliked()
        coVerify(exactly = 1) { trackDao.clearSpotifySaved(7L) }
        coVerify(exactly = 1) { trackDao.clearYtMusicSaved(7L) }
        coVerify(exactly = 1) { trackDao.clearLastFmLoved(7L) }
    }

    @Test fun `when the check fails the heart still goes out, but it is never taken back`() = runBlocking<Unit> {
        answer(LikedThere.UNKNOWN, LikedThere.UNKNOWN, LikedThere.UNKNOWN)

        dispatcher.like(song, all)

        coVerify(exactly = 1) { spotify.saveTracks(listOf("spotify:track:abc")) }
        coVerify(exactly = 1) { ytMusic.likeVideo("vid") }
        coVerify(exactly = 1) { lastFm.setLoved(any(), "a", "t", true) }
        coVerify(exactly = 1) { trackDao.markSpotifySaved(7L, any()) }
        assertThat(owned).hasSize(3)

        dispatcher.unlike(stampedNow(), all)
        verifyNothingUnliked()
    }

    @Test fun `an ownership record that can't be written leaves the row unstamped, never stamped as Stash's`() = runBlocking<Unit> {
        answer(LikedThere.UNKNOWN, LikedThere.UNKNOWN, LikedThere.UNKNOWN)
        coEvery { ownership.markLikedByService(any(), any()) } throws java.io.IOException("disk")

        val results = dispatcher.like(song, all)

        assertThat(results.values.all { it.isSuccess }).isTrue() // the like itself went out
        coVerify(exactly = 0) { trackDao.markSpotifySaved(any(), any()) }
        coVerify(exactly = 0) { trackDao.markYtMusicSaved(any(), any()) }
        coVerify(exactly = 0) { trackDao.markLastFmLoved(any(), any()) }
    }

    @Test fun `not liked there - Stash's like is stamped, and an un-heart may take it back`() = runBlocking<Unit> {
        answer(LikedThere.NO, LikedThere.NO, LikedThere.NO)
        owned += Destination.SPOTIFY to 7L // an earlier like there, since removed by the listener

        dispatcher.like(song, all)

        coVerify(exactly = 1) { spotify.saveTracks(any()) }
        coVerify(exactly = 1) { trackDao.markSpotifySaved(7L, any()) }
        coVerify(exactly = 1) { trackDao.markYtMusicSaved(7L, any()) }
        coVerify(exactly = 1) { trackDao.markLastFmLoved(7L, any()) }
        assertThat(owned).isEmpty() // now it is Stash's

        dispatcher.unlike(stampedNow(), all)
        coVerify(exactly = 1) { spotify.removeTracks(listOf("spotify:track:abc")) }
        coVerify(exactly = 1) { ytMusic.removeLike("vid") }
        coVerify(exactly = 1) { lastFm.setLoved(any(), any(), any(), false) }
    }

    @Test fun `a like stamped by an older build, which never asked first, is never un-liked`() = runBlocking<Unit> {
        // Hearted on v0.9.121, a song in the listener's Spotify Liked Songs since 2019: stamped, no record of whose it is
        val old = song.copy(spotifySavedAt = 1_000L, ytMusicSavedAt = 1_000L, lastFmLovedAt = 1_000L)

        dispatcher.unlike(old, all) // nothing stamped by this build yet: no epoch
        epoch = 2_000L // this build stamped something since
        dispatcher.unlike(old, all)

        verifyNothingUnliked()
        coVerify(exactly = 2) { trackDao.clearSpotifySaved(7L) }
    }

    @Test fun `an epoch that can't be read never un-likes`() = runBlocking<Unit> {
        coEvery { ownership.epoch() } throws java.io.IOException("disk")

        dispatcher.unlike(song.copy(spotifySavedAt = 5_000L), setOf(Destination.SPOTIFY))

        coVerify(exactly = 0) { spotify.removeTracks(any()) }
    }

    @Test fun `a stamped row recorded as the listener's own is still never un-liked`() = runBlocking<Unit> {
        epoch = 1L
        owned += Destination.SPOTIFY to 7L

        dispatcher.unlike(song.copy(spotifySavedAt = 1L), setOf(Destination.SPOTIFY))

        coVerify(exactly = 0) { spotify.removeTracks(any()) }
    }

    @Test fun `an ownership record that can't be read counts as the listener's own`() = runBlocking<Unit> {
        epoch = 1L
        coEvery { ownership.isLikedByService(any(), any()) } throws java.io.IOException("disk")

        dispatcher.unlike(song.copy(spotifySavedAt = 1L), setOf(Destination.SPOTIFY))

        coVerify(exactly = 0) { spotify.removeTracks(any()) }
    }

    @Test fun `a like that fails is not recorded either way`() = runBlocking<Unit> {
        answer(LikedThere.NO, LikedThere.NO, LikedThere.NO)
        coEvery { spotify.saveTracks(any()) } throws RuntimeException("down")

        val result = dispatcher.like(song, setOf(Destination.SPOTIFY))

        assertThat(result[Destination.SPOTIFY]!!.isFailure).isTrue()
        coVerify(exactly = 0) { trackDao.markSpotifySaved(any(), any()) }
        assertThat(owned).isEmpty()
    }

    @Test fun `Last_fm is asked with the artist it will be loved under`() = runBlocking<Unit> {
        every { lastFmSession.firstArtistOnly } returns flowOf(true)
        answer(LikedThere.NO, LikedThere.NO, LikedThere.NO)

        dispatcher.like(song.copy(artist = "Calvin Harris, Dua Lipa"), setOf(Destination.LAST_FM))

        coVerify { likedThere.onLastFm("Calvin Harris", "t") }
        coVerify { lastFm.setLoved(any(), "Calvin Harris", "t", true) }
    }
}
