package com.stash.feature.nowplaying

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.audio.SongTunings
import com.stash.core.media.PlayerRepository
import com.stash.core.media.tuning.TuningController
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import com.stash.core.model.tuning.SongTuning
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The line about the playing song, and "Measure again". */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingTuningViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val reference = MutableStateFlow(432f)
    private val state = MutableStateFlow(PlayerState())
    private val tunings = mutableMapOf<Long, MutableStateFlow<SongTuning>>()

    private val tuning: TuningController = mockk(relaxed = true) {
        every { effectiveHz } returns reference
        every { available } returns true
    }
    private val player: PlayerRepository = mockk(relaxed = true) { every { playerState } returns state }
    private val songTunings: SongTunings = mockk {
        every { observe(any()) } answers { tunings.getOrPut(firstArg()) { MutableStateFlow(SongTuning.NotMeasured) } }
    }

    private fun song(id: Long, file: String? = "/music/$id.flac") = Track(id = id, title = "Song $id", artist = "Artist", filePath = file)

    private fun vm() = NowPlayingTuningViewModel(tuning, player, songTunings)

    // ── the rule ────────────────────────────────────────────────────────

    @Test fun `retuned, a measured song says where it was and how far it moved`() {
        val line = SongTuningLine.of(432f, SongTuning.Measured(7.85f)) as SongTuningLine.Shifted
        assertThat(line.songHz).isWithin(0.01).of(442.0)
        assertThat(line.cents).isWithin(0.01).of(39.62)
        assertThat(line.lower).isTrue()
    }

    @Test fun `a song under the reference moves up`() {
        val line = SongTuningLine.of(432f, SongTuning.Measured(-45.2f)) as SongTuningLine.Shifted
        assertThat(line.lower).isFalse()
        assertThat(line.cents).isWithin(0.01).of(13.43)
    }

    @Test fun `a song within half a cent of the reference is already there`() {
        assertThat(SongTuningLine.of(432f, SongTuning.Measured(-31.5f))).isEqualTo(SongTuningLine.AlreadyThere(432f))
        assertThat(SongTuningLine.of(432f, SongTuning.Measured(-31.0f))).isInstanceOf(SongTuningLine.Shifted::class.java)
    }

    @Test fun `retuned, a song with no reading says it was shifted as a standard recording`() {
        assertThat(SongTuningLine.of(432f, SongTuning.NoPitch)).isEqualTo(SongTuningLine.NoPitch)
        assertThat(SongTuningLine.of(432f, SongTuning.NotMeasured)).isEqualTo(SongTuningLine.NotMeasured)
    }

    @Test fun `at standard only a measured song has a line, and nothing is shifted`() {
        val line = SongTuningLine.of(440f, SongTuning.Measured(21.7f)) as SongTuningLine.RecordedAt
        assertThat(line.songHz).isWithin(0.01).of(445.55)
        assertThat(SongTuningLine.of(440f, SongTuning.NoPitch)).isEqualTo(SongTuningLine.None)
        assertThat(SongTuningLine.of(440f, SongTuning.NotMeasured)).isEqualTo(SongTuningLine.None)
    }

    // ── the view model ──────────────────────────────────────────────────

    @Test fun `the line follows the playing song, its reading and the reference`() = runTest(UnconfinedTestDispatcher()) {
        val vm = vm()
        backgroundScope.launch { vm.songLine.collect() }
        assertThat(vm.songLine.value).isEqualTo(SongTuningLine.NotMeasured) // nothing playing

        state.value = PlayerState(currentTrack = song(1))
        assertThat(vm.songLine.value).isEqualTo(SongTuningLine.NotMeasured)

        tunings.getValue(1L).value = SongTuning.Measured(7.85f) // the reading lands while it plays
        assertThat(vm.songLine.value).isInstanceOf(SongTuningLine.Shifted::class.java)

        reference.value = 440f
        assertThat(vm.songLine.value).isInstanceOf(SongTuningLine.RecordedAt::class.java)

        state.value = PlayerState(currentTrack = song(2))
        assertThat(vm.songLine.value).isEqualTo(SongTuningLine.None)
    }

    @Test fun `measure again is offered only for a song with a file on this phone`() = runTest(UnconfinedTestDispatcher()) {
        val vm = vm()
        backgroundScope.launch { vm.canMeasure.collect() }
        assertThat(vm.canMeasure.value).isFalse()

        state.value = PlayerState(currentTrack = song(1))
        assertThat(vm.canMeasure.value).isTrue()

        state.value = PlayerState(currentTrack = song(2, file = null)) // a stream
        assertThat(vm.canMeasure.value).isFalse()
    }

    @Test fun `measure again measures the playing song, once at a time`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Boolean>()
        coEvery { songTunings.measureAgain(1L) } coAnswers { gate.await() }
        state.value = PlayerState(currentTrack = song(1))
        val vm = vm()

        vm.measureAgain()
        vm.measureAgain() // a second tap while it runs
        assertThat(vm.measuring.value).isTrue()

        gate.complete(true)
        assertThat(vm.measuring.value).isFalse()
        coVerify(exactly = 1) { songTunings.measureAgain(1L) }
    }

    @Test fun `with nothing playing, measure again does nothing`() = runTest(UnconfinedTestDispatcher()) {
        val vm = vm()
        vm.measureAgain()
        assertThat(vm.measuring.value).isFalse()
        coVerify(exactly = 0) { songTunings.measureAgain(any()) }
    }
}
