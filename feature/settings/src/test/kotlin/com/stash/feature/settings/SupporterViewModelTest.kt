package com.stash.feature.settings

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.supporter.ClaimResult
import com.stash.core.data.supporter.SupporterRepository
import com.stash.data.download.lossless.quota.LosslessAllowance
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.quota.LosslessQuotaRefresher
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.RelayEntry
import com.stash.data.download.lossless.relay.TidalRelay
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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

@OptIn(ExperimentalCoroutinesApi::class)
class SupporterViewModelTest {

    private val reset = 1_792_281_600L
    private val allowance = MutableStateFlow<LosslessAllowance?>(null)
    private val token = MutableStateFlow<String?>(null)
    private val quota: LosslessQuota = mockk {
        every { state } returns allowance
        every { current() } answers { allowance.value }
    }
    private val supporter: SupporterRepository = mockk(relaxUnitFun = true) {
        every { this@mockk.token } returns this@SupporterViewModelTest.token
    }
    private val refresher: LosslessQuotaRefresher = mockk()
    private val config: LosslessConfigFetcher = mockk {
        every { relays } returns MutableStateFlow(listOf(RelayEntry("https://relay.example")))
        every { tidalRelay } returns MutableStateFlow<TidalRelay?>(null)
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = SupporterViewModel(supporter, refresher, quota, config)

    @Test fun `the row follows the relays' limit, not the token`() {
        assertThat(supporterRow(null, relayConfigured = false, hasToken = false)).isEqualTo(SupporterRow.Hidden)
        assertThat(supporterRow(null, relayConfigured = true, hasToken = false)).isEqualTo(SupporterRow.Offer(false))
        // A stored token the relays count as free (revoked, over the ceiling) still offers the claim.
        assertThat(supporterRow(LosslessAllowance(3, 500, reset), true, hasToken = true)).isEqualTo(SupporterRow.Offer(true))
        assertThat(supporterRow(LosslessAllowance(3, null, reset), true, hasToken = true)).isEqualTo(SupporterRow.Supporter(true))
    }

    @Test fun `each answer reads as a plain step or message`() {
        val email = SupporterClaimUi()
        assertThat(email.after(ClaimResult.CodeSent, "fan@example.com"))
            .isEqualTo(SupporterClaimUi(step = SupporterClaimUi.Step.Code, email = "fan@example.com"))
        assertThat(email.after(ClaimResult.BadEmail, "x").message).contains("email address")
        assertThat(email.after(ClaimResult.RateLimited(120), "x").message).contains("2 minutes")
        assertThat(email.after(ClaimResult.RateLimited(null), "x").message).contains("a little while")
        assertThat(email.after(ClaimResult.NotConfigured, "x").message).contains("isn't available")
        assertThat(email.after(ClaimResult.Unreachable, "x").message).contains("connection")

        val code = SupporterClaimUi(step = SupporterClaimUi.Step.Code, email = "fan@example.com")
        assertThat(code.after(ClaimResult.BadCode, "fan@example.com").step).isEqualTo(SupporterClaimUi.Step.Code)
        val over = code.after(ClaimResult.StartOver, "fan@example.com")
        assertThat(over.step).isEqualTo(SupporterClaimUi.Step.Email)
        assertThat(over.email).isEqualTo("fan@example.com")
        assertThat(over.message).contains("new one")
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
    }

    @Test fun `removing the code clears it and asks the relays again`() = runTest {
        coEvery { refresher.refreshNow() } returns true
        vm().removeCode()
        coVerify { supporter.clear() }
        coVerify { refresher.refreshNow() }
    }
}
