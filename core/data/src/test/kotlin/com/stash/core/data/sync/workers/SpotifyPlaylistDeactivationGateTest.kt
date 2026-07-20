package com.stash.core.data.sync.workers

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.SyncMode
import org.junit.Test

class SpotifyPlaylistDeactivationGateTest {

    @Test fun `refresh with complete inventory deactivates missing playlists`() {
        assertThat(shouldDeactivateMissingSpotifyPlaylists(SyncMode.REFRESH, inventoryComplete = true))
            .isTrue()
    }

    @Test fun `accumulate preserves missing playlists`() {
        assertThat(shouldDeactivateMissingSpotifyPlaylists(SyncMode.ACCUMULATE, inventoryComplete = true))
            .isFalse()
    }

    @Test fun `partial or unavailable inventory preserves missing playlists`() {
        assertThat(shouldDeactivateMissingSpotifyPlaylists(SyncMode.REFRESH, inventoryComplete = false))
            .isFalse()
    }
}
