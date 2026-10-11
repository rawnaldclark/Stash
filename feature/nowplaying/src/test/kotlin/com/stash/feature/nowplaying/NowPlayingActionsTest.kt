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

    // ── the cap (the rule itself lives in core: NowPlayingActionTest / NowPlayingPreferenceTest) ──

    @Test fun `the editor sees the row as full at five pins`() {
        assertFalse(pinsFull(setOf(RADIO, QUEUE, LYRICS, SHARE)))
        assertTrue(pinsFull(setOf(RADIO, QUEUE, LYRICS, SHARE, DOWNLOAD)))
    }

    // ── a pinned Download never deletes ──────────────────────────────────────────────────────

    @Test fun `a pinned Download on a downloaded track is inert`() {
        assertTrue(isInertPinnedTap(DOWNLOAD, ActionContext(isDownloaded = true)))
        assertFalse(isInertPinnedTap(DOWNLOAD, ActionContext(isDownloaded = false)))
        assertFalse(isInertPinnedTap(SHARE, ActionContext(isDownloaded = true)))
    }

    @Test fun `Remove download stays a menu row while Download is pinned`() {
        val pins = setOf(RADIO, QUEUE, DOWNLOAD)
        assertTrue(DOWNLOAD in menuActions(pins, ActionContext(isDownloaded = true)))
        // Not downloaded: the pin downloads, and the menu doesn't repeat it.
        assertFalse(DOWNLOAD in menuActions(pins, ActionContext(isDownloaded = false)))
    }

    // ── fitting the row ──────────────────────────────────────────────────────────────────────

    /** The full row from the device proof: Radio, Lyrics, Share, Download and Queue pinned. */
    private val fullRow = topRowActions(setOf(RADIO, LYRICS, SHARE, DOWNLOAD, QUEUE), solo)

    /** The row's width on a phone [screenDp] wide: Now Playing keeps 24dp each side. */
    private fun rowWidth(screenDp: Float) = screenDp - 48f

    @Test fun `the default row fits at 48dp on every phone`() {
        for (screen in listOf(320f, 360f, 393f)) {
            val fit = fitTopRow(topRowActions(NowPlayingAction.DefaultPins, solo), rowWidth(screen), castVisible = true)
            assertEquals(listOf(RADIO), fit.row.leading)
            assertEquals(listOf(QUEUE), fit.row.trailing)
            assertEquals(emptyList<NowPlayingAction>(), fit.overflow)
            assertEquals(SLOT_PREFERRED_DP, fit.slotDp, 0f)
        }
    }

    @Test fun `a full row on a 393dp Pixel 5 drops the lowest-priority pin into the menu`() {
        val fit = fitTopRow(fullRow, rowWidth(393f), castVisible = true)
        assertEquals(listOf(RADIO, LYRICS, SHARE), fit.row.leading)
        assertEquals(listOf(QUEUE), fit.row.trailing)
        assertEquals(listOf(DOWNLOAD), fit.overflow)
        assertFits(fit, rowWidth(393f), castVisible = true)
    }

    @Test fun `a full row on a 360dp phone`() {
        val fit = fitTopRow(fullRow, rowWidth(360f), castVisible = true)
        assertEquals(listOf(RADIO, LYRICS, SHARE), fit.row.leading)
        assertEquals(listOf(DOWNLOAD), fit.overflow)
        assertFits(fit, rowWidth(360f), castVisible = true)
    }

    @Test fun `a full row on a 320dp phone keeps Queue and two pins`() {
        val fit = fitTopRow(fullRow, rowWidth(320f), castVisible = true)
        assertEquals(listOf(RADIO, LYRICS), fit.row.leading)
        assertEquals(listOf(QUEUE), fit.row.trailing)
        assertEquals(listOf(SHARE, DOWNLOAD), fit.overflow)
        assertFits(fit, rowWidth(320f), castVisible = true)
    }

    @Test fun `Display size Largest on a Pixel 5 (about 314dp wide) still keeps Queue`() {
        // 1080px at 550dpi (Largest) = 314dp.
        val fit = fitTopRow(fullRow, rowWidth(314f), castVisible = true)
        assertEquals(listOf(QUEUE), fit.row.trailing)
        assertEquals(listOf(RADIO), fit.row.leading)
        assertEquals(listOf(LYRICS, SHARE, DOWNLOAD), fit.overflow)
        assertFits(fit, rowWidth(314f), castVisible = true)
    }

    @Test fun `without Cast a pin more fits`() {
        val fit = fitTopRow(fullRow, rowWidth(393f), castVisible = false)
        assertEquals(listOf(RADIO, LYRICS, SHARE, DOWNLOAD), fit.row.leading)
        assertEquals(emptyList<NowPlayingAction>(), fit.overflow)
        assertFits(fit, rowWidth(393f), castVisible = false)
    }

    @Test fun `a wide window shows every pin at 48dp`() {
        val fit = fitTopRow(fullRow, rowWidth(600f), castVisible = true)
        assertEquals(4, fit.row.leading.size)
        assertEquals(SLOT_PREFERRED_DP, fit.slotDp, 0f)
    }

    @Test fun `overflowed pins come back as ordinary menu rows in canonical order`() {
        val pins = setOf(RADIO, LYRICS, SHARE, DOWNLOAD, QUEUE)
        val fit = fitTopRow(topRowActions(pins, solo), rowWidth(320f), castVisible = true)
        val menu = menuActions(pins, solo, fit.overflow)
        assertTrue(SHARE in menu)
        assertTrue(DOWNLOAD in menu)
        assertFalse(RADIO in menu)
        assertFalse(QUEUE in menu)
        assertEquals(menu, NowPlayingAction.entries.filter { it in menu })
    }

    /** Every rendered slot is at least 44dp and the whole row fits the width. */
    private fun assertFits(fit: TopRowFit, width: Float, castVisible: Boolean) {
        assertTrue("slot ${fit.slotDp} under the 44dp floor", fit.slotDp >= SLOT_MIN_DP)
        val slots = 2 + fit.row.size // Dismiss + ⋮ + the shown pins (Queue included)
        val used = slots * fit.slotDp + if (castVisible) CAST_SLOT_DP else 0f
        assertTrue("row needs $used dp of $width", used <= width + 0.001f)
    }
}
