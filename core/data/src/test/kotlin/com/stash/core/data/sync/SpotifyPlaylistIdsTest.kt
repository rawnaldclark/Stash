package com.stash.core.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Account-keyed ids and tags take only a normalised Spotify user id. SQLite compares them case
 * by case, so `Sam` and `sam` would be two accounts: two Liked Songs rows, or a tag that never
 * matches its account's row. Such an id fails loudly instead.
 */
class SpotifyPlaylistIdsTest {

    private val notKeys = listOf("Sam", " sam", "sam ", "spotify:user:sam", "SPOTIFY:USER:SAM", "", "   ")

    private fun refused(block: () -> Unit): Boolean = runCatching(block).exceptionOrNull() is IllegalArgumentException

    @Test fun `the main's Liked Songs id is fixed, an extra's carries its key`() {
        assertEquals("spotify_liked_songs", SpotifyPlaylistIds.MAIN_LIKED_SONGS)
        assertEquals("spotify_liked_songs:lm7d11nsf65n11sojl4twwr6t", SpotifyPlaylistIds.extraLikedSongs("lm7d11nsf65n11sojl4twwr6t"))
    }

    @Test fun `an extra's Liked Songs id refuses an id that isn't normalised`() {
        notKeys.forEach { assertTrue("'$it' accepted", refused { SpotifyPlaylistIds.extraLikedSongs(it) }) }
    }

    @Test fun `an account tag is the normalised id, and refuses any other shape`() {
        assertEquals("sam", SpotifyPlaylistIds.accountTag("sam"))
        notKeys.forEach { assertTrue("'$it' accepted", refused { SpotifyPlaylistIds.accountTag(it) }) }
    }
}
