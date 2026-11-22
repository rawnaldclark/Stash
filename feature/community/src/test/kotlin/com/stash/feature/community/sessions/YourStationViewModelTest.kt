package com.stash.feature.community.sessions

import com.stash.core.model.community.StationSchedule
import com.stash.core.model.community.ScheduleWindow
import com.stash.core.model.community.AutoLiveMode
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

    // ── Auto-live (step 6) ──────────────────────────────────────────────

    private fun answering() { coEvery { repo.update(any(), any()) } answers { CommunityResult.Ok(own) } }
    private val weekday = ScheduleWindow(1, "09:00", "17:00")

    @Test fun `choosing a schedule sends the mode with its hours and this phone's zone, even with none yet`() = runTest(dispatcher) {
        answering()
        val vm = vm().also { it.zone = { "America/New_York" } }; advanceUntilIdle()
        vm.setAutoLive(AutoLiveMode.SCHEDULE); advanceUntilIdle()
        coVerify(exactly = 1) { repo.update("Ab3dEf9h", StationEdit(autoLive = "schedule", schedule = StationSchedule("America/New_York", emptyList()))) }
    }

    @Test fun `Always and Off send the mode alone, and the mode it already has sends nothing`() = runTest(dispatcher) {
        answering()
        val vm = vm(); advanceUntilIdle()
        vm.setAutoLive(AutoLiveMode.OFF); advanceUntilIdle() // a station starts off
        coVerify(exactly = 0) { repo.update(any(), any()) }
        vm.setAutoLive(AutoLiveMode.ALWAYS); advanceUntilIdle()
        coVerify(exactly = 1) { repo.update("Ab3dEf9h", StationEdit(autoLive = "always")) }
    }

    @Test fun `hours are added in order, with the phone's zone, and removed`() = runTest(dispatcher) {
        val late = ScheduleWindow(5, "22:00", "02:00")
        coEvery { repo.mine() } returns CommunityResult.Ok(own.copy(autoLive = "schedule", schedule = StationSchedule("Europe/London", listOf(late))))
        answering()
        val vm = vm().also { it.zone = { "America/New_York" } }; advanceUntilIdle()
        vm.addHours(weekday); advanceUntilIdle()
        coVerify(exactly = 1) { repo.update("Ab3dEf9h", StationEdit(schedule = StationSchedule("America/New_York", listOf(weekday, late)))) }
    }

    @Test fun `removing hours sends the rest`() = runTest(dispatcher) {
        val late = ScheduleWindow(5, "22:00", "02:00")
        coEvery { repo.mine() } returns CommunityResult.Ok(own.copy(autoLive = "schedule", schedule = StationSchedule("America/New_York", listOf(weekday, late))))
        answering()
        val vm = vm().also { it.zone = { "America/New_York" } }; advanceUntilIdle()
        vm.removeHours(weekday); advanceUntilIdle()
        vm.removeHours(ScheduleWindow(2, "01:00", "02:00")); advanceUntilIdle() // not one of its windows
        coVerify(exactly = 1) { repo.update("Ab3dEf9h", StationEdit(schedule = StationSchedule("America/New_York", listOf(late)))) }
        coVerify(exactly = 1) { repo.update(any(), any()) }
    }

    @Test fun `malformed hours, the same hours twice and an eighth window send nothing`() = runTest(dispatcher) {
        val seven = (1..7).map { ScheduleWindow(it, "09:00", "17:00") }
        coEvery { repo.mine() } returns CommunityResult.Ok(own.copy(autoLive = "schedule", schedule = StationSchedule("America/New_York", seven)))
        answering()
        val full = vm(); advanceUntilIdle()
        full.addHours(ScheduleWindow(1, "18:00", "20:00")); advanceUntilIdle()

        coEvery { repo.mine() } returns CommunityResult.Ok(own.copy(autoLive = "schedule", schedule = StationSchedule("America/New_York", listOf(weekday))))
        val one = vm(); advanceUntilIdle()
        one.addHours(weekday); advanceUntilIdle()
        one.addHours(ScheduleWindow(2, "09:00", "09:00")); advanceUntilIdle()
        one.addHours(ScheduleWindow(2, "9am", "17:00")); advanceUntilIdle()
        coVerify(exactly = 0) { repo.update(any(), any()) }
    }

    @Test fun `a time is two digits, a colon and two digits`() {
        assertThat(timeOrNull("09:05")).isEqualTo("09:05")
        assertThat(timeOrNull(" 23:59 ")).isEqualTo("23:59")
        for (bad in listOf("9:05", "09:5", "24:00", "12:60", "0905", "", "noon")) assertThat(timeOrNull(bad)).isNull()
    }

    @Test fun `hours read as the day and the two times`() {
        assertThat(hoursLine(weekday, java.util.Locale.US)).isEqualTo("Mon 09:00 – 17:00")
        assertThat(dayName(7, java.util.Locale.US)).isEqualTo("Sun")
    }
}
