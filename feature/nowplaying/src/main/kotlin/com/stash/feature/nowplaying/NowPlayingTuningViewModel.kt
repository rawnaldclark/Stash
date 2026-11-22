package com.stash.feature.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.audio.SongTunings
import com.stash.core.media.PlayerRepository
import com.stash.core.media.tuning.TuningController
import com.stash.core.model.TuningMath
import com.stash.core.model.tuning.SongTuning
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

/**
 * What Tuning did to the song that's playing, for the line under Tuning's chips (true tuning, spec 2026-11-21
 * §3.3). Pure, so the rule is tested without a screen.
 */
sealed interface SongTuningLine {
    /** Nothing to say: standard pitch, and the song has no reading. */
    data object None : SongTuningLine

    /** At standard, a measured song: what it was recorded at. Nothing is shifted. */
    data class RecordedAt(val songHz: Double) : SongTuningLine

    /** Retuned: the song's own tuning, and how far it was moved to reach the reference ([cents], [lower]). */
    data class Shifted(val songHz: Double, val cents: Double, val lower: Boolean) : SongTuningLine

    /** Retuned, and the song already sits at the reference. */
    data class AlreadyThere(val hz: Float) : SongTuningLine

    /** Retuned, measured, nothing to read: shifted as a standard recording. */
    data object NoPitch : SongTuningLine

    /** Retuned, not measured yet: shifted as a standard recording. */
    data object NotMeasured : SongTuningLine

    companion object {
        /** A shift smaller than this isn't one worth naming: half a cent. */
        private const val SAME_CENTS = 0.5

        fun of(referenceHz: Float, song: SongTuning): SongTuningLine {
            if (!TuningMath.isRetuned(referenceHz)) {
                return if (song is SongTuning.Measured) RecordedAt(song.hz) else None
            }
            return when (song) {
                SongTuning.NotMeasured -> NotMeasured
                SongTuning.NoPitch -> NoPitch
                is SongTuning.Measured -> {
                    val shift = TuningMath.shiftCents(referenceHz, song.cents)
                    if (abs(shift) < SAME_CENTS) AlreadyThere(TuningMath.sanitize(referenceHz))
                    else Shifted(song.hz, abs(shift), lower = shift < 0)
                }
            }
        }
    }
}

/**
 * Tuning's row in the Playback speed sheet (440 · 432 · 444 · Custom), its mark on the speed action, and the line
 * about the song that's playing. The same setting as Settings > Audio & Quality > Tuning; like speed, the sheet is
 * hidden while casting (the speaker decodes, so the phone's output stage isn't in the path).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NowPlayingTuningViewModel @Inject constructor(
    private val tuning: TuningController,
    private val playerRepository: PlayerRepository,
    private val songTunings: SongTunings,
) : ViewModel() {

    /** What playback is retuned to (440 on a phone without the engine). */
    val referenceHz: StateFlow<Float> = tuning.effectiveHz

    /** False without the engine: the sheet leaves the Tuning row out. */
    val available: Boolean = tuning.available

    private val current = playerRepository.playerState
        .map { it.currentTrack }
        .distinctUntilChanged { a, b -> a?.id == b?.id && a?.filePath == b?.filePath }

    /** What Tuning did to the song that's playing. */
    val songLine: StateFlow<SongTuningLine> = current
        .flatMapLatest { track -> if (track == null || track.id <= 0) flowOf(SongTuning.NotMeasured) else songTunings.observe(track.id) }
        .combine(referenceHz) { song, hz -> SongTuningLine.of(hz, song) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SongTuningLine.None)

    /** Whether the playing song has a file on this phone to measure: only then is "Measure again" offered. */
    val canMeasure: StateFlow<Boolean> = current
        .map { it != null && it.id > 0 && !it.filePath.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _measuring = MutableStateFlow(false)

    /** True while "Measure again" is running. */
    val measuring: StateFlow<Boolean> = _measuring.asStateFlow()

    fun set(hz: Float) = tuning.setReferenceHz(TuningMath.sanitize(hz))

    /** Measures the playing song's file again and stores what it reads; playback follows the new reading. */
    fun measureAgain() {
        if (_measuring.value) return
        val id = currentId() ?: return
        _measuring.value = true
        viewModelScope.launch {
            try {
                songTunings.measureAgain(id)
            } finally {
                _measuring.value = false
            }
        }
    }

    private fun currentId(): Long? = playerRepository.playerState.value.currentTrack?.id?.takeIf { it > 0 }
}
