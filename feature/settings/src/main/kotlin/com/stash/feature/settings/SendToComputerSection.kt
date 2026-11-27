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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.stash.data.download.files.SendCount
import com.stash.data.download.files.SendToComputerCoordinator
import com.stash.data.download.files.SendToComputerState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** "Send to computer" in Settings › Library & storage ([SendToComputerCoordinator]). */
@HiltViewModel
class SendToComputerViewModel @Inject constructor(
    private val coordinator: SendToComputerCoordinator,
) : ViewModel() {
    val state = coordinator.state

    init {
        // A send the computer has not finished taking is shown where it is.
        coordinator.resume()
    }

    suspend fun count(): SendCount = coordinator.count()
    suspend fun forget() = coordinator.forget()
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
 * The Storage card's "Send to computer" row, one step at a time: what a send copies (leaving out the songs the computer
 * has, with a way to send those too; your likes, playlists and plays always go, alone when the computer has every song)
 * and whether the phone is plugged into a computer with File transfer on; the songs being copied out; waiting for Stash
 * on the computer; the computer taking them (counted as they leave the folder); and the end. Hidden with no downloads.
 */
@Composable
internal fun SendToComputerSection(viewModel: SendToComputerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var count by remember { mutableStateOf<SendCount?>(null) }
    LaunchedEffect(state is SendToComputerState.Idle) {
        if (state is SendToComputerState.Idle) count = runCatching { viewModel.count() }.getOrNull()
    }
    val scope = rememberCoroutineScope()
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
                val c = count ?: return@Column
                if (c.songs == 0 && c.onComputer == 0) return@Column
                Text(
                    text = stringResource(R.string.settings_send_computer_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (c.songs == 0) {
                    // The computer has every song: a send carries the latest likes, playlists and plays alone, unless asked
                    // to send the songs again too.
                    Line(stringResource(R.string.settings_send_computer_library_only))
                    Spacer(modifier = Modifier.height(6.dp))
                    UsbLine()
                    Action(stringResource(R.string.settings_send_computer_button), filled = true, onClick = viewModel::start)
                    Action(stringResource(R.string.settings_send_computer_send_again), filled = false) {
                        scope.launch {
                            viewModel.forget()
                            viewModel.start()
                        }
                    }
                } else {
                    val desc = if (c.onComputer > 0) R.plurals.settings_send_computer_desc_new else R.plurals.settings_send_computer_desc
                    Line(pluralStringResource(desc, c.songs, c.songs, size(c.bytes)))
                    Spacer(modifier = Modifier.height(4.dp))
                    Line(stringResource(R.string.settings_send_computer_library_too))
                    if (c.onComputer > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Line(pluralStringResource(R.plurals.settings_send_computer_there, c.onComputer, c.onComputer))
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    UsbLine()
                    Action(stringResource(R.string.settings_send_computer_button), filled = true, onClick = viewModel::start)
                    if (c.onComputer > 0) {
                        Action(stringResource(R.string.settings_send_computer_copy_too), filled = false) {
                            scope.launch {
                                viewModel.forget()
                                count = runCatching { viewModel.count() }.getOrNull()
                            }
                        }
                    }
                }
            }
            SendToComputerState.Preparing -> {
                Line(stringResource(R.string.settings_send_computer_preparing), strong = true)
                Spacer(modifier = Modifier.height(4.dp))
                Line(stringResource(R.string.settings_send_computer_copying_desc))
                Bar(0f)
                Action(stringResource(R.string.settings_cancel), filled = false, onClick = viewModel::cancel)
            }
            SendToComputerState.Cancelling -> {
                Line(stringResource(R.string.settings_send_computer_cancelling), strong = true)
                Spacer(modifier = Modifier.height(4.dp))
                Line(stringResource(R.string.settings_send_computer_cancelling_desc))
            }
            is SendToComputerState.Copying -> {
                Line(stringResource(R.string.settings_send_computer_copying, s.current + 1, s.total), strong = true)
                Spacer(modifier = Modifier.height(4.dp))
                Line(stringResource(R.string.settings_send_computer_copying_keeps))
                Bar(if (s.total == 0) 0f else s.current.toFloat() / s.total)
                Action(stringResource(R.string.settings_cancel), filled = false, onClick = viewModel::cancel)
            }
            is SendToComputerState.Waiting -> {
                if (s.libraryOnly) {
                    // Your likes, playlists and plays alone: no songs to choose on the computer, only to bring in.
                    Line(stringResource(R.string.settings_send_computer_waiting_library), strong = true)
                    if (usb != Usb.FileTransfer) {
                        Spacer(modifier = Modifier.height(4.dp))
                        UsbLine()
                    }
                } else {
                    Line(pluralStringResource(R.plurals.settings_send_computer_waiting, s.total, s.total), strong = true)
                    Spacer(modifier = Modifier.height(4.dp))
                    val short = s.rest.noRoom
                    if (short != null) {
                        // The phone filled up partway: these go first, the rest once the computer has them (which frees
                        // their room), and once more is freed if even that is not enough.
                        val full = if (short > 0L) {
                            pluralStringResource(R.plurals.settings_send_computer_full, s.total, s.total, size(short))
                        } else {
                            pluralStringResource(R.plurals.settings_send_computer_full_room, s.total, s.total)
                        }
                        Line(full, error = true)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    if (s.rest.failed > 0) {
                        Line(pluralStringResource(R.plurals.settings_send_computer_not_copied, s.rest.failed, s.rest.failed), error = true)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    if (s.libraryLeftOut) {
                        // The row said they go with the songs: they didn't this time.
                        Line(stringResource(R.string.settings_send_computer_library_left_out))
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    if (usb == Usb.FileTransfer) {
                        Line(stringResource(R.string.settings_send_computer_waiting_desc))
                    } else {
                        UsbLine()
                    }
                }
                Bar(null)
                Action(stringResource(R.string.settings_cancel), filled = false, onClick = viewModel::cancel)
            }
            is SendToComputerState.Taking -> {
                Line(stringResource(R.string.settings_send_computer_taking, s.taken, s.total), strong = true)
                Spacer(modifier = Modifier.height(4.dp))
                Line(stringResource(R.string.settings_send_computer_taking_desc))
                Bar(if (s.total == 0) 0f else s.taken.toFloat() / s.total)
                Action(stringResource(R.string.settings_cancel), filled = false, onClick = viewModel::cancel)
            }
            is SendToComputerState.Finished -> {
                if (s.libraryOnly) {
                    Line(stringResource(R.string.settings_send_computer_finished_library), strong = true)
                    Spacer(modifier = Modifier.height(4.dp))
                    Line(stringResource(R.string.settings_send_computer_finished_desc))
                } else if (s.taken >= s.total) {
                    Line(pluralStringResource(R.plurals.settings_send_computer_finished, s.total, s.total), strong = true)
                    val rest = s.rest
                    if (rest.full > 0) {
                        // Left for room: a send again carries them, once there is room enough for them.
                        val short = rest.noRoom ?: 0L
                        Spacer(modifier = Modifier.height(4.dp))
                        Line(
                            if (short > 0L) {
                                pluralStringResource(R.plurals.settings_send_computer_finished_rest_free, rest.full, rest.full, size(short))
                            } else {
                                pluralStringResource(R.plurals.settings_send_computer_finished_rest_full, rest.full, rest.full)
                            },
                        )
                    }
                    if (rest.failed > 0) {
                        // These failed for another reason: a send again is not promised to carry them.
                        Spacer(modifier = Modifier.height(4.dp))
                        Line(pluralStringResource(R.plurals.settings_send_computer_finished_failed, rest.failed, rest.failed))
                    }
                    if (rest.full == 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Line(stringResource(R.string.settings_send_computer_finished_desc))
                    }
                } else if (s.chosen) {
                    Line(stringResource(R.string.settings_send_computer_chosen, s.taken, s.total), strong = true)
                    Spacer(modifier = Modifier.height(4.dp))
                    Line(stringResource(R.string.settings_send_computer_chosen_desc))
                } else {
                    Line(stringResource(R.string.settings_send_computer_partly, s.taken, s.total), strong = true)
                    Spacer(modifier = Modifier.height(4.dp))
                    Line(stringResource(R.string.settings_send_computer_partly_desc))
                }
                Action(stringResource(R.string.settings_storage_dismiss_button), filled = false, onClick = viewModel::dismiss)
            }
            is SendToComputerState.Error -> {
                Line(
                    when {
                        s.listInTheWay -> stringResource(R.string.settings_send_computer_list_in_the_way)
                        s.libraryInTheWay -> stringResource(R.string.settings_send_computer_library_in_the_way)
                        s.noAnswer -> stringResource(R.string.settings_send_computer_no_answer)
                        else -> s.message
                    },
                    error = true,
                )
                // Copies left that nothing takes (no room, or another list in the way): they can go, to win the room back.
                if (s.copiesLeft) Action(stringResource(R.string.settings_send_computer_remove_copies), filled = false, onClick = viewModel::cancel)
                Action(stringResource(R.string.settings_storage_dismiss_button), filled = false, onClick = viewModel::dismiss)
            }
        }
    }
}
