package com.stash.feature.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LibraryUiStateTest {
    @Test fun `mix slices default empty`() {
        val s = LibraryUiState()
        assertThat(s.stashMixes).isEmpty()
        assertThat(s.spotifyMixes).isEmpty()
        assertThat(s.youtubeMixes).isEmpty()
        assertThat(s.likedPlaylists).isEmpty()
        assertThat(s.recentlyAdded).isEmpty()
        assertThat(s.buildingMixIds).isEmpty()
        assertThat(s.customMixPlaylistIds).isEmpty()
    }
}
