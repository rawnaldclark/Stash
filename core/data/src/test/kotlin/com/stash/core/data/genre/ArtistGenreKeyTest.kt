package com.stash.core.data.genre

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistGenreKeyTest {

    @Test fun `collaborations key on the first artist`() {
        assertEquals("Drake", ArtistGenreKey.of("Drake, 21 Savage"))
        assertEquals("Aarne", ArtistGenreKey.of("Aarne, Toxi$, Big Baby Tape"))
    }

    @Test fun `featured artists are cut off in every common form`() {
        listOf(
            "Drake feat. Rihanna", "Drake Feat. Rihanna", "Drake feat Rihanna", "Drake ft. Rihanna",
            "Drake FT Rihanna", "Drake featuring Rihanna", "Drake (feat. Rihanna)", "Drake [ft. Rihanna]",
            "Drake feat. Rihanna, Future", "Drake, Future feat. Rihanna",
        ).forEach { assertEquals(it, "Drake", ArtistGenreKey.of(it)) }
    }

    @Test fun `names that only look like a feature are left alone`() {
        assertEquals("Daft Punk", ArtistGenreKey.of("Daft Punk"))
        assertEquals("Ft. Lauderdale", ArtistGenreKey.of("Ft. Lauderdale"))
        assertEquals("Sleeping With Sirens", ArtistGenreKey.of("Sleeping With Sirens"))
        assertEquals("Mumford & Sons", ArtistGenreKey.of("Mumford & Sons"))
        assertEquals("Earth, Wind & Fire", ArtistGenreKey.of("Earth, Wind & Fire"))
        assertEquals("Featurette", ArtistGenreKey.of("Featurette"))
    }

    @Test fun `allowlisted comma names stay whole inside collaborations`() {
        assertEquals("Earth, Wind & Fire", ArtistGenreKey.of("Earth, Wind & Fire, Chicago"))
        assertEquals("Tyler, The Creator", ArtistGenreKey.of("Tyler, The Creator"))
        assertEquals("Tyler, The Creator", ArtistGenreKey.of("Tyler, The Creator, Kali Uchis"))
        assertEquals("Tyler, The Creator", ArtistGenreKey.of("Tyler, The Creator feat. Frank Ocean"))
        assertEquals("Kali Uchis", ArtistGenreKey.of("Kali Uchis, Tyler, The Creator"))
    }

    @Test fun `whitespace is trimmed and blank stays blank`() {
        assertEquals("Drake", ArtistGenreKey.of("  Drake  "))
        assertEquals("", ArtistGenreKey.of("   "))
        assertEquals("", ArtistGenreKey.of(""))
    }
}
