package com.stash.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.prefs.HomeMixRotationPreference
import com.stash.core.data.prefs.MixRotation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Settings > Appearance > Home layout > Mixes: how often the mix rails reshuffle. */
@HiltViewModel
class HomeMixRotationViewModel @Inject constructor(
    private val preference: HomeMixRotationPreference,
) : ViewModel() {

    val rotation: StateFlow<MixRotation> =
        preference.rotation.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MixRotation.DAILY)

    val unplayedFirst: StateFlow<Boolean> =
        preference.unplayedFirst.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setRotation(value: MixRotation) {
        viewModelScope.launch { preference.setRotation(value) }
    }

    fun setUnplayedFirst(value: Boolean) {
        viewModelScope.launch { preference.setUnplayedFirst(value) }
    }
}
