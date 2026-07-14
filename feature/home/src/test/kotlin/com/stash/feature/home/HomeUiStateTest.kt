package com.stash.feature.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HomeUiStateTest {
    @Test fun `defaults are discovery-shaped`() {
        val s = HomeUiState()
        assertThat(s.hero).isNull()
        assertThat(s.isColdStart).isTrue()
        assertThat(s.isLoading).isTrue()
    }
    @Test fun `cold start is false once a hero exists`() {
        val s = HomeUiState(
            hero = DiscoverHeroState("Discover", "30 tracks", null, 7L),
            isLoading = false,
        )
        assertThat(s.isColdStart).isFalse()
    }
}
