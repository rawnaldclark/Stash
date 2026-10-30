package com.stash.feature.settings.weblink

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.stash.feature.settings.R
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.weblink.DeviceTrust
import com.stash.core.data.weblink.LinkedDevice
import com.stash.core.data.weblink.PairingSession
import com.stash.core.data.weblink.PairingState
import com.stash.core.data.weblink.WebLinkCopy
import com.stash.core.data.weblink.WebLinkStatus
import com.stash.core.data.weblink.inbox.IncomingSend
import com.stash.feature.settings.WebLibraryExportSheet
import com.stash.feature.settings.WebLibraryExportViewModel
import com.stash.core.ui.components.GlassCard
import com.stash.feature.settings.components.SettingsGroupCard
import com.stash.feature.settings.components.SettingsToggleRow
import com.stash.feature.settings.components.SettingsNavRow
import com.stash.feature.settings.components.SettingsScaffold
import com.stash.feature.settings.components.SettingsSectionLabel

/**
 * Settings › Library & Storage › Link Stash on the web (spec §2.2, §2.6): link a browser by scanning its code, see the linked
 * devices, rename or remove one, unlink everything. Linking changes nothing in either library; nothing is mirrored yet.
 */
@Composable
fun SettingsWebLinkScreen(
    onBack: () -> Unit,
    viewModel: WebLinkViewModel = hiltViewModel(),
    exportVm: WebLibraryExportViewModel = hiltViewModel(),
    inboxVm: InboxViewModel = hiltViewModel(),
    mirrorVm: MirrorViewModel = hiltViewModel(),
) {
    val mirror by mirrorVm.status.collectAsStateWithLifecycle()
    val mirrorMessage by mirrorVm.message.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val pairing by viewModel.pairing.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val handoffOn by viewModel.handoffEnabled.collectAsStateWithLifecycle()
    val sends by inboxVm.sends.collectAsStateWithLifecycle()
    val inboxStep by inboxVm.step.collectAsStateWithLifecycle()
    var openSend by remember { mutableStateOf<IncomingSend?>(null) }

    var picked by remember { mutableStateOf<LinkedDevice?>(null) }
    var renaming by remember { mutableStateOf<LinkedDevice?>(null) }
    var removing by remember { mutableStateOf<LinkedDevice?>(null) }
    var unlinkAll by remember { mutableStateOf(false) }

    // Back on the screen (from the browser, another app): read the list again, as the web does when its tab returns.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        inboxVm.refresh()
        mirrorVm.refresh()
        onPauseOrDispose { }
    }

    Box(Modifier.fillMaxSize()) {
        SettingsScaffold(title = stringResource(R.string.settings_web_link_nav_title), onBack = onBack) {
            if (pairing != PairingState.Idle) {
                PairingPanel(pairing, onLink = viewModel::confirm, onClose = viewModel::closePairing, onScanAgain = viewModel::startScan)
            }
            when (val s = status) {
                WebLinkStatus.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                WebLinkStatus.NotLinked -> if (pairing == PairingState.Idle) NotLinkedCard(onScan = viewModel::startScan)
                is WebLinkStatus.Linked -> LinkedList(
                    status = s,
                    busy = busy,
                    onPick = { picked = it },
                    onScan = viewModel::startScan,
                    onUnlinkAll = { unlinkAll = true },
                    handoffOn = handoffOn,
                    onHandoff = viewModel::setHandoffEnabled,
                    sends = sends,
                    onSendLibrary = exportVm::openPicker,
                    onOpenSend = { openSend = it },
                    mirror = { MirrorSection(mirror, mirrorVm) },
                )
            }
        }
        if (scanning) {
            BackHandler(onBack = viewModel::stopScan)
            QrScannerScreen(onCode = viewModel::openLink, onClose = viewModel::stopScan)
        }
    }

    // Send my library to a browser: the export picker (Save as file is there too), and a received send's choices.
    WebLibraryExportSheet(exportVm)
    openSend?.let { send ->
        SendDialog(
            send = send,
            onAdd = { openSend = null; inboxVm.addAll(send) },
            onChoose = { openSend = null; inboxVm.choose(send) },
            onLater = { openSend = null },
            onDiscard = { openSend = null; inboxVm.discard(send) },
        )
    }
    InboxStepDialogs(inboxStep, inboxVm)

    picked?.let { d ->
        DeviceActionsDialog(
            device = d,
            onRename = { picked = null; renaming = d },
            onRemove = { picked = null; removing = d },
            onDismiss = { picked = null },
        )
    }
    renaming?.let { d ->
        RenameDialog(d, onSave = { viewModel.rename(d.id, it); renaming = null }, onDismiss = { renaming = null })
    }
    removing?.let { d ->
        ConfirmDialog(
            title = stringResource(R.string.settings_web_link_remove_device_title, d.name),
            text = stringResource(R.string.settings_web_link_remove_device_desc),
            confirm = stringResource(R.string.settings_web_link_action_remove),
            onConfirm = { viewModel.remove(d.id); removing = null },
            onDismiss = { removing = null },
        )
    }
    if (unlinkAll) {
        ConfirmDialog(
            title = stringResource(R.string.settings_web_link_unlink_everything_title),
            text = stringResource(R.string.settings_web_link_unlink_everything_desc),
            confirm = stringResource(R.string.settings_web_link_action_unlink_everything),
            onConfirm = { viewModel.unlinkEverything(); unlinkAll = false },
            onDismiss = { unlinkAll = false },
        )
    }
    mirrorMessage?.let { m ->
        AlertDialog(
            onDismissRequest = mirrorVm::messageShown,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            text = { Text(m, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = mirrorVm::messageShown) { Text(stringResource(R.string.settings_ok)) } },
        )
    }
    message?.let { m ->
        AlertDialog(
            onDismissRequest = viewModel::messageShown,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            text = { Text(m, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = viewModel::messageShown) { Text(stringResource(R.string.settings_ok)) } },
        )
    }
}

