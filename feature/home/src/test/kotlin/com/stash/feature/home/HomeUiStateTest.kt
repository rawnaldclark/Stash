package com.stash.feature.home

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.prefs.HomeSection
import org.junit.Test

class HomeUiStateTest {
    @Test fun `defaults are discovery-shaped`() {
        val s = HomeUiState()
        assertThat(s.hero).isNull()
        assertThat(s.isColdStart).isTrue()
        assertThat(s.isLoading).isTrue()
    }

    @Test fun `Home leaves Community out before the preference loads`() {
        assertThat(HomeUiState().sections).doesNotContain(HomeSection.COMMUNITY)
    }
    @Test fun `cold start is false once a hero exists`() {
        val s = HomeUiState(
            hero = DiscoverHeroState("Discover", "30 tracks", null, 7L),
            isLoading = false,
        )
        assertThat(s.isColdStart).isFalse()
    }

    // -- The "Personalize your Home" card only belongs to an empty Home ------------

    @Test fun `an empty home shows the cold-start card`() {
        assertThat(HomeUiState().showColdStartCard).isTrue()
    }

    /** Daily Discover not built yet, but forty synced mixes on the rails: the rails lead. */
    @Test fun `a rail with content hides the card even with no hero`() {
        val s = HomeUiState(madeForYou = listOf(mix(1L, "Release Radar")))
        assertThat(s.hero).isNull()
        assertThat(s.showColdStartCard).isFalse()
    }

    @Test fun `a hero hides the card`() {
        val s = HomeUiState(hero = DiscoverHeroState("Discover", "30 tracks", null, 7L))
        assertThat(s.showColdStartCard).isFalse()
    }

    @Test fun `a liked songs card is content too`() {
        val s = HomeUiState(likedCard = LikedCardState(trackCount = 1229))
        assertThat(s.showColdStartCard).isFalse()
    }

    private fun mix(id: Long, title: String) = HomeMix(
        id = id, title = title, artUrl = null, source = com.stash.core.model.MusicSource.SPOTIFY,
    )
}
