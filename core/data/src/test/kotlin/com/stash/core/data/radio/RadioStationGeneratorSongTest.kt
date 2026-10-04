package com.stash.core.data.radio

import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmSimilarTrack
import com.stash.core.data.lastfm.LastFmTopTrack
import com.stash.data.ytmusic.YTMusicApiClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioStationGeneratorSongTest {
    private val lastFm: LastFmApiClient = mockk()
    private val yt: YTMusicApiClient = mockk()
    private fun gen() = RadioStationGenerator(lastFm, yt)

    @Test fun `song seed blends seed-artist tracks with similar tracks, resolves via search`() = runTest {
        coEvery { lastFm.getSimilarTracks("Lil Wayne", "A Milli", any()) } returns
            Result.success(listOf(
                LastFmSimilarTrack("Drake", "Forever", 0.9f),
                LastFmSimilarTrack("T.I.", "Whatever You Like", 0.7f)))
        coEvery { lastFm.getArtistTopTracks("Lil Wayne", any()) } returns
            Result.success(listOf(
                LastFmTopTrack("Lil Wayne", "6 Foot 7 Foot", 100),
                LastFmTopTrack("Lil Wayne", "Lollipop", 90)))
        // Song-radio candidates carry no Last.fm artwork and resolve via YT Music
        // search — which also yields the song's square album cover. Most resolve
        // WITH a cover; "T.I." resolves without one to exercise the video-frame
        // fallback so blank covers never reach Now Playing/queue/notif.
        coEvery { yt.searchCanonicalMatch(any(), any()) } answers {
            val artist = firstArg<String>()
            com.stash.data.ytmusic.CanonicalMatch(
                // A distinct id per song, in the 11-character shape real ids have:
                // only those are built into a thumbnail URL.
                videoId = "v%010d".format("$artist|${secondArg<String>()}".hashCode().toLong() and 0x7fffffffL),
                thumbnailUrl = if (artist == "T.I.") null
                    else "https://lh3.googleusercontent.com/cover=w1024-h1024",
            )
        }

        val (session, batch) = gen().start(RadioSeed.Song("A Milli", "Lil Wayne"))

        assertTrue(batch.isNotEmpty())
        assertTrue(batch.all { it.youtubeId != null })
        assertTrue("has seed share", batch.any { it.artist == "Lil Wayne" })
        assertTrue("has similar tracks", batch.any { it.artist != "Lil Wayne" })
        assertTrue(session.played.isNotEmpty())
        // Every emitted track has album art, and the resolved square cover is used
        // when the search returned one (crisp, no black bars) rather than the
        // low-res video frame.
        assertTrue("all tracks have album art", batch.all { !it.albumArtUrl.isNullOrBlank() })
        assertTrue("resolved cover used", batch.any { it.albumArtUrl!!.contains("lh3.googleusercontent.com") })
    }
}
