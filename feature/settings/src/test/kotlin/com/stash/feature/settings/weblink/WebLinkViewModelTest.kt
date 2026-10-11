package com.stash.feature.settings.weblink

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.weblink.DeviceTrust
import com.stash.core.data.weblink.LinkedDevice
import com.stash.core.data.weblink.PairingSession
import com.stash.core.data.weblink.PairingSessionFactory
import com.stash.core.data.weblink.PairingState
import com.stash.core.data.weblink.WebLinkRepository
import com.stash.core.data.weblink.WebLinkResult
import com.stash.core.data.weblink.WebLinkStatus
import com.stash.core.data.weblink.handoff.HandoffPrefs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The "Linked to Chrome on Windows" result card must not outlive its link: Unlink everything (or that browser being
 * removed, here or from the web) clears it at once, not on the next visit to the screen.
 *
 * The repository and the pairing session are faked at their flows: [status] stands in for [WebLinkRepository.status], and
 * [pairing] for [PairingSession.state], driven the way the real ones drive them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebLinkViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val me = LinkedDevice("d_me", "phone", "Pixel 5", isMe = true, trust = DeviceTrust.VERIFIED, lastSeenAt = 0)
    private val chrome = LinkedDevice("d_chrome", "web", "Chrome on Windows", isMe = false, trust = DeviceTrust.VERIFIED, lastSeenAt = 0)
    private val firefox = LinkedDevice("d_firefox", "web", "Firefox on Mac", isMe = false, trust = DeviceTrust.VERIFIED, lastSeenAt = 0)
    private fun linked(vararg devices: LinkedDevice, problem: String? = null) =
        WebLinkStatus.Linked(devices.toList(), epoch = 1, serverTime = 0, problem = problem)

    private val status = MutableStateFlow<WebLinkStatus>(WebLinkStatus.NotLinked)
    private val pairing = MutableStateFlow<PairingState>(PairingState.Idle)

    private val repo: WebLinkRepository = mockk {
        every { status } returns this@WebLinkViewModelTest.status
        every { notice } returns MutableStateFlow(null)
        every { noticeShown() } returns Unit
        coEvery { refresh() } returns WebLinkResult.Ok
        // As the real one: the space is deleted on the server, then the list says not linked.
        coEvery { unlinkEverything() } answers {
            this@WebLinkViewModelTest.status.value = WebLinkStatus.NotLinked
            WebLinkResult.Ok
        }
        coEvery { remove(any()) } answers {
            val gone = firstArg<String>()
            val s = this@WebLinkViewModelTest.status.value as WebLinkStatus.Linked
            this@WebLinkViewModelTest.status.value = s.copy(devices = s.devices.filter { it.id != gone })
            WebLinkResult.Ok
        }
    }

    private val session: PairingSession = mockk {
        every { state } returns pairing
        every { reset() } answers { pairing.value = PairingState.Idle }
        coEvery { open(any(), any()) } answers {
            pairing.value = PairingState.Confirm("Chrome on Windows", "123456")
            pairing.value
        }
        // A successful pairing: the session refreshes the list (now with the browser), then shows the result.
        coEvery { confirm() } answers {
            status.value = linked(me, chrome)
            pairing.value = PairingState.Linked("Chrome on Windows", chrome.id)
            pairing.value
        }
    }

    private val factory: PairingSessionFactory = mockk { every { create() } returns session }
    private val handoffPrefs: HandoffPrefs = mockk { every { enabled } returns MutableStateFlow(true) }

    private fun vm() = WebLinkViewModel(repo, factory, SavedStateHandle(), handoffPrefs)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    /** Scan, Link: the "Linked to Chrome on Windows" card is up. */
    private fun kotlinx.coroutines.test.TestScope.pairChrome(vm: WebLinkViewModel) {
        vm.openLink("https://stashfm.app/link#1.a.b.c")
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        assertThat(vm.pairing.value).isEqualTo(PairingState.Linked("Chrome on Windows", chrome.id))
    }

    @Test fun `pair success then unlink everything leaves no result card`() = runTest(dispatcher) {
        val vm = vm()
        pairChrome(vm)

        vm.unlinkEverything()
        advanceUntilIdle()

        assertThat(vm.status.value).isEqualTo(WebLinkStatus.NotLinked)
        assertThat(vm.pairing.value).isEqualTo(PairingState.Idle)
    }

    @Test fun `removing the browser that was just linked clears its card`() = runTest(dispatcher) {
        val vm = vm()
        pairChrome(vm)

        vm.remove(chrome.id)
        advanceUntilIdle()

        assertThat(vm.pairing.value).isEqualTo(PairingState.Idle)
    }

    @Test fun `removing another browser keeps the card for the one still linked`() = runTest(dispatcher) {
        val vm = vm()
        pairChrome(vm)
        status.value = linked(me, chrome, firefox)
        advanceUntilIdle()

        vm.remove(firefox.id)
        advanceUntilIdle()

        assertThat(vm.pairing.value).isEqualTo(PairingState.Linked("Chrome on Windows", chrome.id))
    }

    @Test fun `an unlink seen from elsewhere (a refresh) clears the card`() = runTest(dispatcher) {
        val vm = vm()
        pairChrome(vm)

        // The browser unlinked everything; this phone learns on its next read of the list.
        status.value = WebLinkStatus.NotLinked
        advanceUntilIdle()

        assertThat(vm.pairing.value).isEqualTo(PairingState.Idle)
    }

    @Test fun `a pairing in progress is never cleared by a not-linked list`() = runTest(dispatcher) {
        val vm = vm()
        vm.openLink("https://stashfm.app/link#1.a.b.c")
        advanceUntilIdle()
        assertThat(vm.pairing.value).isInstanceOf(PairingState.Confirm::class.java)

        status.value = WebLinkStatus.Loading
        advanceUntilIdle()
        status.value = WebLinkStatus.NotLinked
        advanceUntilIdle()

        assertThat(vm.pairing.value).isInstanceOf(PairingState.Confirm::class.java)
    }

    // ---------------------------------------------------------------- the rule itself

    @Test fun `rule - only a Linked result can be outlived`() {
        val outlived = WebLinkViewModel.Companion::pairingResultOutlived
        for (p in listOf(PairingState.Idle, PairingState.Checking, PairingState.Confirm("C", "1"), PairingState.Waiting("C", "1"), PairingState.Failed("x"))) {
            assertThat(outlived(WebLinkStatus.NotLinked, p)).isFalse()
        }
    }

    @Test fun `rule - a list read with a problem can't prove the browser is gone`() {
        val result = PairingState.Linked("Chrome on Windows", chrome.id)
        // A failed refresh keeps the last list, maybe from before the pairing.
        assertThat(WebLinkViewModel.pairingResultOutlived(linked(me, problem = "Offline"), result)).isFalse()
        assertThat(WebLinkViewModel.pairingResultOutlived(linked(me), result)).isTrue()
        assertThat(WebLinkViewModel.pairingResultOutlived(linked(me, chrome), result)).isFalse()
        assertThat(WebLinkViewModel.pairingResultOutlived(WebLinkStatus.Loading, result)).isFalse()
    }

    @Test fun `rule - a result without a device id is cleared only by a full unlink`() {
        val result = PairingState.Linked("Chrome on Windows")
        assertThat(WebLinkViewModel.pairingResultOutlived(linked(me), result)).isFalse()
        assertThat(WebLinkViewModel.pairingResultOutlived(WebLinkStatus.NotLinked, result)).isTrue()
    }
}
