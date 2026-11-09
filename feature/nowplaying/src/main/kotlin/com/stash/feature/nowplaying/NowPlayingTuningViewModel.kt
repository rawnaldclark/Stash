package com.stash.feature.nowplaying

import androidx.lifecycle.ViewModel
import com.stash.core.media.tuning.TuningController
import com.stash.core.model.TuningMath
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Tuning's row in the Playback speed sheet (440 · 432 · 444 · Custom) and its mark on the speed
 * action. The same setting as Settings > Audio & Quality > Tuning; like speed, the sheet is hidden
 * while casting (the speaker decodes, so the phone's output stage isn't in the path).
 */
@HiltViewModel
class NowPlayingTuningViewModel @Inject constructor(
    private val tuning: TuningController,
) : ViewModel() {

    /** What playback is retuned to (440 on a phone without the engine). */
    val referenceHz: StateFlow<Float> = tuning.effectiveHz

    /** False without the engine: the sheet leaves the Tuning row out. */
    val available: Boolean = tuning.available

    fun set(hz: Float) = tuning.setReferenceHz(TuningMath.sanitize(hz))
}
