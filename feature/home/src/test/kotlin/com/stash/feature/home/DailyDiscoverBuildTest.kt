package com.stash.feature.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DailyDiscoverBuildTest {

    @Test fun `not materialized yet is building`() {
        assertThat(dailyDiscoverBuilding(active = true, materialized = false, trackCount = 0, pendingDiscoveryCount = 0))
            .isTrue()
    }

    @Test fun `materialized and empty while discovery owes tracks is building`() {
        assertThat(dailyDiscoverBuilding(active = true, materialized = true, trackCount = 0, pendingDiscoveryCount = 12))
            .isTrue()
    }

    @Test fun `materialized, empty and owed nothing is not building`() {
        // It found nothing to build from: no promise of a mix that isn't coming.
        assertThat(dailyDiscoverBuilding(active = true, materialized = true, trackCount = 0, pendingDiscoveryCount = 0))
            .isFalse()
    }

    @Test fun `a build without Last fm keys is not building - an all-recommendations mix is owed nothing`() {
        // Daily Discover is all recommendations, and with no Last.fm keys (a fork, CI) none are ever queued:
        // it materializes empty with nothing owed, so Home shows no hero rather than a card that never fills.
        assertThat(dailyDiscoverBuilding(active = true, materialized = true, trackCount = 0, pendingDiscoveryCount = 0))
            .isFalse()
    }

    @Test fun `with tracks it is built, not building`() {
        assertThat(dailyDiscoverBuilding(active = true, materialized = true, trackCount = 40, pendingDiscoveryCount = 5))
            .isFalse()
    }

    @Test fun `Stash Mixes turned off is never building`() {
        assertThat(dailyDiscoverBuilding(active = false, materialized = false, trackCount = 0, pendingDiscoveryCount = 3))
            .isFalse()
    }
}