@Composable
private fun NotLinkedCard(onScan: () -> Unit) {
    SettingsSectionLabel(stringResource(R.string.settings_web_link_section_link_browser))
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_web_link_nav_subtitle), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.settings_web_link_not_linked_instructions),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onScan) { Text(stringResource(R.string.settings_web_link_scan_code_button)) }
        }
    }
}

@Composable
private fun LinkedList(
    status: WebLinkStatus.Linked,
    busy: Boolean,
    onPick: (LinkedDevice) -> Unit,
    onScan: () -> Unit,
    onUnlinkAll: () -> Unit,
    handoffOn: Boolean,
    onHandoff: (Boolean) -> Unit,
    sends: List<IncomingSend>,
    onSendLibrary: () -> Unit,
    onOpenSend: (IncomingSend) -> Unit,
    mirror: @Composable () -> Unit,
) {
    SettingsSectionLabel(stringResource(R.string.settings_web_link_section_linked_devices))
    status.problem?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    val rows = mutableListOf<@Composable () -> Unit>()
    for (d in status.devices) {
        rows += {
            SettingsNavRow(
                title = d.name,
                subtitle = subtitleOf(d, status.serverTime),
                leadingIcon = if (d.type == "phone") Icons.Outlined.PhoneAndroid else Icons.Outlined.Computer,
                onClick = { if (!busy) onPick(d) },
            )
        }
    }
    rows += {
        SettingsNavRow(
            title = stringResource(R.string.settings_web_link_link_another_browser),
            subtitle = stringResource(R.string.settings_web_link_up_to_four),
            leadingIcon = Icons.Outlined.QrCodeScanner,
            onClick = { if (!busy) onScan() },
        )
    }
    SettingsGroupCard(rows = rows)
    SettingsSectionLabel(stringResource(R.string.settings_web_link_section_handoff))
    SettingsGroupCard(
        rows = listOf {
            SettingsToggleRow(
                title = stringResource(R.string.settings_web_link_handoff_title),
                subtitle = stringResource(R.string.settings_web_link_handoff_subtitle),
                checked = handoffOn,
                onCheckedChange = onHandoff,
            )
        },
    )
    mirror()
    SettingsSectionLabel(stringResource(R.string.settings_web_link_section_your_library))
    SettingsGroupCard(
        rows = listOf {
            SettingsNavRow(
                title = stringResource(R.string.settings_web_link_send_library_title),
                subtitle = stringResource(R.string.settings_web_link_send_library_subtitle),
                leadingIcon = Icons.AutoMirrored.Outlined.Send,
                onClick = { if (!busy && status.browsers.isNotEmpty()) onSendLibrary() },
            )
        },
    )
    if (sends.isNotEmpty()) {
        SettingsSectionLabel(stringResource(R.string.settings_web_link_section_waiting_for_you))
        SettingsGroupCard(
            rows = sends.map { s ->
                @Composable {
                    SettingsNavRow(
                        title = stringResource(R.string.settings_web_link_sent_you_summary, s.name, s.summary),
                        subtitle = s.problem?.let { stringResource(R.string.settings_web_link_sent_problem_discard, it) } ?: stringResource(R.string.settings_web_link_sent_actions_subtitle),
                        leadingIcon = Icons.Outlined.Inbox,
                        onClick = { onOpenSend(s) },
                    )
                }
            },
        )
    }
    SettingsSectionLabel(stringResource(R.string.settings_web_link_section_unlink))
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.settings_web_link_unlink_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onUnlinkAll,
                enabled = !busy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.settings_web_link_action_unlink_everything)) }
        }
    }
}

