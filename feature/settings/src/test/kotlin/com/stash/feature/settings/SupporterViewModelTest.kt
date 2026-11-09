package com.stash.feature.settings

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.supporter.ClaimResult
import com.stash.core.data.supporter.SupporterRepository
import com.stash.data.download.lossless.LosslessAvailability
import com.stash.data.download.lossless.quota.LosslessAllowance
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.quota.LosslessQuotaRefresher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupporterViewModelTest {

    private val reset = 1_792_281_600L
    private val allowance = MutableStateFlow<LosslessAllowance?>(null)
    private val token = MutableStateFlow<String?>(null)
    private val quota: LosslessQuota = mockk {
        every { state } returns allowance
        every { current() } answers { allowance.value }
        every { liftSpent() } returns Unit
    }
    private val supporter: SupporterRepository = mockk(relaxUnitFun = true) {
        every { this@mockk.token } returns this@SupporterViewModelTest.token
    }
    private val refresher: LosslessQuotaRefresher = mockk()
    private val availability: LosslessAvailability = mockk {
        every { stashTidalRelayInUse } returns MutableStateFlow(true)
    }

    /** The module's own strings: what the app shows, not a copy of it. */
    private val res: Resources = ApplicationProvider.getApplicationContext<Context>().resources
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = SupporterViewModel(context, supporter, refresher, quota, availability)

    @Test fun `the row follows the relays' limit, not the token`() {
        assertThat(supporterRow(null, relayConfigured = false, hasToken = false)).isEqualTo(SupporterRow.Hidden)
        assertThat(supporterRow(null, relayConfigured = true, hasToken = false)).isEqualTo(SupporterRow.Offer(false))
        // A stored token the relays count as free (revoked, over the ceiling) still offers the claim.
        assertThat(supporterRow(LosslessAllowance(3, 500, reset), true, hasToken = true)).isEqualTo(SupporterRow.Offer(true))
        assertThat(supporterRow(LosslessAllowance(3, null, reset), true, hasToken = true)).isEqualTo(SupporterRow.Supporter(true))
    }

    @Test fun `each answer reads as a plain step or message`() {
        val email = SupporterClaimUi()
        assertThat(email.after(ClaimResult.CodeSent, "fan@example.com", res))
            .isEqualTo(SupporterClaimUi(step = SupporterClaimUi.Step.Code, email = "fan@example.com"))
        assertThat(email.after(ClaimResult.BadEmail, "x", res).message).contains("email address")
        assertThat(email.after(ClaimResult.RateLimited(120), "x", res).message).isEqualTo("Too many tries. Wait 2 minutes.")
        assertThat(email.after(ClaimResult.RateLimited(null), "x", res).message).isEqualTo("Too many tries. Wait a minute.")
        assertThat(email.after(ClaimResult.RateLimited(30), "x", res).message).isEqualTo("Too many tries. Wait a minute.")
        assertThat(email.after(ClaimResult.NotConfigured, "x", res).message).contains("isn't available")
        assertThat(email.after(ClaimResult.Unreachable, "x", res).message).contains("connection")

        val code = SupporterClaimUi(step = SupporterClaimUi.Step.Code, email = "fan@example.com")
        // Wrong, expired or used up all read the same, on the code step with "Send a new code".
        val bad = code.after(ClaimResult.BadCode, "fan@example.com", res)
        assertThat(bad.step).isEqualTo(SupporterClaimUi.Step.Code)
        assertThat(bad.email).isEqualTo("fan@example.com")
        assertThat(bad.message).isEqualTo("That code didn't work. Check it, or send a new one.")
        assertThat(bad.messageIsError).isTrue()

        val resent = bad.after(ClaimResult.CodeSent, "fan@example.com", res)
        assertThat(resent.step).isEqualTo(SupporterClaimUi.Step.Code)
        assertThat(resent.message).isEqualTo("A new code is on its way.")
        assertThat(resent.messageIsError).isFalse()
    }

    @Test fun `send a new code asks again for the same address`() = runTest {
        coEvery { supporter.claimStart(any()) } returns ClaimResult.CodeSent
        coEvery { supporter.claimVerify(any(), any()) } returns ClaimResult.BadCode
        val vm = vm()
        vm.sendCode("fan@example.com")
        vm.unlock("111111")
        assertThat(vm.claim.value.message).contains("send a new one")

        vm.resendCode()

        coVerify(exactly = 2) { supporter.claimStart("fan@example.com") }
        assertThat(vm.claim.value.step).isEqualTo(SupporterClaimUi.Step.Code)
        assertThat(vm.claim.value.message).isEqualTo("A new code is on its way.")
    }

    @Test fun `a right code asks the relays and closes once they report unlimited`() = runTest {
        coEvery { supporter.claimStart(" fan@example.com ") } returns ClaimResult.CodeSent
        coEvery { supporter.claimVerify("fan@example.com", "123456") } returns ClaimResult.Unlocked
        coEvery { refresher.refreshNow() } answers {
            allowance.value = LosslessAllowance(500, null, reset)
            true
        }
        val vm = vm()

        vm.sendCode(" fan@example.com ")
        assertThat(vm.claim.value.step).isEqualTo(SupporterClaimUi.Step.Code)
        vm.unlock("123456")

        coVerify(exactly = 1) { refresher.refreshNow() }
        assertThat(vm.claim.value.done).isTrue()
        assertThat(vm.claim.value.busy).isFalse()
    }

    @Test fun `a right code the relays do not confirm keeps the sheet open and says so`() = runTest {
        coEvery { supporter.claimStart(any()) } returns ClaimResult.CodeSent
        coEvery { supporter.claimVerify(any(), any()) } returns ClaimResult.Unlocked
        coEvery { refresher.refreshNow() } returns false
        val vm = vm()

        vm.sendCode("fan@example.com")
        vm.unlock("123456")

        assertThat(vm.claim.value.done).isFalse()
        assertThat(vm.claim.value.message).contains("Code accepted")
        assertThat(vm.claim.value.messageIsError).isFalse()
    }

    @Test fun `a right code lifts the spent day even when the relays can't be reached`() = runTest {
        coEvery { supporter.claimStart(any()) } returns ClaimResult.CodeSent
        coEvery { supporter.claimVerify(any(), any()) } returns ClaimResult.Unlocked
        coEvery { refresher.refreshNow() } returns false // offline
        val vm = vm()

        vm.sendCode("fan@example.com")
        vm.unlock("123456")

        verify(exactly = 1) { quota.liftSpent() }
    }

    @Test fun `a wrong code lifts nothing`() = runTest {
        coEvery { supporter.claimStart(any()) } returns ClaimResult.CodeSent
        coEvery { supporter.claimVerify(any(), any()) } returns ClaimResult.BadCode
        val vm = vm()

        vm.sendCode("fan@example.com")
        vm.unlock("123456")

        verify(exactly = 0) { quota.liftSpent() }
    }

    @Test fun `removing the code clears it and asks the relays again`() = runTest {
        coEvery { refresher.refreshNow() } returns true
        vm().removeCode()
        coVerify { supporter.clear() }
        coVerify { refresher.refreshNow() }
    }
}
