package com.stash.feature.settings.update

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.update.AppUpdater
import com.stash.core.data.update.AvailableUpdate
import com.stash.core.data.update.UpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The update sheet over the whole app: up when a newer release is found and Later wasn't tapped in the last day, or when
 * the listener asks (the notification, Settings).
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(private val updater: AppUpdater) : ViewModel() {
    val available: StateFlow<AvailableUpdate?> = updater.available
    val state: StateFlow<UpdateState> = updater.state

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    init {
        viewModelScope.launch {
            updater.available.collect { u -> if (u != null && updater.shouldPrompt(u)) _visible.value = true }
        }
        viewModelScope.launch { updater.asks.drop(1).collect { _visible.value = updater.available.value != null } }
    }

    fun update() = updater.update()

    /** Open the sheet now (the update notification, Settings › About). */
    fun ask() = updater.ask()

    /** Later, or the sheet swiped away before Update: ask again tomorrow. Once updating, closing it just hides it. */
    fun dismiss() {
        when (updater.state.value) {
            UpdateState.Idle -> updater.later()
            is UpdateState.Failed -> {
                updater.later()
                updater.reset()
            }
            else -> Unit
        }
        _visible.value = false
    }

    /**
     * The app came back to the front: maybe from Android's "Install unknown apps" screen, or a day after Later with the process
     * still alive (the stored release then emits nothing new, so the deadline is checked here).
     */
    fun onResume() {
        updater.onReturnFromPermission()
        val u = updater.available.value
        if (!_visible.value && u != null && updater.shouldPrompt(u)) _visible.value = true
    }

    fun permissionIntent(): Intent = updater.permissionIntent()

    fun retry() {
        updater.reset()
        updater.update()
    }
}