@Composable
private fun subtitleOf(d: LinkedDevice, serverTime: Long): String = when {
    d.isMe -> stringResource(R.string.settings_web_link_device_this_phone)
    d.trust == DeviceTrust.KEY_CHANGED -> WebLinkCopy.KEY_CHANGED
    d.trust == DeviceTrust.NOT_VERIFIED -> WebLinkCopy.NOT_VERIFIED
    serverTime <= 0 || d.lastSeenAt <= 0 -> stringResource(R.string.settings_web_link_device_linked)
    else -> when (val days = ((serverTime - d.lastSeenAt).coerceAtLeast(0) / 86_400_000L).toInt()) {
        0 -> stringResource(R.string.settings_web_link_device_last_used_today)
        1 -> stringResource(R.string.settings_web_link_device_last_used_yesterday)
        else -> pluralStringResource(R.plurals.settings_web_link_device_last_used_days, days, days)
    }
}

/** The pairing steps: confirm sheet, waiting with the code, linked, or why it stopped (spec §2.2). */
@Composable
private fun PairingPanel(state: PairingState, onLink: () -> Unit, onClose: () -> Unit, onScanAgain: () -> Unit) {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state) {
                PairingState.Idle -> Unit
                PairingState.Checking -> {
                    Text(stringResource(R.string.settings_web_link_reading_code), style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is PairingState.Confirm -> {
                    Text(stringResource(R.string.settings_web_link_confirm_title, state.browserName), style = MaterialTheme.typography.titleMedium)
                    Text(
                        buildAnnotatedString {
                            append(stringResource(R.string.settings_web_link_confirm_body_before_code))
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(PairingSession.spaced(state.code)) }
                            append(stringResource(R.string.settings_web_link_confirm_body_after_code))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.fromLink) {
                        // An App Link can come from anyone (a chat, a web page), and the name above is the browser's own choice.
                        Text(
                            stringResource(R.string.settings_web_link_confirm_from_link_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onClose) { Text(stringResource(R.string.settings_cancel)) }
                        Button(onClick = onLink) { Text(stringResource(R.string.settings_web_link_action_link)) }
                    }
                }
                is PairingState.Waiting -> {
                    Text(
                        buildAnnotatedString {
                            append(stringResource(R.string.settings_web_link_waiting_computer))
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(PairingSession.spaced(state.code)) }
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.settings_web_link_waiting_desc, state.browserName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedButton(onClick = onClose) { Text(stringResource(R.string.settings_cancel)) }
                }
                is PairingState.Linked -> {
                    Text(stringResource(R.string.settings_web_link_linked_to, state.browserName), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.settings_web_link_linked_desc), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onClose) { Text(stringResource(R.string.settings_done)) }
                }
                is PairingState.Failed -> {
                    state.code?.let {
                        Text(
                            buildAnnotatedString {
                                append(stringResource(R.string.settings_web_link_code_label))
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(PairingSession.spaced(it)) }
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onClose) { Text(stringResource(R.string.settings_ok)) }
                        Button(onClick = onScanAgain) { Text(stringResource(R.string.settings_web_link_scan_again)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceActionsDialog(device: LinkedDevice, onRename: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        title = { Text(device.name, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.padding(top = 4.dp)) {
                TextButton(onClick = onRename) { Text(stringResource(R.string.settings_web_link_action_rename)) }
                if (!device.isMe) {
                    TextButton(onClick = onRemove, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text(stringResource(R.string.settings_web_link_action_remove))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_close)) } },
    )
}

@Composable
private fun RenameDialog(device: LinkedDevice, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(device.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        title = { Text(stringResource(R.string.settings_web_link_rename_dialog_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it.take(60) }, singleLine = true)
                Text(
                    if (device.isMe) stringResource(R.string.settings_web_link_rename_me_hint) else stringResource(R.string.settings_web_link_rename_other_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.settings_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(confirm)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}
