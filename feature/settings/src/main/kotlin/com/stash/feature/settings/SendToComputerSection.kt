package com.stash.feature.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.stash.data.download.files.SendToComputerCoordinator
import com.stash.data.download.files.SendToComputerState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** "Send to computer" in Settings › Library & storage ([SendToComputerCoordinator]). */
@HiltViewModel
class SendToComputerViewModel @Inject constructor(
    private val coordinator: SendToComputerCoordinator,
) : ViewModel() {
    val state = coordinator.state
    suspend fun count(): Pair<Int, Long> = coordinator.count()
    fun start() = coordinator.start()
    fun cancel() = coordinator.cancel()
    fun dismiss() = coordinator.dismiss()
}

/**
 * The Storage card's "Send to computer" row: what a send copies (how many songs, how big), then its progress, then what
 * it did and what to do next (plug the phone into a computer with Stash, choose File transfer). Hidden with no downloads.
 */
@Composable
internal fun SendToComputerSection(viewModel: SendToComputerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var count by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    LaunchedEffect(state is SendToComputerState.Running) {
        if (state !is SendToComputerState.Running) count = runCatching { viewModel.count() }.getOrNull()
    }
    val context = LocalContext.current
    val size = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }
    Column {
        when (val s = state) {
            SendToComputerState.Idle -> {
                val (songs, bytes) = count ?: return@Column
                if (songs == 0) return@Column
                Text(
                    text = stringResource(R.string.settings_send_computer_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = pluralStringResource(R.plurals.settings_send_computer_desc, songs, songs, size(bytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = viewModel::start,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                ) { Text(stringResource(R.string.settings_send_computer_button)) }
            }
            is SendToComputerState.Running -> {
                Text(
                    text = stringResource(R.string.settings_send_computer_progress, s.current, s.total),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { if (s.total == 0) 0f else s.current.toFloat() / s.total },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
            is SendToComputerState.Done -> {
                Text(
                    text = pluralStringResource(R.plurals.settings_send_computer_done, s.sent, s.sent, size(s.bytes)) +
                        (if (s.failed > 0) stringResource(R.string.settings_send_computer_failed, s.failed) else ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_send_computer_next),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = viewModel::dismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_storage_dismiss_button))
                }
            }
            is SendToComputerState.Error -> {
                Text(text = s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = viewModel::dismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_storage_dismiss_button))
                }
            }
        }
    }
}
