package com.stash.feature.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The exact-Hz field as drawn: one decimal at most, clamping said before Set, junk refused. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TuningExactFieldTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val submitted = mutableListOf<Float>()

    private fun show(initial: String = "") {
        rule.setContent {
            var text by remember { mutableStateOf(initial) }
            TuningExactField(text = text, onTextChange = { text = it }, onSubmit = { submitted += it })
        }
    }

    private val set get() = rule.onNodeWithText(context.getString(R.string.settings_tuning_set))
    private val field get() = rule.onNodeWithTag(TUNING_EXACT_FIELD_TAG)

    @Test fun `an in-range value sets as typed and says its cents`() {
        show()
        field.performTextInput("432")
        rule.onNodeWithText(context.getString(R.string.settings_tuning_cents_lower, "31.8")).assertExists()
        set.assertIsEnabled().performClick()
        assertThat(submitted).containsExactly(432f)
    }

    @Test fun `a second decimal place is refused`() {
        show()
        field.performTextInput("431.5")
        field.performTextInput("5")
        field.assertTextContains("431.5")
        set.performClick()
        assertThat(submitted).containsExactly(431.5f)
    }

    @Test fun `past the range it says where it will clamp, then clamps`() {
        show()
        field.performTextInput("500")
        rule.onNodeWithText(context.getString(R.string.settings_tuning_exact_highest, "494")).assertExists()
        set.performClick()
        field.performTextReplacement("300")
        rule.onNodeWithText(context.getString(R.string.settings_tuning_exact_lowest, "392")).assertExists()
        set.performClick()
        assertThat(submitted).containsExactly(494f, 392f).inOrder()
    }

    @Test fun `empty or not a number can't be set`() {
        show()
        set.assertIsNotEnabled()
        field.performTextInput(".")
        rule.onNodeWithText(context.getString(R.string.settings_tuning_exact_invalid)).assertExists()
        set.assertIsNotEnabled()
        assertThat(submitted).isEmpty()
    }

    @Test fun `letters never reach the field`() {
        show("44")
        field.performTextInput("x")
        field.assertTextContains("44")
    }
}
