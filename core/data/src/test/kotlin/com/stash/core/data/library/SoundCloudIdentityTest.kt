package com.stash.core.data.library

import com.stash.core.data.db.entity.TrackEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundCloudIdentityTest {
    private val remix = TrackEntity(title = "Song", artist = "Artist", soundCloudId = "123")

    @Test fun `title matching cannot replace a SoundCloud upload with YouTube`() {
        assertFalse(SameSongRule.isSameSong(remix, "Song", "youtube", null))
    }

    @Test fun `incoming SoundCloud upload cannot take an unlinked row by title`() {
        assertFalse(SameSongRule.isSameSong(remix.copy(soundCloudId = null), "Song", null, null, "123"))
    }

    @Test fun `two SoundCloud uploads remain distinct even with identical metadata`() {
        assertFalse(SameSongRule.isSameSong(remix, "Song", null, null, "456"))
        assertTrue(SameSongRule.isSameSong(remix, "Song", null, null, "123"))
    }
}
