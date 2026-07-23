package com.stash.core.data.sync.workers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Contract tests for [keepAsLibraryPlaylist], the keep-check applied to the
 * `libraryV3` walk.
 *
 * Issue #354: the old check was `owner != "spotify"`, which dropped a
 * FOLLOWED "Made For You" playlist (a friend's Discover Weekly / Release
 * Radar). Those are spotify-owned but absent from the user's own home feed,
 * so nothing else in the sync picked them up. The keep-check is now about
 * what the home-feed pass already covered, not about who owns the row.
 */
class LibraryPlaylistKeepTest {

    private val homeFeedMixIds = setOf("hf_discover_weekly", "hf_release_radar")

    @Test fun `a followed friends Discover Weekly is kept`() {
        assertThat(keepAsLibraryPlaylist("friend_dw", "Discover Weekly", homeFeedMixIds))
            .isTrue()
    }

    @Test fun `a followed friends Release Radar is kept`() {
        assertThat(keepAsLibraryPlaylist("friend_rr", "Release Radar", homeFeedMixIds))
            .isTrue()
    }

    @Test fun `an ordinary user playlist is kept`() {
        assertThat(keepAsLibraryPlaylist("road_trip", "My Road Trip", homeFeedMixIds))
            .isTrue()
    }

    @Test fun `the users own mix already snapshotted from the home feed is skipped`() {
        assertThat(keepAsLibraryPlaylist("hf_discover_weekly", "Discover Weekly", homeFeedMixIds))
            .isFalse()
    }

    @Test fun `Daily Mix N is skipped by name even without a home feed hit`() {
        assertThat(keepAsLibraryPlaylist("dm3", "Daily Mix 3", emptySet()))
            .isFalse()
    }

    @Test fun `a name merely containing Daily Mix is kept`() {
        assertThat(keepAsLibraryPlaylist("clone", "My Daily Mix 3 Clone", emptySet()))
            .isTrue()
    }
}
