package com.stash.feature.record.card

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.prefs.HomeSection
import org.junit.Test

/** The card's menu says "your mixes stay" only when hiding it leaves mixes on Home ([mixesStay]). */
class MixesStayTest {
    private val none = emptySet<HomeSection>()

    @Test fun `the shelf has mixes and is on, so they stay`() {
        assertThat(mixesStay(none, shelfHasPicks = true, imported = true)).isTrue()
    }

    @Test fun `no mixes on the shelf (too little history) promises nothing, import or not`() {
        assertThat(mixesStay(none, shelfHasPicks = false, imported = true)).isFalse()
        assertThat(mixesStay(none, shelfHasPicks = false, imported = false)).isFalse()
    }

    @Test fun `the card from this phone's own plays alone, with no import, has no mixes`() {
        // the shelf hasn't made them yet; without an import there are none to make
        assertThat(mixesStay(none, shelfHasPicks = null, imported = false)).isFalse()
    }

    @Test fun `before the shelf has made them, an import stands in`() {
        assertThat(mixesStay(none, shelfHasPicks = null, imported = true)).isTrue()
    }

    @Test fun `the mixes switched off in Home layout don't stay, whatever the shelf had`() {
        val off = setOf(HomeSection.RECORD_MIXES)
        assertThat(mixesStay(off, shelfHasPicks = true, imported = true)).isFalse()
        assertThat(mixesStay(off, shelfHasPicks = null, imported = true)).isFalse()
        // the card's own switch doesn't matter here
        assertThat(mixesStay(setOf(HomeSection.YOUR_RECORD), shelfHasPicks = true, imported = true)).isTrue()
    }
}
