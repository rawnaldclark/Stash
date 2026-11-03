package com.stash.feature.sync

import com.stash.core.model.PlaylistType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * An account page's tabs (item 5, 2026-11-02): a saved album is listed under Albums, never among
 * the playlists, the mixes or Liked, and never counted as a playlist on the Sync tab.
 */
class ManageListsTest {

    private fun row(id: Long, type: PlaylistType = PlaylistType.CUSTOM, album: Boolean = false) = SyncPlaylistRow(
        id = id, name = "Row $id", trackCount = 10, type = type, syncEnabled = false, keepOffline = false,
        artUrl = null, source = SyncSource.SPOTIFY, album = album,
    )

    @Test fun `saved albums get their own tab and stay out of the others`() {
        val playlist = row(1)
        val album = row(2, album = true)
        val mix = row(3, type = PlaylistType.DAILY_MIX)
        val liked = row(4, type = PlaylistType.LIKED_SONGS)

        val lists = manageLists(listOf(playlist, album, mix, liked))

        assertEquals(listOf(playlist), lists.playlists)
        assertEquals(listOf(album), lists.albums)
        assertEquals(listOf(mix), lists.mixes)
        assertEquals(listOf(liked), lists.liked)
    }

    @Test fun `a saved album never counts as a playlist`() {
        assertFalse(row(2, album = true).isOwnPlaylist())
        assertEquals(1, listOf(row(1), row(2, album = true)).count { it.isOwnPlaylist() })
    }
}
