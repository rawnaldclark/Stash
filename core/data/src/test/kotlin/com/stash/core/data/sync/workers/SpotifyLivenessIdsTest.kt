package com.stash.core.data.sync.workers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit test for [spotifyLivenessIds] — the "these still exist" set that
 * `deactivateMissingSpotifyCustomPlaylists` measures absence against.
 *
 * The bug it pins: the caller used to pass the POST-[keepAsLibraryPlaylist]
 * list. That filter only decides which pass snapshots an id, so a playlist
 * was hidden the moment it started matching it — appearing on the home feed,
 * or being renamed to "Daily Mix N" — even though the library walk had just
 * listed it. The name-match case has no DAILY_MIX snapshot behind it to
 * reactivate the row, so the playlist vanished for good.
 */
class SpotifyLivenessIdsTest {

    @Test fun `an observed playlist the library filter drops still counts as live`() {
        val observed = listOf("keep_me", "daily_mix_named", "on_home_feed")
        // Both of the ids keepAsLibraryPlaylist would reject.
        assertThat(keepAsLibraryPlaylist("daily_mix_named", "Daily Mix 3", emptySet())).isFalse()
        assertThat(keepAsLibraryPlaylist("on_home_feed", "Late Night", setOf("on_home_feed")))
            .isFalse()

        assertThat(spotifyLivenessIds(observed))
            .containsExactly("keep_me", "daily_mix_named", "on_home_feed")
    }

    // The library walk is the only authority on what is still saved. Widening
    // this with homeFeedMixIds would keep an unsaved playlist that Spotify
    // still features alive forever, so home-feed ids must not leak in here.
    @Test fun `only what the library walk listed counts`() {
        assertThat(spotifyLivenessIds(emptyList())).isEmpty()
        assertThat(spotifyLivenessIds(listOf("p1"))).containsExactly("p1")
    }

    // An id listed at the library root AND inside a folder must not produce a
    // duplicate — the list goes straight into a SQL `NOT IN (...)`.
    @Test fun `duplicates collapse`() {
        assertThat(spotifyLivenessIds(listOf("p1", "p1", "p2"))).containsExactly("p1", "p2")
    }
}
