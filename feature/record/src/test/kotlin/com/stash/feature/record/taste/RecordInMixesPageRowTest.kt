package com.stash.feature.record.taste

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Daily Discover's "Your record" toggle: one short label beside Download, a tap flips it, TalkBack hears a switch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordInMixesPageRowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `one short label and no sentence`() {
        compose.setContent { RecordInMixesPageRow(on = false, onChange = {}) }
        compose.onNodeWithText("Your record").assertExists()
        compose.onNodeWithText("lean on", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a tap asks to turn it on`() {
        val received = mutableListOf<Boolean>()
        compose.setContent { RecordInMixesPageRow(on = false, onChange = { received += it }) }
        compose.onNodeWithText("Your record").performClick()
        assertThat(received).containsExactly(true)
    }

    @Test
    fun `TalkBack hears a switch, off then on`() {
        val role = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        compose.setContent { RecordInMixesPageRow(on = false, onChange = {}) }
        compose.onNodeWithText("Your record").assert(role)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
    }

    @Test
    fun `on reads as on`() {
        compose.setContent { RecordInMixesPageRow(on = true, onChange = {}) }
        compose.onNodeWithText("Your record")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
    }
}
