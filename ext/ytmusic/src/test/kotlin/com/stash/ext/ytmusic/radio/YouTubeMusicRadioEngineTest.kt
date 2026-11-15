package com.stash.ext.ytmusic.radio

import com.stash.core.auth.TokenManager
import com.stash.core.data.radio.RadioEngineId
import com.stash.core.data.radio.RadioSeed
import com.stash.core.model.MusicSource
import com.stash.data.ytmusic.ArtistRadioEndpoint
import com.stash.data.ytmusic.CanonicalMatch
import com.stash.data.ytmusic.RadioQueuePage
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.ArtistProfile
import com.stash.data.ytmusic.model.ArtistSummary
import com.stash.data.ytmusic.model.TrackSummary
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The YouTube Music engine against a mocked client: what it seeds from, what it asks
 * for, and what it hands the player.
 */
class YouTubeMusicRadioEngineTest {

    private val yt: YTMusicApiClient = mockk()
    private val tokenManager: TokenManager = mockk()

    private fun engine() = YouTubeMusicRadioEngine(yt, tokenManager)

    private fun summary(videoId: String, title: String = "T", artist: String = "A") = TrackSummary(
        videoId = videoId,
        title = title,
        artist = artist,
        album = "Album",
        durationSeconds = 200.0,
        thumbnailUrl = null,
    )

    @Test
    fun `it reports itself as the YouTube Music engine`() {
        assertEquals(RadioEngineId.YOUTUBE_MUSIC, engine().id)
    }

    /** No account means no personalised station, so the engines fall back instead of failing. */
    @Test
    fun `available toggles on the stored cookie`() = runTest {
        coEvery { tokenManager.getYouTubeCookie() } returns null
        assertFalse(engine().available())

        coEvery { tokenManager.getYouTubeCookie() } returns "SAPISID=abc; HSID=def"
        assertTrue(engine().available())
    }

    @Test
    fun `a song seed asks for its own station`() = runTest {
        coEvery { yt.startRadio("seedVid", "RDAMVMseedVid", null) } returns
            RadioQueuePage(listOf(summary("a"), summary("b")), "TOKEN")

        val (_, first) = engine().start(RadioSeed.Song("Title", "Artist", ytVideoId = "seedVid"))

        assertEquals(listOf("a", "b"), first.map { it.youtubeId })
    }

    /**
     * A library row from Spotify has no video id; the canonical search supplies the seed,
     * and it is what makes the station start at all for those rows.
     */
    @Test
    fun `a seed without a video id resolves one first`() = runTest {
        coEvery { yt.searchCanonicalMatch("Artist", "Title") } returns
            CanonicalMatch("resolvedVid", null)
        coEvery { yt.startRadio("resolvedVid", "RDAMVMresolvedVid", null) } returns
            RadioQueuePage(listOf(summary("a")), "TOKEN")

        val (_, first) = engine().start(RadioSeed.Song("Title", "Artist", ytVideoId = null))

        assertEquals(listOf("a"), first.map { it.youtubeId })
    }

    /** A station seeded off the wrong recording would be worse than none, so a miss starts nothing. */
    @Test
    fun `a seed that cannot be resolved starts no station`() = runTest {
        coEvery { yt.searchCanonicalMatch("Artist", "Title") } returns null

        val (_, first) = engine().start(RadioSeed.Song("Title", "Artist", ytVideoId = null))

        assertTrue(first.isEmpty())
        coVerify(exactly = 0) { yt.startRadio(any(), any(), any()) }
    }

    /** An artist's station is seeded exactly as the web player's radio button does it. */
    @Test
    fun `an artist seed uses the page's radio endpoint`() = runTest {
        coEvery { yt.artistRadioEndpoint("UCartist") } returns
            ArtistRadioEndpoint("artistSeed", "RDEMartist", "wAEB")
        coEvery { yt.startRadio("artistSeed", "RDEMartist", "wAEB") } returns
            RadioQueuePage(listOf(summary("x")), "TOKEN")

        val (_, first) = engine().start(RadioSeed.Artist("An Artist", ytBrowseId = "UCartist"))

        assertEquals(listOf("x"), first.map { it.youtubeId })
    }

    /** A page without a radio button still yields a station: from the artist's most popular song. */
    @Test
    fun `an artist seed without a radio button falls back to a popular song`() = runTest {
        coEvery { yt.artistRadioEndpoint("UCartist") } returns null
        coEvery { yt.getArtist("UCartist") } returns ArtistProfile(
            id = "UCartist",
            name = "An Artist",
            avatarUrl = null,
            subscribersText = null,
            popular = listOf(summary("popularVid")),
            albums = emptyList(),
            singles = emptyList(),
            related = emptyList(),
        )
        coEvery { yt.startRadio("popularVid", "RDAMVMpopularVid", null) } returns
            RadioQueuePage(listOf(summary("y")), "TOKEN")

        val (_, first) = engine().start(RadioSeed.Artist("An Artist", ytBrowseId = "UCartist"))

        assertEquals(listOf("y"), first.map { it.youtubeId })
    }

