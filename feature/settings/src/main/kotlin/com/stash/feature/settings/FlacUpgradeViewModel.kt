package com.stash.feature.settings

import android.content.Context
import android.content.res.Resources
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.stash.core.data.lossless.FlacSweepResult
import com.stash.core.data.lossless.FlacUpgradeSweeper
import com.stash.core.data.prefs.AutoFlacUpgradePreference
import com.stash.core.data.sync.workers.FlacUpgradeWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Library Health's "FLAC upgrades" card.
 *
 * @property active a batch is queued or running: the card shows progress, not the button.
 * @property message the headline: what is happening, or how the last run ended.
 * @property detail a smaller second line, when there is more to say.
 */
data class FlacUpgradeUiState(
    val active: Boolean = false,
    val message: String? = null,
    val detail: String? = null,
)

/**
 * Drives the "Auto-upgrade to FLAC after sync" toggle (Audio & Quality) and the
 * "FLAC upgrades" card (Library Health). The card reads [FlacUpgradeWorker]'s unique
 * work, so a run started here, after a sync, or from the Library's "Upgrade to FLAC"
 * shows its progress and result even after the screen was left and reopened.
 */
@HiltViewModel
class FlacUpgradeViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val sweeper: FlacUpgradeSweeper,
    private val autoPref: AutoFlacUpgradePreference,
) : ViewModel() {

    val autoEnabled: StateFlow<Boolean> = autoPref.enabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setAutoEnabled(enabled: Boolean) {
        viewModelScope.launch { autoPref.setEnabled(enabled) }
    }

    /** What the last tap answered when it queued nothing (Online, Lossless off, ...). */
    private val tapResult = MutableStateFlow<FlacSweepResult?>(null)

    val state: StateFlow<FlacUpgradeUiState> = combine(
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(FlacUpgradeWorker.UNIQUE_WORK_NAME)
            // A batch started since (a sync, the Library) is newer news than the old tap.
            .onEach { infos -> if (infos.any { !it.state.isFinished }) tapResult.value = null },
        tapResult,
    ) { infos, tap -> flacUpgradeUiState(infos, tap, context.resources) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FlacUpgradeUiState())

    fun runNow() {
        viewModelScope.launch {
            tapResult.value = try {
                sweeper.runNow()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, "Check for upgrades failed to start", e)
                null
            }
        }
    }

    private companion object {
        const val TAG = "FlacUpgradeVM"
    }
}

/**
 * The card's state from the batch worker's unique work and the last tap's answer.
 * A live batch wins; then a tap that queued nothing; then how the last batch ended.
 */
internal fun flacUpgradeUiState(infos: List<WorkInfo>, tap: FlacSweepResult?, res: Resources): FlacUpgradeUiState {
    infos.firstOrNull { !it.state.isFinished }?.let { live ->
        return FlacUpgradeUiState(
            active = true,
            message = if (live.state == WorkInfo.State.RUNNING) {
                res.getString(R.string.settings_flac_upgrade_checking)
            } else {
                // Just queued, waiting for a connection, or paced by the lossless service.
                res.getString(R.string.settings_flac_upgrade_waiting)
            },
        )
    }
    tapMessage(tap, res)?.let { return FlacUpgradeUiState(message = it) }
    val last = infos.lastOrNull { it.state != WorkInfo.State.CANCELLED }
        ?: infos.lastOrNull()
        ?: return FlacUpgradeUiState()
    return if (last.state == WorkInfo.State.SUCCEEDED) {
        finishedState(last.outputData, res)
    } else {
        FlacUpgradeUiState(message = res.getString(R.string.settings_flac_upgrade_stopped))
    }
}

private fun tapMessage(tap: FlacSweepResult?, res: Resources): String? = when (tap) {
    FlacSweepResult.StreamingMode -> res.getString(R.string.settings_flac_upgrade_streaming_mode)
    FlacSweepResult.LosslessDisabled -> res.getString(R.string.settings_flac_upgrade_lossless_disabled)
    FlacSweepResult.NothingToUpgrade ->
        res.getString(R.string.settings_flac_upgrade_nothing)
    // Queued / already running: the batch itself has the news. AutoUpgradeOff is post-sync only.
    FlacSweepResult.AutoUpgradeOff, FlacSweepResult.AlreadyRunning, is FlacSweepResult.Queued, null -> null
}

private fun finishedState(output: Data, res: Resources): FlacUpgradeUiState {
    val upgraded = output.getInt(FlacUpgradeWorker.KEY_UPGRADED, 0)
    val noMatch = output.getInt(FlacUpgradeWorker.KEY_NO_MATCH, 0)
    val failed = output.getInt(FlacUpgradeWorker.KEY_FAILED, 0)
    val detail = listOfNotNull(
        if (noMatch > 0) res.getString(R.string.settings_flac_upgrade_detail_no_match, tracks(noMatch, res)) else null,
        if (failed > 0) res.getString(R.string.settings_flac_upgrade_detail_failed, tracks(failed, res)) else null,
    ).joinToString("\n").ifEmpty { null }
    return FlacUpgradeUiState(
        message = if (upgraded > 0) res.getString(R.string.settings_flac_upgrade_done_upgraded, tracks(upgraded, res)) else res.getString(R.string.settings_flac_upgrade_done_none),
        detail = detail,
    )
}

private fun tracks(n: Int, res: Resources) = res.getQuantityString(R.plurals.settings_flac_upgrade_tracks, n, n)
