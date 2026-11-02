package com.stash.feature.settings

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.supporter.SupporterRepository
import com.stash.core.data.tipjar.DonationGoal
import com.stash.core.data.tipjar.DonationGoalRepository
import com.stash.data.download.lossless.quota.LosslessAllowance
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.RelayEntry
import com.stash.data.download.lossless.relay.TidalRelay
import com.stash.feature.settings.components.GoalLine
import com.stash.feature.settings.components.SupportCardState
import com.stash.feature.settings.components.SupportPerk
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The Support card's ViewModel: the supporter state with the month's goal, and one goal ask when Settings opens. */
@OptIn(ExperimentalCoroutinesApi::class)
class SupportCardViewModelTest {

    private val reset = 1_792_281_600L
    private val allowance = MutableStateFlow<LosslessAllowance?>(null)
    private val token = MutableStateFlow<String?>(null)
    private val goal = MutableStateFlow<DonationGoal?>(null)
    private val relays = MutableStateFlow(listOf(RelayEntry("https://relay.example")))

    private val quota: LosslessQuota = mockk { every { state } returns allowance }
    private val supporter: SupporterRepository = mockk { every { this@mockk.token } returns this@SupportCardViewModelTest.token }
    private val config: LosslessConfigFetcher = mockk {
        every { this@mockk.relays } returns this@SupportCardViewModelTest.relays
        every { tidalRelay } returns MutableStateFlow<TidalRelay?>(null)
    }
    private val goals: DonationGoalRepository = mockk {
        every { this@mockk.goal } returns this@SupportCardViewModelTest.goal
        coEvery { refreshIfDue() } returns true
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = SupportCardViewModel(goals, supporter, quota, config)

    @Test fun `opening Settings asks for the goal once`() {
        vm()
        coVerify(exactly = 1) { goals.refreshIfDue() }
    }

    @Test fun `a failed goal ask leaves the card without its goal line`() = runTest {
        coEvery { goals.refreshIfDue() } throws IllegalStateException("boom")
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertThat(vm.state.value).isEqualTo(SupportCardState(goal = null, perk = SupportPerk.Offer))
    }

    @Test fun `the card follows the goal and the relays' word`() = runTest {
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertThat(vm.state.value).isEqualTo(SupportCardState(goal = null, perk = SupportPerk.Offer))

        goal.value = DonationGoal(16_800, 20_000, "2026-11")
        assertThat(vm.state.value.goal).isEqualTo(GoalLine("$168", "$200", 0.84f))

        // The code is claimed: the relays say unlimited, and the card thanks them.
        token.value = "tok.en"
        allowance.value = LosslessAllowance(3, null, reset)
        assertThat(vm.state.value.perk).isEqualTo(SupportPerk.Supporter)
        assertThat(vm.state.value.goal).isNotNull()
    }

    @Test fun `without an owner relay the card makes no promise`() = runTest {
        relays.value = emptyList()
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertThat(vm.state.value.perk).isEqualTo(SupportPerk.None)
    }
}
