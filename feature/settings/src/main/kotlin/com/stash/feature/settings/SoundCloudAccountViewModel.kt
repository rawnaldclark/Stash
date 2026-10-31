package com.stash.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SoundCloudConnectionUi(val open: Boolean = false, val web: Boolean = false,
    val busy: Boolean = false, val failed: Boolean = false)

@HiltViewModel
class SoundCloudAccountViewModel @Inject constructor(private val tokens: TokenManager, private val repository: com.stash.core.data.repository.MusicRepository) : ViewModel() {
    val session = tokens.soundCloudSession.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    private val mutable = MutableStateFlow(SoundCloudConnectionUi())
    val ui = mutable.asStateFlow()
    private var connectJob: kotlinx.coroutines.Job? = null
    fun open() { mutable.value = mutable.value.copy(open = true, failed = false) }
    fun close() { connectJob?.cancel(); mutable.value = SoundCloudConnectionUi() }
    fun web() { mutable.value = mutable.value.copy(web = true) }
    fun closeWeb() { mutable.value = mutable.value.copy(web = false) }
    fun connectToken(token: String) = connect { tokens.connectSoundCloudWithToken(token) }
    fun connectProfile(url: String) = connect { tokens.connectSoundCloudPublicProfile(url) }
    private fun connect(action: suspend () -> Boolean) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, web = false, failed = false)
        connectJob = viewModelScope.launch {
            try {
                val ok = kotlinx.coroutines.withContext(io) {
                    kotlinx.coroutines.withTimeoutOrNull(30_000) { action() } ?: false
                }
                mutable.value = if (ok) SoundCloudConnectionUi() else mutable.value.copy(busy = false, failed = true)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = mutable.value.copy(busy = false, failed = true) }
        }
    }
    fun disconnect() {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true)
        viewModelScope.launch {
            try {
                tokens.clearAuth(AuthService.SOUNDCLOUD)
                repository.cancelPendingDownloadsForSource("SOUNDCLOUD")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = mutable.value.copy(open = true, failed = true) }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }

    companion object {
        /**
         * Where the connect check runs. A test points it at its Main test dispatcher, so a
         * cancelled check unwinds inside the test instead of resuming onto Main after the test
         * has reset it (an uncaught exception in whichever test class ran next).
         */
        internal var io: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO
    }
}
