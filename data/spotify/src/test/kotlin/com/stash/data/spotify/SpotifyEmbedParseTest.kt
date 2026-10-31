package com.stash.data.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SpotifyEmbedClient.parse] on real embed pages (trimmed to their `__NEXT_DATA__`, fetched 2026-10-29). */
class SpotifyEmbedParseTest {

    private fun page(name: String): String =
        this::class.java.classLoader!!.getResourceAsStream("fixtures/embed/$name")!!.bufferedReader().use { it.readText() }

    @Test
    fun `a playlist gives its name, owner, cover and songs`() {
        val e = SpotifyEmbedClient.parse(page("playlist.html"))!!
        assertEquals("playlist", e.type)
        assertEquals("Spotify", e.subtitle)
        assertEquals("https://i.scdn.co/image/ab67706f00000002e8ccdd567fff6a597b87586b", e.coverUrl)
        assertEquals(50, e.tracks.size)
        val first = e.tracks.first()
        assertEquals("Housewife", first.title)
        assertEquals("Tate McRae", first.artist)
        assertEquals("2sFepQNyl1WjpROruP8bJ0", first.id)
        assertEquals(208810L, first.durationMs)
    }

    @Test
    fun `an album's artists lose the no-break space and the cover is the largest`() {
        val e = SpotifyEmbedClient.parse(page("album.html"))!!
        assertEquals("album", e.type)
        assertEquals("Global Warming", e.name)
        assertEquals("Pitbull", e.subtitle)
        assertEquals(18, e.tracks.size)
        assertEquals("Pitbull, Sensato", e.tracks.first().artist)
        assertTrue(e.coverUrl!!.endsWith("ab67616d0000b2732c5b24ecfa39523a75c993c4"))
    }

    @Test
    fun `a track is its own one song`() {
        val e = SpotifyEmbedClient.parse(page("track.html"))!!
        assertEquals("track", e.type)
        assertNull(e.subtitle)
        val t = e.tracks.single()
        assertEquals("Cut To The Feeling", t.title)
        assertEquals("Carly Rae Jepsen", t.artist)
        assertEquals("11dFghVXANMlKmJXsNCbNl", t.id)
        assertEquals(207959L, t.durationMs)
    }

    @Test
    fun `a page without the data is nothing`() {
        assertNull(SpotifyEmbedClient.parse("<html><body>Page not found</body></html>"))
    }
}
