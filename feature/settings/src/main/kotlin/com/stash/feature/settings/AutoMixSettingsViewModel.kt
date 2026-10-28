package com.stash.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.prefs.AutoMixMode
import com.stash.core.data.prefs.AutoMixPreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AutoMixSettingsViewModel @Inject constructor(
    private val preference: AutoMixPreference,
) : ViewModel() {
    val enabled: StateFlow<Boolean> = preference.enabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val mode: StateFlow<AutoMixMode> = preference.mode.stateIn(viewModelScope, SharingStarted.Eagerly, AutoMixMode.BEAT_SYNC)

    fun setEnabled(value: Boolean) { viewModelScope.launch { preference.setEnabled(value) } }
    fun setMode(value: AutoMixMode) { viewModelScope.launch { preference.setMode(value) } }
}
