package com.stash.core.data.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicLinksTest {

    private val id = "37i9dQZF1DXcBWIGoYBM5M"

    @Test
    fun `spotify links of each kind, with tracking and a locale`() {
        assertEquals(MusicLink.SpotifyPlaylist(id), MusicLinks.find("https://open.spotify.com/playlist/$id?si=abc123"))
        assertEquals(MusicLink.SpotifyAlbum(id), MusicLinks.find("https://open.spotify.com/intl-de/album/$id"))
        assertEquals(MusicLink.SpotifyTrack(id), MusicLinks.find("https://open.spotify.com/embed/track/$id?utm_source=x"))
        assertEquals(MusicLink.SpotifyTrack(id), MusicLinks.find("spotify:track:$id"))
    }

    @Test
    fun `a link inside shared text`() {
        assertEquals(
            MusicLink.SpotifyPlaylist(id),
            MusicLinks.find("Check out this playlist on Spotify! https://open.spotify.com/playlist/$id?si=x."),
        )
    }

    @Test
    fun `the url is found as written, skipping links that aren't music`() {
        assertEquals(
            "https://youtu.be/dQw4w9WgXcQ?si=z",
            MusicLinks.findUrl("see https://example.com/x and https://youtu.be/dQw4w9WgXcQ?si=z!"),
        )
        assertNull(MusicLinks.findUrl("see https://example.com/x"))
    }

    @Test
    fun `spotify short links are followed later`() {
        assertEquals(MusicLink.SpotifyShort("https://spotify.link/AbCdEf"), MusicLinks.find("https://spotify.link/AbCdEf?x=1"))
    }

    @Test
    fun `youtube playlists, albums and songs`() {
        assertEquals(MusicLink.YouTubePlaylist("PLabcdefghij"), MusicLinks.find("https://music.youtube.com/playlist?list=PLabcdefghij&si=q"))
        assertEquals(MusicLink.YouTubePlaylist("OLAK5uy_abc"), MusicLinks.find("https://www.youtube.com/playlist?list=OLAK5uy_abc"))
        assertEquals(MusicLink.YouTubeAlbum("MPREb_abcdef"), MusicLinks.find("https://music.youtube.com/browse/MPREb_abcdef"))
        assertEquals(MusicLink.YouTubeSong("dQw4w9WgXcQ", null), MusicLinks.find("https://youtu.be/dQw4w9WgXcQ?si=z"))
        assertEquals(MusicLink.YouTubeSong("dQw4w9WgXcQ", null), MusicLinks.find("https://m.youtube.com/shorts/dQw4w9WgXcQ"))
    }

    @Test
    fun `a song opened from a playlist names it, from a radio does not`() {
        assertEquals(
            MusicLink.YouTubeSong("dQw4w9WgXcQ", "PLabcdefghij"),
            MusicLinks.find("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=PLabcdefghij"),
        )
        assertEquals(
            MusicLink.YouTubeSong("dQw4w9WgXcQ", null),
            MusicLinks.find("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=RDdQw4w9WgXcQ&start_radio=1"),
        )
    }

    @Test
    fun `anything else is no link`() {
        assertNull(MusicLinks.find("just some words"))
        assertNull(MusicLinks.find("https://open.spotify.com/artist/$id"))
        assertNull(MusicLinks.find("https://open.spotify.com/playlist/short"))
        assertNull(MusicLinks.find("https://example.com/playlist?list=PLabcdefghij"))
        assertNull(MusicLinks.find("https://www.youtube.com/channel/UCabc"))
        assertNull(MusicLinks.find(null))
    }
}
