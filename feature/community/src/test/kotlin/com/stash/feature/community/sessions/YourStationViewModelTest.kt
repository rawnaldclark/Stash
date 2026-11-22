package com.stash.feature.community.sessions

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.community.StationEdit
import com.stash.core.data.community.StationsRepository
import com.stash.core.model.community.Station
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class YourStationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<StationsRepository>(relaxed = true)
    private val own = Station(id = "Ab3dEf9h", name = "Rawn's Basement", hostName = "Rawn", vibe = "90s house", followers = 38, up = 31, down = 8, mine = true)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.mine() } returns CommunityResult.Ok(own)
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = YourStationViewModel(repo, mockk(relaxed = true))

    @Test fun `it opens on this phone's station`() = runTest(dispatcher) {
        val vm = vm(); advanceUntilIdle()
        assertThat(vm.state.value.station).isEqualTo(own)
        assertThat(vm.state.value.loading).isFalse()
    }

    @Test fun `without a station it says so, and one that can't load offers a retry`() = runTest(dispatcher) {
        coEvery { repo.mine() } returns CommunityResult.Ok(null)
        val none = vm(); advanceUntilIdle()
        assertThat(none.state.value.none).isTrue()
        coEvery { repo.mine() } returns CommunityResult.Failed("offline")
        val failed = vm(); advanceUntilIdle()
        assertThat(failed.state.value.failed).isTrue()
        coEvery { repo.mine() } returns CommunityResult.Ok(own)
        failed.load(); advanceUntilIdle()
        assertThat(failed.state.value.station).isEqualTo(own)
        assertThat(failed.state.value.failed).isFalse()
    }

    @Test fun `a rename takes the server's station`() = runTest(dispatcher) {
        val renamed = own.copy(name = "Rawn's Attic")
        coEvery { repo.update("Ab3dEf9h", StationEdit(name = "Rawn's Attic")) } returns CommunityResult.Ok(renamed)
        val vm = vm(); advanceUntilIdle()
        vm.rename("  Rawn's Attic "); advanceUntilIdle()
        assertThat(vm.state.value.station).isEqualTo(renamed)
        assertThat(vm.state.value.message).isNull()
    }

    @Test fun `a refused rename keeps the old name and says why`() = runTest(dispatcher) {
        coEvery { repo.update(any(), any()) } returns CommunityResult.Rejected("bad_request")
        val vm = vm(); advanceUntilIdle()
        vm.rename("​​"); advanceUntilIdle()
        assertThat(vm.state.value.station!!.name).isEqualTo("Rawn's Basement")
        assertThat(vm.state.value.message).isEqualTo("That can't be used. Try something else.")
    }

    @Test fun `a blank or unchanged name sends nothing`() = runTest(dispatcher) {
        val vm = vm(); advanceUntilIdle()
        vm.rename("   "); vm.rename("Rawn's Basement"); advanceUntilIdle()
        coVerify(exactly = 0) { repo.update(any(), any()) }
    }

    @Test fun `the vibe line can be changed and cleared`() = runTest(dispatcher) {
        coEvery { repo.update("Ab3dEf9h", StationEdit(vibe = "slow jams")) } returns CommunityResult.Ok(own.copy(vibe = "slow jams"))
        coEvery { repo.update("Ab3dEf9h", StationEdit(vibe = "")) } returns CommunityResult.Ok(own.copy(vibe = null))
        val vm = vm(); advanceUntilIdle()
        vm.setVibe("slow jams"); advanceUntilIdle()
        assertThat(vm.state.value.station!!.vibe).isEqualTo("slow jams")
        // Blank clears it on the server.
        vm.setVibe(" "); advanceUntilIdle()
        assertThat(vm.state.value.station!!.vibe).isNull()
    }

    @Test fun `Delete asks first, then deletes the station and leaves`() = runTest(dispatcher) {
        coEvery { repo.delete("Ab3dEf9h") } returns CommunityResult.Ok(Unit)
        val vm = vm(); advanceUntilIdle()
        vm.askDelete()
        assertThat(vm.state.value.confirmingDelete).isTrue()
        coVerify(exactly = 0) { repo.delete(any()) }
        vm.cancelDelete()
        assertThat(vm.state.value.confirmingDelete).isFalse()
        vm.askDelete(); vm.delete(); advanceUntilIdle()
        coVerify(exactly = 1) { repo.delete("Ab3dEf9h") }
        assertThat(vm.state.value.deleted).isTrue()
    }

    @Test fun `a delete that fails stays on the screen and says why`() = runTest(dispatcher) {
        coEvery { repo.delete(any()) } returns CommunityResult.Failed("offline")
        val vm = vm(); advanceUntilIdle()
        vm.askDelete(); vm.delete(); advanceUntilIdle()
        assertThat(vm.state.value.deleted).isFalse()
        assertThat(vm.state.value.confirmingDelete).isFalse()
        assertThat(vm.state.value.station).isEqualTo(own)
        assertThat(vm.state.value.message).isEqualTo("Couldn't reach Community. Try again.")
    }

    // ── What the screen says about the station ──────────────────────────────────

    private val newYork = ZoneId.of("America/New_York")

    @Test fun `the 7-day note names the day the station would be deleted`() {
        val deleteAt = ZonedDateTime.of(2026, 11, 28, 14, 0, 0, 0, newYork).toInstant().toEpochMilli()
        assertThat(deleteDay(deleteAt, newYork, Locale.UK)).isEqualTo("Sat 28 Nov")
        // In the phone's own zone: the same moment is already Sunday in Tokyo.
        assertThat(deleteDay(deleteAt, ZoneId.of("Asia/Tokyo"), Locale.UK)).isEqualTo("Sun 29 Nov")
        assertThat(deleteDay(null, newYork, Locale.UK)).isNull()
    }

    @Test fun `a hidden or removed station says so, and a healthy one says nothing`() {
        assertThat(stationStanding(own)).isEqualTo(StationStanding.FINE)
        assertThat(stationStanding(own.copy(hidden = true))).isEqualTo(StationStanding.HIDDEN)
        // Taken down wins: it's the one that ends in deletion.
        assertThat(stationStanding(own.copy(hidden = true, removed = true))).isEqualTo(StationStanding.REMOVED)
    }

    @Test fun `the station's link is its page on stashfm`() {
        assertThat(stationLinkText("Ab3dEf9h")).isEqualTo("stashfm.app/s/Ab3dEf9h")
    }
}
