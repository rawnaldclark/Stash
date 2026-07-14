package com.stash.core.ui.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MotionTokensTest {
    @Test fun `durations are ordered and sane`() {
        assertThat(StashMotion.DUR_SHORT_MS).isLessThan(StashMotion.DUR_MED_MS)
        assertThat(StashMotion.DUR_SHORT_MS).isGreaterThan(0)
    }
    @Test fun `press scale is a subtle shrink`() {
        assertThat(StashMotion.PRESS_SCALE).isGreaterThan(0.9f)
        assertThat(StashMotion.PRESS_SCALE).isLessThan(1f)
    }
    @Test fun `stagger is small`() {
        assertThat(StashMotion.SECTION_STAGGER_MS).isAtLeast(10)
        assertThat(StashMotion.SECTION_STAGGER_MS).isAtMost(80)
    }
}
