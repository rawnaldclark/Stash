package com.stash.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistTypeTest {
    @Test
    fun `DOWNLOADS_MIX is a first-class enum value`() {
        val value = PlaylistType.valueOf("DOWNLOADS_MIX")
        assertEquals(PlaylistType.DOWNLOADS_MIX, value)
    }

    @Test
    fun `enum contains the expected set`() {
        assertEquals(
            setOf("DAILY_MIX", "LIKED_SONGS", "CUSTOM", "STASH_MIX", "DOWNLOADS_MIX"),
            PlaylistType.entries.map { it.name }.toSet(),
        )
    }
}
