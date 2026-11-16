package com.stash.feature.settings

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.stash.data.download.lossless.tidal.TidalLoginClient
import com.stash.data.download.lossless.tidal.TidalLoginStore
import com.stash.data.download.lossless.tidal.TidalPoll
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings › Lossless sources › "Your Tidal account": device-code sign-in through Stash's relay. */
@HiltViewModel
class TidalLoginViewModel @Inject constructor(
    private val client: TidalLoginClient,
    private val store: TidalLoginStore,
) : ViewModel() {

    sealed interface Step {
        data object Idle : Step
        data object Starting : Step
        data class Waiting(val userCode: String, val url: String) : Step
        data class Failed(val message: String) : Step
    }

    val loggedIn: StateFlow<Boolean> =
        store.hasLogin.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _step = MutableStateFlow<Step>(Step.Idle)
    val step: StateFlow<Step> = _step.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _step.value = Step.Starting
            val code = client.start()
            if (code == null) {
                _step.value = Step.Failed("Couldn't reach Stash's Tidal service. Check your connection and try again.")
                return@launch
            }
            _step.value = Step.Waiting(code.userCode, code.verificationUri)
            val deadline = SystemClock.elapsedRealtime() + code.expiresInSec * 1000L
            var failures = 0
            while (SystemClock.elapsedRealtime() < deadline) {
                delay(code.intervalSec * 1000L)
                when (val r = client.poll(code.deviceCode)) {
                    is TidalPoll.Authorized -> {
                        _step.value = try {
                            store.save(r.tokens)
                            Step.Idle
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Step.Failed("Couldn't save the sign-in on this device. Please try again.")
                        }
                        return@launch
                    }
                    TidalPoll.Pending -> failures = 0
                    TidalPoll.Expired -> {
                        _step.value = Step.Failed("That code expired or was declined. Try again.")
                        return@launch
                    }
                    TidalPoll.Failed -> if (++failures >= MAX_POLL_FAILURES) {
                        _step.value = Step.Failed("Couldn't reach Stash's Tidal service. Try again.")
                        return@launch
                    }
                }
            }
            _step.value = Step.Failed("The code expired. Try again.")
        }
    }

    fun cancel() {
        job?.cancel()
        _step.value = Step.Idle
    }

    fun disconnect() {
        viewModelScope.launch {
            try { store.clear() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* still stored; the card still says connected */ }
        }
    }

    private companion object {
        const val MAX_POLL_FAILURES = 5
    }
}

@Composable
internal fun TidalAccountSection(viewModel: TidalLoginViewModel = hiltViewModel()) {
    val loggedIn by viewModel.loggedIn.collectAsStateWithLifecycle()
    val step by viewModel.step.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_tidal_account_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(2.dp))
        when {
            loggedIn -> {
                Text(
                    text = stringResource(R.string.settings_tidal_account_connected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = viewModel::disconnect) { Text(stringResource(R.string.settings_disconnect)) }
            }
            step is TidalLoginViewModel.Step.Waiting -> {
                val w = step as TidalLoginViewModel.Step.Waiting
                Text(
                    text = stringResource(R.string.settings_tidal_account_waiting, w.userCode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { runCatching { uriHandler.openUri(w.url) } }) {
                        Text(stringResource(R.string.settings_tidal_account_open_link))
                    }
                    TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.settings_cancel)) }
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
            else -> {
                Text(
                    text = stringResource(R.string.settings_tidal_account_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                (step as? TidalLoginViewModel.Step.Failed)?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Button(onClick = viewModel::start, enabled = step !is TidalLoginViewModel.Step.Starting) {
                    if (step is TidalLoginViewModel.Step.Starting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.settings_tidal_account_sign_in))
                    }
                }
            }
        }
    }
}