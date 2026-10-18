package com.stash.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** [PlaybackSource] survives the DataStore round trip the saved queue takes. */
class PlaybackSourceTest {

    @Test fun `every source round-trips through serialize and deserialize`() {
        val sources = listOf(
            PlaybackSource.Library,
            PlaybackSource.Liked("ALL"),
            PlaybackSource.Playlist(42L, "Chill Vibes"),
            PlaybackSource.Artist("Portishead"),
            PlaybackSource.Album("Dummy", "Portishead"),
            PlaybackSource.Radio("Glory Box"),
            PlaybackSource.Search,
            PlaybackSource.AllSongs,
            PlaybackSource.Handoff("Late night"),
            PlaybackSource.Listening("History · Sat, Oct 11"),
            PlaybackSource.Unknown,
        )
        for (source in sources) {
            assertEquals(source, PlaybackSource.deserialize(source.serialize()))
        }
    }

    @Test fun `a listening label keeps a pipe inside it`() {
        val source = PlaybackSource.Listening("History | Today")
        assertEquals("LISTENING|History | Today", source.serialize())
        assertEquals(source, PlaybackSource.deserialize(source.serialize()))
    }

    @Test fun `a listening source shows its label as Playing from`() {
        assertEquals("History · Today", PlaybackSource.Listening("History · Today").displayLabel)
    }

    @Test fun `a bare LISTENING reads as an empty label, not Unknown`() {
        assertEquals(PlaybackSource.Listening(""), PlaybackSource.deserialize("LISTENING"))
    }
}
