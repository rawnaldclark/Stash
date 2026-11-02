package com.stash.feature.settings.update

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.update.AppUpdater
import com.stash.core.data.update.AvailableUpdate
import com.stash.core.data.update.ReleaseNotes
import com.stash.core.data.update.UpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The update sheet over the whole app: up by itself when a newer release is found that wasn't skipped and Later wasn't
 * tapped in the last day, or when the listener asks (the notification, Settings). Once Update now is tapped it never
 * comes up by itself again for that try, except when the update needs the listener: Android's one-time permission, or
 * a failure, or an install waiting for a confirm Android wouldn't show in the background. Hide means hidden, through a
 * return to the app or edited notes (review on #814).
 *
 * [parseNotes] is [ReleaseNotes.parse]; a test passes its own to see which thread it runs on.
 */
@HiltViewModel
class UpdateViewModel internal constructor(
    private val updater: AppUpdater,
    private val parseNotes: (String) -> ReleaseNotes,
) : ViewModel() {

    @Inject constructor(updater: AppUpdater) : this(updater, ReleaseNotes::parse)

    val available: StateFlow<AvailableUpdate?> = updater.available
    val state: StateFlow<UpdateState> = updater.state

    /**
     * The available release's notes, read for the sheet off the main thread (a parse, bounded, but not one for a frame).
     * Null until the first read is done; the sheet waits for it.
     */
    val notes: StateFlow<ReleaseNotes?> = updater.available
        .map { it?.notes }
        .distinctUntilChanged()
        .map { raw -> raw?.let(parseNotes) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** "0.9.122", for "You have 0.9.122". */
    val installedVersion: String? get() = updater.installedVersion

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    init {
        viewModelScope.launch {
            updater.available.collect { u -> if (u != null && opensByItself(u)) _visible.value = true }
        }
        viewModelScope.launch { updater.asks.drop(1).collect { _visible.value = updater.available.value != null } }
        viewModelScope.launch {
            updater.state.collect { s ->
                val needsListener = s == UpdateState.NeedsPermission || s == UpdateState.ReadyToInstall || s is UpdateState.Failed
                if (needsListener && updater.available.value != null) _visible.value = true
            }
        }
    }

    /**
     * Whether the sheet comes up by itself for [u]: only before Update now (Idle), and only when [AppUpdater.shouldPrompt]
     * says so. Mid-download or installing, the sheet is up because the listener opened it, or hidden because they hid it.
     */
    private fun opensByItself(u: AvailableUpdate): Boolean = updater.state.value == UpdateState.Idle && updater.shouldPrompt(u)

    fun update() = updater.update()

    /** Install now: the update downloaded while Stash was in the background goes to Android's confirm. */
    fun installNow() = updater.installNow()

    /** Open the sheet now (the update notification, Settings › About). */
    fun ask() = updater.ask()

    /**
     * Later, or the sheet swiped away: before Update now, after a failure, or at Android's permission step, ask again
     * tomorrow and start over then (a downloaded APK is kept for it). Mid-download, installing or ready to install, closing
 * it just hides it (the notification, or Settings › Check for updates, brings it back).
     */
    fun dismiss() {
        when (updater.state.value) {
            UpdateState.Idle -> updater.later()
            is UpdateState.Failed, UpdateState.NeedsPermission -> {
                updater.later()
                updater.reset()
            }
            else -> Unit
        }
        _visible.value = false
    }

    /** Skip this version: never asked again for it by itself; the next version asks as usual. */
    fun skip() {
        updater.skip()
        _visible.value = false
    }

    /** Hide the sheet while the update downloads or installs: it carries on, and the sheet comes back if it needs the listener. */
    fun hide() {
        _visible.value = false
    }

    /**
     * The app came back to the front: maybe from Android's "Install unknown apps" screen, or a day after Later with the process
     * still alive (the stored release then emits nothing new, so the deadline is checked here).
     */
    fun onResume() {
        updater.onReturnFromPermission()
        val u = updater.available.value
        if (!_visible.value && u != null && opensByItself(u)) _visible.value = true
    }

    fun permissionIntent(): Intent = updater.permissionIntent()

    fun retry() {
        updater.reset()
        updater.update()
    }
}

/**
 * Settings › About's way to the update sheet after its check: only [ask]. The sheet itself (its notes, its collectors)
 * lives in the scaffold's [UpdateViewModel]; a second one here parsed and collected for nothing (review on #814).
 */
@HiltViewModel
class UpdateAskViewModel @Inject constructor(private val updater: AppUpdater) : ViewModel() {
    /** Open the update sheet when the check found and stored a release. False when there is none to show. */
    fun ask(): Boolean {
        if (updater.available.value == null) return false
        updater.ask()
        return true
    }
}
