package com.stash.feature.settings.cloud

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.stash.core.ui.components.LocalAppSnackbar
import com.stash.core.ui.components.rememberAppSnackbar
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * Drive's messages carry email addresses, so they go on the app's snackbar, which wraps: a toast is
 * cut to two lines, and on a phone "Saves and backup go to … from now o…" lost its end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudMessagesTest {

    @get:Rule
    val rule = createComposeRule()

    private val long = "Saving 2 songs. 3 songs are waiting on ronald.clark.long.address@gmail.com."

    @Test
    fun `in the app, a message goes on the snackbar, whole`() {
        val host = SnackbarHostState()
        rule.setContent {
            CompositionLocalProvider(LocalAppSnackbar provides rememberAppSnackbar(host)) {
                val show = rememberCloudMessages()
                LaunchedEffect(Unit) { show(long) }
            }
        }
        rule.waitForIdle()
        assertThat(host.currentSnackbarData?.visuals?.message).isEqualTo(long)
        assertThat(ShadowToast.shownToastCount()).isEqualTo(0)
    }

    @Test
    fun `with no snackbar (a preview, a test), it falls back to a toast`() {
        rule.setContent {
            val show = rememberCloudMessages()
            LaunchedEffect(Unit) { show(long) }
        }
        rule.waitForIdle()
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(long)
    }
}
