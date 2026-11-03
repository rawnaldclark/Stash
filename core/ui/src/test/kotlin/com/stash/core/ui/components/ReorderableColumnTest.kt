package com.stash.core.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The drag through real touches on a real Column: the handle carries its row past half of each neighbour, rows of
 * different heights included, the drop sends ONE move from where the row started to where it landed, and the rows then
 * show the dropped order in their slots. A drag that crosses no half moves nothing, and holding a row at the bottom edge
 * scrolls the page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReorderableColumnTest {

    @get:Rule
    val rule = createComposeRule()

    private val heights = mapOf("a" to 40.dp, "b" to 60.dp, "c" to 40.dp, "d" to 80.dp, "e" to 40.dp)
    private var order by mutableStateOf(listOf("a", "b", "c", "d", "e"))
    private val moves = mutableListOf<Pair<String, Int>>()
    private lateinit var scroll: ScrollState

    /** Every haptic the drag asks for. */
    private val ticks = mutableListOf<HapticFeedbackType>()
    private val haptics = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            ticks += hapticFeedbackType
        }
    }

    /**
     * The rows in a page [pageHeight] tall, with [below] more of the page after them (a Settings page's other cards); each
     * drop is saved the way a screen would, and comes back as the order.
     */
    private fun start(pageHeight: Dp = 400.dp, below: Dp = 0.dp) {
        rule.setContent {
            scroll = rememberScrollState()
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                Column(Modifier.size(width = 200.dp, height = pageHeight).verticalScroll(scroll)) {
                    val state = rememberReorderableColumnState(
                        keys = order,
                        scrollState = scroll,
                        onMove = { key, to ->
                            moves += key to to
                            order = order.toMutableList().apply { add(to, removeAt(indexOf(key))) }
                        },
                        bottomInset = 0.dp,
                    )
                    Column(Modifier.fillMaxWidth().reorderableColumn(state)) {
                        state.rows.forEach { k ->
                            key(k) {
                                Row(Modifier.fillMaxWidth().reorderableRow(state, k).height(heights.getValue(k)).testTag("row-$k")) {
                                    Box(Modifier.size(40.dp).reorderHandle(state, k).testTag("handle-$k"))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(below))
                }
            }
        }
        rule.waitForIdle()
        // A held row runs the edge scroll every frame, so the clock only moves when the test moves it.
        rule.mainClock.autoAdvance = false
    }

    private fun px(dp: Dp) = with(rule.density) { dp.toPx() }

    /** Holds [row]'s handle and drags it [by] in small steps, the way a finger moves; lifts unless [lift] is false. */
    private fun drag(row: String, by: Dp, lift: Boolean = true) {
        rule.onNodeWithTag("handle-$row").performTouchInput {
            down(center)
            val steps = 20
            repeat(steps) { moveBy(Offset(0f, px(by) / steps)) }
            if (lift) up()
        }
        rule.waitForIdle()
    }

    private fun assertTop(row: String, top: Dp) {
        val actual = rule.onNodeWithTag("row-$row").fetchSemanticsNode().positionInRoot.y
        assertEquals("$row's top", px(top), actual, 1.5f)
    }

    @Test
    fun `a drag down past rows of different heights is one move, and the rows settle in the dropped order`() {
        start()
        // a (40) down 110: past half of b (60), then of c (40), not of d (80)
        drag("a", 110.dp)
        assertEquals(listOf("a" to 2), moves)
        assertEquals(listOf("b", "c", "a", "d", "e"), order)
        // One light tick as it was picked up, none for the two swaps
        assertEquals(listOf(DragPickUpHaptic), ticks)
        rule.mainClock.advanceTimeBy(1_000)
        assertTop("b", 0.dp)
        assertTop("c", 60.dp)
        assertTop("a", 100.dp)
        assertTop("d", 140.dp)
    }

    @Test
    fun `a drag up takes half of each neighbour`() {
        start()
        // d (80) up 70: past half of c (40), not of b (60)
        drag("d", (-70).dp)
        assertEquals(listOf("d" to 2), moves)
        assertEquals(listOf("a", "b", "d", "c", "e"), order)
    }

    @Test
    fun `a drag that crosses no half moves nothing and the row eases back`() {
        start()
        drag("b", 15.dp)
        assertTrue(moves.isEmpty())
        rule.mainClock.advanceTimeBy(1_000)
        assertTop("b", 40.dp)
    }

    @Test
    fun `an order that changes under the finger drops the move`() {
        start()
        drag("a", 110.dp, lift = false)
        order = listOf("e", "a", "b", "c", "d")
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("handle-a").performTouchInput { up() }
        rule.waitForIdle()
        assertTrue(moves.isEmpty())
        rule.mainClock.advanceTimeBy(1_000)
        // The new order shows once the row is put down
        assertTop("e", 0.dp)
        assertTop("a", 40.dp)
    }

    @Test
    fun `holding a row at the bottom edge scrolls the page under it`() {
        // 260dp of rows in a 150dp page
        start(pageHeight = 150.dp)
        assertEquals(0, scroll.value)
        drag("a", 110.dp, lift = false)
        rule.mainClock.advanceTimeBy(500)
        assertTrue("scrolled ${scroll.value}", scroll.value > 0)
        rule.onNodeWithTag("handle-a").performTouchInput { up() }
        rule.waitForIdle()
        // Carried further down by the scroll than the finger went on its own
        assertEquals("a", moves.single().first)
        assertTrue("dropped at ${moves.single().second}", moves.single().second >= 3)
    }

    @Test
    fun `with more page below the list, the edge scroll stops at the list's end and the row stays in it`() {
        // 260dp of rows in a 150dp page, with 400dp of other cards after them
        start(pageHeight = 150.dp, below = 400.dp)
        drag("a", 110.dp, lift = false)
        rule.mainClock.advanceTimeBy(2_000)
        // Scrolled just far enough to show the list's last row, not on into the cards below
        assertEquals(px(110.dp), scroll.value.toFloat(), 2f)
        val held = rule.onNodeWithTag("row-a").fetchSemanticsNode()
        assertTrue("drawn at ${held.positionInRoot.y}", held.positionInRoot.y + held.size.height <= px(150.dp) + 1.5f)
        rule.onNodeWithTag("handle-a").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(listOf("a" to 4), moves)
    }
}
