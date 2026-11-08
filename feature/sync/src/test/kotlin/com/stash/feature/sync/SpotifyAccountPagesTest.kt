package com.stash.feature.sync

import com.stash.core.model.PlaylistType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Sync tab with several Spotify accounts (plan 2026-11-04 §3.7): each account's card and page
 * lists its own rows. The main account's page used to list every Spotify row; it lists the main
 * account's now, and an other account's page never shows the main account's rows under its name.
 */
class SpotifyAccountPagesTest {

    private fun spotify(id: Long, account: String?) = SpotifySyncPlaylist(
        id = id, name = "Spotify $id", trackCount = 10, type = PlaylistType.CUSTOM, syncEnabled = false,
        spotifyAccount = account,
    )

    private fun youTube(id: Long) = YouTubeSyncPlaylist(
        id = id, name = "YouTube $id", trackCount = 10, type = PlaylistType.CUSTOM, syncEnabled = false,
    )

    private val sam = SpotifyOtherAccount(id = 4, tag = "sam", name = "Sam", syncAlbums = true, syncMixes = false)
    private val alex = SpotifyOtherAccount(id = 7, tag = "alex", name = "Alex", syncAlbums = true, syncMixes = false)

    private val state = SyncUiState(
        spotifyPlaylists = listOf(spotify(1, null), spotify(2, "sam"), spotify(3, "alex"), spotify(5, null), spotify(6, "gone")),
        youTubePlaylists = listOf(youTube(9)),
        spotifyOtherAccounts = listOf(sam, alex),
        spotifyOtherAccountsLoaded = true,
    )

    private fun SyncUiState.page(source: SyncSource, account: Long?) = accountPageRows(source, account).map { it.id }

    @Test fun `the main account's page lists the main account's rows only`() {
        assertEquals(listOf(1L, 5L), state.page(SyncSource.SPOTIFY, null))
    }

    @Test fun `an other account's page lists that account's rows only`() {
        assertEquals(listOf(2L), state.page(SyncSource.SPOTIFY, sam.id))
        assertEquals(listOf(3L), state.page(SyncSource.SPOTIFY, alex.id))
    }

    @Test fun `a removed account's rows are on no page`() {
        // Tagged "gone": an account removed (its rows hidden, kept for a re-add) has no page, and
        // they never fall onto the main account's.
        val everyPage = state.page(SyncSource.SPOTIFY, null) + state.spotifyOtherAccounts.flatMap { state.page(SyncSource.SPOTIFY, it.id) }
        assertEquals(false, 6L in everyPage)
    }

    @Test fun `an other account's page is empty until the accounts are read, never the main's rows`() {
        val loading = state.copy(spotifyOtherAccounts = emptyList(), spotifyOtherAccountsLoaded = false)

        assertEquals(emptyList<Long>(), loading.page(SyncSource.SPOTIFY, sam.id))
    }

    @Test fun `an account removed while its page is open lists nothing`() {
        assertEquals(emptyList<Long>(), state.page(SyncSource.SPOTIFY, 99L))
    }

    @Test fun `other services' pages are as before`() {
        assertEquals(listOf(9L), state.page(SyncSource.YOUTUBE, null))
    }

    @Test fun `each account's card counts its own rows`() {
        val rows = state.playlistRows()

        assertEquals(listOf(1L, 5L), rows.ofSpotifyAccount(null).map { it.id })
        assertEquals(listOf(2L), rows.ofSpotifyAccount("sam").map { it.id })
    }
}
