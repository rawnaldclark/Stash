package com.stash.core.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [AlbumSquareCard]'s optional long press (Home's Because you like and New from your artists cards open their sheet
 * with it): a long press calls `onLongClick` and not `onClick`, a tap still calls `onClick`, TalkBack reads the label,
 * and a card given no `onLongClick` has no long-press action at all, as every card had before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlbumSquareCardLongPressTest {

    @get:Rule
    val rule = createComposeRule()

    private var taps = 0
    private var longPresses = 0

    private fun show(withLongPress: Boolean) {
        rule.setContent {
            MaterialTheme {
                AlbumSquareCard(
                    title = "Bring Your Love", artist = "Sabrina Carpenter", thumbnailUrl = null, year = "2026",
                    modifier = Modifier.testTag(CARD),
                    onLongClickLabel = if (withLongPress) "More for Bring Your Love" else null,
                    onLongClick = if (withLongPress) ({ longPresses++ }) else null,
                    onClick = { taps++ },
                )
            }
        }
    }

    @Test fun `a long press calls onLongClick and not onClick`() {
        show(withLongPress = true)
        rule.onNodeWithTag(CARD).performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals(1, longPresses)
        assertEquals(0, taps)
    }

    @Test fun `with a long press set, a tap still calls onClick`() {
        show(withLongPress = true)
        rule.onNodeWithTag(CARD).performClick()
        rule.waitForIdle()
        assertEquals(1, taps)
        assertEquals(0, longPresses)
    }

    @Test fun `TalkBack offers the long press under its label`() {
        show(withLongPress = true)
        rule.onNodeWithTag(CARD).assert(
            SemanticsMatcher("long click labelled 'More for Bring Your Love'") {
                it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "More for Bring Your Love"
            },
        )
    }

    @Test fun `without onLongClick the card has no long-press action and a tap calls onClick`() {
        show(withLongPress = false)
        rule.onNodeWithTag(CARD).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
        rule.onNodeWithTag(CARD).performClick()
        rule.waitForIdle()
        assertEquals(1, taps)
    }

    private companion object {
        const val CARD = "card"
    }
}
