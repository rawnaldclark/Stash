package com.stash.feature.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Edit Home button at the end of Home's feed: it says so, and a tap reports once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditHomeButtonTest {

    @get:Rule
    val rule = createComposeRule()

    @Test fun `Edit Home is a button and a tap opens the layout once`() {
        var taps = 0
        rule.setContent { MaterialTheme { EditHomeButton(onClick = { taps++ }) } }
        rule.onNodeWithText("Edit Home").assertHasClickAction().performClick()
        rule.waitForIdle()
        assertEquals(1, taps)
    }
}
