package com.stash.feature.nowplaying

import com.stash.core.data.prefs.NowPlayingAction
import com.stash.core.data.prefs.NowPlayingAction.DOWNLOAD
import com.stash.core.data.prefs.NowPlayingAction.FLAG_WRONG_MATCH
import com.stash.core.data.prefs.NowPlayingAction.LISTEN_TOGETHER
import com.stash.core.data.prefs.NowPlayingAction.LYRICS
import com.stash.core.data.prefs.NowPlayingAction.PLAYBACK_SPEED
import com.stash.core.data.prefs.NowPlayingAction.POST_TO_COMMUNITY
import com.stash.core.data.prefs.NowPlayingAction.QUEUE
import com.stash.core.data.prefs.NowPlayingAction.RADIO
import com.stash.core.data.prefs.NowPlayingAction.SAVE_TO_PLAYLIST
import com.stash.core.data.prefs.NowPlayingAction.SHARE
import com.stash.core.data.prefs.NowPlayingAction.VIEW_ALBUM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingActionsTest {

    private val solo = ActionContext()

    // ── the default ──────────────────────────────────────────────────────────────────────────

    @Test fun `default row is Radio, then the menu, then Queue`() {
        val row = topRowActions(NowPlayingAction.DefaultPins, solo)
        assertEquals(listOf(RADIO), row.leading)
        assertEquals(listOf(QUEUE), row.trailing)
    }

    @Test fun `default menu keeps the old items in their old order, with Lyrics first`() {
        assertEquals(
            listOf(LYRICS, LISTEN_TOGETHER, SAVE_TO_PLAYLIST, SHARE, VIEW_ALBUM, PLAYBACK_SPEED, DOWNLOAD, FLAG_WRONG_MATCH),
            menuActions(NowPlayingAction.DefaultPins, solo),
        )
    }

    // ── order ────────────────────────────────────────────────────────────────────────────────

    @Test fun `pinned icons keep the canonical order whatever order they were pinned in`() {
        val pins = linkedSetOf(DOWNLOAD, QUEUE, SHARE, RADIO, LYRICS)
        val row = topRowActions(pins, solo)
        assertEquals(listOf(RADIO, LYRICS, SHARE, DOWNLOAD), row.leading)
        assertEquals(listOf(QUEUE), row.trailing)
    }

    @Test fun `a pinned item leaves the menu and an unpinned one returns to it`() {
        val pins = setOf(SHARE)
        val menu = menuActions(pins, solo)
        assertFalse(SHARE in menu)
        assertTrue(RADIO in menu)
        assertTrue(QUEUE in menu)
        assertEquals(menu, NowPlayingAction.entries.filter { it in menu }) // canonical order
    }

    @Test fun `with Queue unpinned nothing trails the menu`() {
        assertEquals(emptyList<NowPlayingAction>(), topRowActions(setOf(RADIO), solo).trailing)
    }

    // ── visibility rules ─────────────────────────────────────────────────────────────────────

    @Test fun `in a room Radio, Queue and Speed are hidden pinned or not`() {
        val room = ActionContext(inRoom = true)
        val pins = setOf(RADIO, QUEUE, PLAYBACK_SPEED, SHARE)
        val row = topRowActions(pins, room)
        assertEquals(listOf(SHARE), row.leading)
        assertEquals(emptyList<NowPlayingAction>(), row.trailing)
        val menu = menuActions(emptySet(), room)
        assertFalse(RADIO in menu)
        assertFalse(QUEUE in menu)
        assertFalse(PLAYBACK_SPEED in menu)
        assertTrue(LISTEN_TOGETHER in menu)
    }

    @Test fun `casting hides Speed only`() {
        val cast = ActionContext(casting = true)
        assertFalse(PLAYBACK_SPEED in topRowActions(setOf(PLAYBACK_SPEED, RADIO), cast).leading)
        assertTrue(RADIO in topRowActions(setOf(PLAYBACK_SPEED, RADIO), cast).leading)
        assertFalse(PLAYBACK_SPEED in menuActions(emptySet(), cast))
    }

    @Test fun `Post to Community shows only while Community is on`() {
        assertFalse(POST_TO_COMMUNITY in menuActions(emptySet(), solo))
        assertFalse(POST_TO_COMMUNITY in topRowActions(setOf(POST_TO_COMMUNITY), solo).leading)
        val on = ActionContext(communityAvailable = true)
        assertTrue(POST_TO_COMMUNITY in menuActions(emptySet(), on))
        assertTrue(POST_TO_COMMUNITY in topRowActions(setOf(POST_TO_COMMUNITY), on).leading)
    }

    // ── the editor ───────────────────────────────────────────────────────────────────────────

    @Test fun `the editor lists room-hidden actions so they stay editable, with a reason`() {
        val room = ActionContext(inRoom = true)
        val rows = editableActions(NowPlayingAction.DefaultPins, room)
        assertTrue(RADIO in rows)
        assertTrue(PLAYBACK_SPEED in rows)
        assertEquals("Hidden while listening together", hiddenReason(RADIO, room))
        assertEquals("Hidden while casting", hiddenReason(PLAYBACK_SPEED, ActionContext(casting = true)))
        assertNull(hiddenReason(SHARE, room))
    }

    @Test fun `the editor hides Post to Community while Community is off unless it is still pinned`() {
        assertFalse(POST_TO_COMMUNITY in editableActions(emptySet(), solo))
        assertTrue(POST_TO_COMMUNITY in editableActions(setOf(POST_TO_COMMUNITY), solo))
    }

    // ── the cap ──────────────────────────────────────────────────────────────────────────────

    @Test fun `pinning adds and unpinning removes`() {
        assertEquals(PinToggle.Changed(setOf(RADIO, QUEUE, SHARE)), togglePin(NowPlayingAction.DefaultPins, SHARE))
        assertEquals(PinToggle.Changed(setOf(QUEUE)), togglePin(NowPlayingAction.DefaultPins, RADIO))
    }

    @Test fun `three extra pins fill the row and a fourth is refused`() {
        var pins: Set<NowPlayingAction> = NowPlayingAction.DefaultPins
        for (a in listOf(LYRICS, SHARE, DOWNLOAD)) {
            pins = (togglePin(pins, a) as PinToggle.Changed).pins
        }
        assertEquals(NowPlayingAction.MAX_PINS, pins.size)
        assertTrue(pinsFull(pins))
        assertEquals(PinToggle.Full, togglePin(pins, VIEW_ALBUM))
    }

    @Test fun `unpinning still works when the row is full`() {
        val full = setOf(RADIO, QUEUE, LYRICS, SHARE, DOWNLOAD)
        val after = togglePin(full, SHARE)
        assertEquals(PinToggle.Changed(setOf(RADIO, QUEUE, LYRICS, DOWNLOAD)), after)
        assertFalse(pinsFull((after as PinToggle.Changed).pins))
    }

    @Test fun `with Radio and Queue unpinned five others fit`() {
        var pins: Set<NowPlayingAction> = emptySet()
        for (a in listOf(LYRICS, SHARE, DOWNLOAD, VIEW_ALBUM, SAVE_TO_PLAYLIST)) {
            pins = (togglePin(pins, a) as PinToggle.Changed).pins
        }
        assertEquals(PinToggle.Full, togglePin(pins, RADIO))
    }
}