    @Test
    fun `an artist seed without a browse id resolves the name`() = runTest {
        coEvery { yt.resolveArtist("An Artist") } returns ArtistSummary("UCfound", "An Artist", null)
        coEvery { yt.artistRadioEndpoint("UCfound") } returns
            ArtistRadioEndpoint("seed", "RDEMfound", null)
        coEvery { yt.startRadio("seed", "RDEMfound", null) } returns
            RadioQueuePage(listOf(summary("z")), "TOKEN")

        val (_, first) = engine().start(RadioSeed.Artist("An Artist"))

        assertEquals(listOf("z"), first.map { it.youtubeId })
    }

    /** The next page is the continuation token and nothing else. */
    @Test
    fun `a station follows its continuation`() = runTest {
        coEvery { yt.startRadio("seedVid", "RDAMVMseedVid", null) } returns
            RadioQueuePage(listOf(summary("a")), "TOKEN_1")
        coEvery { yt.continueRadio("TOKEN_1") } returns RadioQueuePage(listOf(summary("b")), "TOKEN_2")
        coEvery { yt.continueRadio("TOKEN_2") } returns RadioQueuePage(listOf(summary("c")), null)

        val (station, _) = engine().start(RadioSeed.Song("Title", "Artist", ytVideoId = "seedVid"))

        assertEquals(listOf("b"), station.nextBatch().map { it.youtubeId })
        assertEquals(listOf("c"), station.nextBatch().map { it.youtubeId })
        // The station's last page has no token: it is over, and asking again asks YouTube nothing.
        assertTrue(station.nextBatch().isEmpty())
        coVerify(exactly = 1) { yt.continueRadio("TOKEN_2") }
    }

    /** Station tracks are ordinary streaming tracks, in the same shape the Last.fm engine emits. */
    @Test
    fun `a track is a streaming YouTube track with a synthetic id`() = runTest {
        val videoId = "dQw4w9WgXcQ"
        coEvery { yt.startRadio("seedVid", "RDAMVMseedVid", null) } returns
            RadioQueuePage(listOf(summary(videoId, title = "Song", artist = "Someone")), "TOKEN")

        val track = engine().start(RadioSeed.Song("Title", "Artist", ytVideoId = "seedVid")).second.single()

        assertEquals("Song", track.title)
        assertEquals("Someone", track.artist)
        assertEquals("Album", track.album)
        assertEquals(videoId, track.youtubeId)
        assertEquals(MusicSource.YOUTUBE, track.source)
        assertTrue(track.isStreamable)
        assertEquals(videoId.hashCode().toLong(), track.id)
        assertEquals(200_000L, track.durationMs)
        // No thumbnail on the row: the frame YouTube serves for the id, which centre-crops square.
        assertEquals("https://i.ytimg.com/vi/$videoId/maxresdefault.jpg", track.albumArtUrl)
    }

    /** An id that isn't shaped like a YouTube one gets no invented URL. */
    @Test
    fun `an id that is not a video id gets no thumbnail url`() = runTest {
        coEvery { yt.startRadio("seedVid", "RDAMVMseedVid", null) } returns
            RadioQueuePage(listOf(summary("short")), "TOKEN")

        val track = engine().start(RadioSeed.Song("Title", "Artist", ytVideoId = "seedVid")).second.single()

        assertEquals(null, track.albumArtUrl)
    }

    /** A page that doesn't parse, or a client that throws, ends the station rather than crashing the player. */
    @Test
    fun `a failing continuation ends the station quietly`() = runTest {
        coEvery { yt.startRadio("seedVid", "RDAMVMseedVid", null) } returns
            RadioQueuePage(listOf(summary("a")), "TOKEN")
        coEvery { yt.continueRadio("TOKEN") } throws java.io.IOException("offline")

        val (station, _) = engine().start(RadioSeed.Song("Title", "Artist", ytVideoId = "seedVid"))

        assertTrue(station.nextBatch().isEmpty())
    }

    /** A client that throws on the first call starts no station instead of propagating. */
    @Test
    fun `a failing start starts no station`() = runTest {
        coEvery { yt.startRadio(any(), any(), any()) } throws java.io.IOException("offline")

        val (_, first) = engine().start(RadioSeed.Song("Title", "Artist", ytVideoId = "seedVid"))

        assertTrue(first.isEmpty())
    }
}
