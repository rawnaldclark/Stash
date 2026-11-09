package com.stash.feature.settings

import androidx.lifecycle.ViewModel
import com.stash.core.media.tuning.TuningController
import com.stash.core.model.TuningMath
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Drives Audio & Quality > Output > Tuning: the reference everything plays at, picked from presets,
 * a slider or an exact number of hertz. Its own small ViewModel, like [HiResOutputViewModel]: it
 * reads playback-side state nothing else on the screen needs.
 */
@HiltViewModel
class TuningSettingsViewModel @Inject constructor(
    private val tuning: TuningController,
) : ViewModel() {

    /** The chosen reference (A4, Hz). */
    val referenceHz: StateFlow<Float> = tuning.referenceHz

    /** False when this phone has no tuning engine: the row shows as unavailable. */
    val available: Boolean = tuning.available

    fun setReferenceHz(hz: Float) = tuning.setReferenceHz(TuningMath.sanitize(hz))

    /**
     * The exact-Hz field's Set: stores what [text] asks for, clamped to 392 to 494 Hz. Returns the
     * stored value, or null (and stores nothing) when [text] isn't a number.
     */
    fun setExact(text: String): Float? =
        (TuningMath.parse(text) as? TuningMath.Entry.Value)?.hz?.also { setReferenceHz(it) }
}
