package com.stash.core.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What each role lets a menu show: transport, Add to queue, and the words on Play next. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListenTogetherRoleTest {
    @get:Rule
    val rule = createComposeRule()

    private fun label(role: ListenTogetherRole, hostName: String? = null): String {
        var label = ""
        rule.setContent {
            CompositionLocalProvider(LocalListenTogetherHostName provides hostName) { label = playNextLabel(role, "Play next") }
        }
        rule.waitForIdle()
        return label
    }

    @Test fun `a session listener is a guest without Add to queue, whose Play next is Request`() {
        val role = ListenTogetherRole.SESSION_LISTENER
        assertThat(role.isGuest).isTrue()
        assertThat(role.canQueue).isFalse()
        assertThat(label(role, hostName = "Maya")).isEqualTo("Request")
    }

    @Test fun `the other roles are as before`() {
        assertThat(ListenTogetherRole.entries.filter { it.isGuest })
            .containsExactly(ListenTogetherRole.GUEST, ListenTogetherRole.LISTENER, ListenTogetherRole.SESSION_LISTENER)
        assertThat(ListenTogetherRole.entries.filterNot { it.canQueue })
            .containsExactly(ListenTogetherRole.LISTENER, ListenTogetherRole.SESSION_LISTENER)
    }

    @Test fun `a listener who suggests names the host`() {
        assertThat(label(ListenTogetherRole.LISTENER, hostName = "Maya")).isEqualTo("Suggest to Maya")
    }

    @Test fun `the host and a guest who adds keep Play next`() {
        assertThat(label(ListenTogetherRole.GUEST)).isEqualTo("Play next")
    }
}
