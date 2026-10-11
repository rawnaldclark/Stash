package com.stash.core.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingActionTest {

    @Test fun `never saved reads as the default row`() {
        assertEquals(setOf(NowPlayingAction.RADIO, NowPlayingAction.QUEUE), NowPlayingAction.parsePins(null))
    }

    @Test fun `an empty saved set stays empty - the user unpinned everything`() {
        assertEquals(emptySet<NowPlayingAction>(), NowPlayingAction.parsePins(emptySet()))
    }

    @Test fun `unknown names are ignored`() {
        val pins = NowPlayingAction.parsePins(setOf("SHARE", "TELEPORT", "", "share"))
        assertEquals(setOf(NowPlayingAction.SHARE), pins)
    }

    @Test fun `a corrupt oversized set keeps the first MAX_PINS in canonical order`() {
        val all = NowPlayingAction.entries.map { it.name }.toSet()
        val pins = NowPlayingAction.parsePins(all)
        assertEquals(NowPlayingAction.MAX_PINS, pins.size)
        assertEquals(NowPlayingAction.entries.take(NowPlayingAction.MAX_PINS).toSet(), pins)
    }
}
