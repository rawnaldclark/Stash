package com.stash.feature.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.core.content.ContextCompat
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

/** How the phone is plugged in, as the USB broadcast says: not into a computer, into one with File transfer off, or on. */
private enum class Usb { Unplugged, NoFileTransfer, FileTransfer }

/** The USB state, kept current while shown. `android.hardware.usb.action.USB_STATE` is sticky: the first answer is now. */
@Composable
private fun rememberUsb(): Usb {
    val context = LocalContext.current
    var usb by remember { mutableStateOf(Usb.Unplugged) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent ?: return
                usb = when {
                    !intent.getBooleanExtra("connected", false) -> Usb.Unplugged
                    intent.getBooleanExtra("mtp", false) -> Usb.FileTransfer
                    else -> Usb.NoFileTransfer
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(USB_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )?.let { receiver.onReceive(context, it) }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return usb
}

private const val USB_STATE = "android.hardware.usb.action.USB_STATE"

/**
 * The Storage card's "Send to computer" row, one step at a time: what a send copies and whether the phone is plugged
 * into a computer with File transfer on; the songs being copied out; waiting for Stash on the computer; the computer
 * taking them (counted as they leave the folder); and the end. Hidden with no downloads.
 */
@Composable
internal fun SendToComputerSection(viewModel: SendToComputerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var count by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    LaunchedEffect(state is SendToComputerState.Idle) {
        if (state is SendToComputerState.Idle) count = runCatching { viewModel.count() }.getOrNull()
    }
    val usb = rememberUsb()
    val context = LocalContext.current
    val size = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }

    @Composable
    fun Line(text: String, strong: Boolean = false, error: Boolean = false) = Text(
        text = text,
        style = if (strong) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall,
        color = when {
            error -> MaterialTheme.colorScheme.error
            strong -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )

    @Composable
    fun UsbLine() = Line(
        stringResource(
            when (usb) {
                Usb.Unplugged -> R.string.settings_send_computer_usb_unplugged
                Usb.NoFileTransfer -> R.string.settings_send_computer_usb_no_mtp
                Usb.FileTransfer -> R.string.settings_send_computer_usb_ready
            },
        ),
        strong = usb == Usb.FileTransfer,
    )

    @Composable
    fun Bar(progress: Float?) {
        Spacer(modifier = Modifier.height(8.dp))
        if (progress == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
        } else {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    @Composable
    fun Action(text: String, filled: Boolean, onClick: () -> Unit) {
        Spacer(modifier = Modifier.height(8.dp))
        if (filled) {
            OutlinedButton(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            ) { Text(text) }
        } else {
            TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
        }
    }

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
                Line(pluralStringResource(R.plurals.settings_send_computer_desc, songs, songs, size(bytes)))
                Spacer(modifier = Modifier.height(6.dp))
                UsbLine()
                Action(stringResource(R.string.settings_send_computer_button), filled = true, onClick = viewModel::start)
            }
            is SendToComputerState.Copying -> {
                Line(stringResource(R.string.settings_send_computer_copying, s.current + 1, s.total), strong = true)
                Spacer(modifier = Modifier.height(4.dp))
                Line(stringResource(R.string.settings_send_computer_copying_desc))
                Bar(if (s.total == 0) 0f else s.current.toFloat() / s.total)
                Action(stringResource(R.string.settings_cancel), filled = false, onClick = viewModel::cancel)
            }
            is SendToComputerState.Waiting -> {
                Line(pluralStringResource(R.plurals.settings_send_computer_waiting, s.total, s.total), strong = true)
                Spacer(modifier = Modifier.height(4.dp))
                if (s.notCopied > 0) {
                    Line(pluralStringResource(R.plurals.settings_send_computer_not_copied, s.notCopied, s.notCopied), error = true)
                    Spacer(modifier = Modifier.height(4.dp))
                }
                if (usb == Usb.FileTransfer) {
                    Line(stringResource(R.string.settings_send_computer_waiting_desc))
                } else {
                    UsbLine()
                }
                Bar(null)
                Action(stringResource(R.string.settings_cancel), filled = false, onClick = viewModel::cancel)
            }
            is SendToComputerState.Taking -> {
                Line(stringResource(R.string.settings_send_computer_taking, s.taken, s.total), strong = true)
                Spacer(modifier = Modifier.height(4.dp))
                Line(stringResource(R.string.settings_send_computer_taking_desc))
                Bar(if (s.total == 0) 0f else s.taken.toFloat() / s.total)
            }
            is SendToComputerState.Finished -> {
                if (s.taken >= s.total) {
                    Line(pluralStringResource(R.plurals.settings_send_computer_finished, s.total, s.total), strong = true)
                    Spacer(modifier = Modifier.height(4.dp))
                    Line(stringResource(R.string.settings_send_computer_finished_desc))
                } else {
                    Line(stringResource(R.string.settings_send_computer_partly, s.taken, s.total), strong = true)
                    Spacer(modifier = Modifier.height(4.dp))
                    Line(stringResource(R.string.settings_send_computer_partly_desc))
                }
                Action(stringResource(R.string.settings_storage_dismiss_button), filled = false, onClick = viewModel::dismiss)
            }
            is SendToComputerState.Error -> {
                Line(s.message, error = true)
                Action(stringResource(R.string.settings_storage_dismiss_button), filled = false, onClick = viewModel::dismiss)
            }
        }
    }
}
