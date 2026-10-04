package com.stash.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.stash.core.data.lossless.FlacSweepResult
import com.stash.core.data.lossless.FlacUpgradeSweeper
import com.stash.core.data.prefs.AutoFlacUpgradePreference
import com.stash.core.data.sync.workers.FlacUpgradeWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FlacUpgradeUiState(val running: Boolean = false, val message: String? = null)

@HiltViewModel
class FlacUpgradeViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val sweeper: FlacUpgradeSweeper,
    private val autoPref: AutoFlacUpgradePreference,
) : ViewModel() {

    val autoEnabled: StateFlow<Boolean> = autoPref.enabled
    fun setAutoEnabled(enabled: Boolean) = autoPref.set(enabled)

    private val lastResult = MutableStateFlow<FlacSweepResult?>(null)

    val state: StateFlow<FlacUpgradeUiState> = combine(
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(FlacUpgradeWorker.UNIQUE_WORK_NAME),
        lastResult,
    ) { infos, result ->
        val running = infos.any {
            it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
        }
        val message = when (result) {
            null -> null
            FlacSweepResult.StreamingMode -> "Downloads are off (streaming mode), so there's nothing to upgrade."
            FlacSweepResult.LosslessDisabled -> "Turn on lossless in Audio & Quality first."
            FlacSweepResult.AlreadyRunning -> null
            FlacSweepResult.NothingToUpgrade -> "Nothing to upgrade. Every downloaded track is already FLAC or was checked recently."
            is FlacSweepResult.Queued ->
                if (running) null else "Finished checking ${result.count} ${if (result.count == 1) "track" else "tracks"}."
        }
        FlacUpgradeUiState(running = running, message = message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FlacUpgradeUiState())

    fun runNow() {
        viewModelScope.launch {
            lastResult.value = sweeper.enqueue(autoSweep = false)
        }
    }
}