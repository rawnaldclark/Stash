package com.stash.feature.settings.weblink

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.weblink.PairingSessionFactory
import com.stash.core.data.weblink.PairingState
import com.stash.core.data.weblink.WebLinkRepository
import com.stash.core.data.weblink.WebLinkResult
import com.stash.core.data.weblink.WebLinkStatus
import com.stash.core.data.weblink.handoff.HandoffPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Settings › Library & Storage › Link Stash on the web (spec §2.2, §2.6): the device list and its actions, the scanner, and
 * one pairing at a time. A pairing link opened from outside (the camera app, `adb am start`) arrives as the route's `link`
 * argument and goes straight to the confirm sheet.
 */
@HiltViewModel
class WebLinkViewModel @Inject constructor(
    private val repo: WebLinkRepository,
    pairingFactory: PairingSessionFactory,
    private val savedState: SavedStateHandle,
    private val handoffPrefs: HandoffPrefs,
) : ViewModel() {
    private val session = pairingFactory.create()

    val status: StateFlow<WebLinkStatus> = repo.status
    val pairing: StateFlow<PairingState> = session.state

    /** "Pick up where you left off" (spec §2.5): on by default once linked. */
    val handoffEnabled: StateFlow<Boolean> = handoffPrefs.enabled

    fun setHandoffEnabled(on: Boolean) = handoffPrefs.setEnabled(on)

    /** "Unlinked from Chrome on Windows." (once), or the result of an action that failed. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** The full-screen scanner is open. */
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** An action on the list is running (its buttons wait). */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var pairingJob: Job? = null

    init {
        viewModelScope.launch {
            repo.notice.collect { n ->
                if (n != null) {
                    _message.value = n
                    repo.noticeShown()
                }
            }
        }
        // The list is read when the screen resumes (SettingsWebLinkScreen), so not here too.
        // A link handed in by the route is opened once, not again after a configuration change.
        val link = savedState.get<String>(ARG_LINK)
        if (link != null && savedState.get<Boolean>(KEY_LINK_OPENED) != true) {
            savedState[KEY_LINK_OPENED] = true
            openLink(link, fromLink = true)
        }
    }

    fun refresh() {
        viewModelScope.launch { repo.refresh() }
    }

    fun startScan() {
        if (pairingJob?.isActive == true) return
        session.reset()
        _scanning.value = true
    }

    fun stopScan() {
        _scanning.value = false
    }

    /**
     * A scanned or opened link: read the code and show the confirm sheet. [fromLink]: it came from outside the app's scanner
     * (an App Link), so the sheet adds its warning.
     */
    fun openLink(text: String, fromLink: Boolean = false) {
        _scanning.value = false
        pairingJob?.cancel()
        pairingJob = viewModelScope.launch { session.open(text, fromLink) }
    }

    /**
     * "Link" on the confirm sheet: answer, wait for the computer, then join or create. One answer per code: a second tap while
     * it runs does nothing (it used to cancel the first mid-answer).
     */
    fun confirm() {
        if (pairing.value !is PairingState.Confirm || pairingJob?.isActive == true) return
        pairingJob = viewModelScope.launch { session.confirm() }
    }

    /** Cancel, Done or OK: the pairing panel closes; a pairing still waiting is abandoned (nothing was joined). */
    fun closePairing() {
        pairingJob?.cancel()
        pairingJob = null
        session.reset()
        refresh()
    }

    fun rename(deviceId: String, name: String) = act { repo.rename(deviceId, name) }

    fun remove(deviceId: String) = act { repo.remove(deviceId) }

    fun unlinkEverything() = act { repo.unlinkEverything() }

    fun messageShown() {
        _message.value = null
    }

    private fun act(block: suspend () -> WebLinkResult) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                when (val r = block()) {
                    is WebLinkResult.Failed -> _message.value = r.message
                    is WebLinkResult.Unlinked -> Unit // the notice arrives through repo.notice
                    WebLinkResult.Ok -> Unit
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = com.stash.core.data.weblink.WebLinkCopy.STORE_BUSY
            } finally {
                _busy.value = false
            }
        }
    }

    override fun onCleared() {
        pairingJob?.cancel()
    }

    companion object {
        /** The route's argument holding a pairing link (`WebLinkRoute.link`). */
        const val ARG_LINK = "link"
        private const val KEY_LINK_OPENED = "weblink.linkOpened"
    }
}
