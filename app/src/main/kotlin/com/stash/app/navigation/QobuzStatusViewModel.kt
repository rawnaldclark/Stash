package com.stash.app.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.discovery.HomeDiscoveryRepository
import com.stash.core.data.discovery.QobuzDiscoveryStatus
import com.stash.core.data.prefs.HomeDiscoveryPreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class QobuzStatusViewModel @Inject constructor(
    homeDiscoveryRepository: HomeDiscoveryRepository,
    homeDiscoveryPreference: HomeDiscoveryPreference,
) : ViewModel() {
    val bannerStatus: StateFlow<QobuzDiscoveryStatus> = combine(
        homeDiscoveryRepository.status,
        homeDiscoveryPreference.enabled,
    ) { status, enabled ->
        if (enabled) status else QobuzDiscoveryStatus.OK
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QobuzDiscoveryStatus.OK)
}