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
) {
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
        onPauseOrDispose { }
    }

    Box(Modifier.fillMaxSize()) {
        SettingsScaffold(title = "Link Stash on the web", onBack = onBack) {
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
            title = "Remove ${d.name}?",
            text = "It stops seeing your phone at once. Its library stays as it is.",
            confirm = "Remove",
            onConfirm = { viewModel.remove(d.id); removing = null },
            onDismiss = { removing = null },
        )
    }
    if (unlinkAll) {
        ConfirmDialog(
            title = "Unlink everything?",
            text = "Every linked browser is unlinked at once. Nothing is deleted from any library.",
            confirm = "Unlink everything",
            onConfirm = { viewModel.unlinkEverything(); unlinkAll = false },
            onDismiss = { unlinkAll = false },
        )
    }
    message?.let { m ->
        AlertDialog(
            onDismissRequest = viewModel::messageShown,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            text = { Text(m, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = viewModel::messageShown) { Text("OK") } },
        )
    }
}

@Composable
private fun NotLinkedCard(onScan: () -> Unit) {
    SettingsSectionLabel("Link a browser")
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Pick up where you left off, mirror, send your library.", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Open play.stashfm.app › Settings › Your phone",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onScan) { Text("Scan code") }
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
) {
    SettingsSectionLabel("Linked devices")
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
            title = "Link another browser",
            subtitle = "Up to 4",
            leadingIcon = Icons.Outlined.QrCodeScanner,
            onClick = { if (!busy) onScan() },
        )
    }
    SettingsGroupCard(rows = rows)
    SettingsSectionLabel("Handoff")
    SettingsGroupCard(
        rows = listOf {
            SettingsToggleRow(
                title = "Pick up where you left off",
                subtitle = "Share what's playing with your linked devices.",
                checked = handoffOn,
                onCheckedChange = onHandoff,
            )
        },
    )
    SettingsSectionLabel("Your library")
    SettingsGroupCard(
        rows = listOf {
            SettingsNavRow(
                title = "Send my library to a browser",
                subtitle = "Choose what goes: likes, plays, each playlist.",
                leadingIcon = Icons.AutoMirrored.Outlined.Send,
                onClick = { if (!busy && status.browsers.isNotEmpty()) onSendLibrary() },
            )
        },
    )
    if (sends.isNotEmpty()) {
        SettingsSectionLabel("Waiting for you")
        SettingsGroupCard(
            rows = sends.map { s ->
                @Composable {
                    SettingsNavRow(
                        title = "${s.name} sent you ${s.summary}",
                        subtitle = s.problem?.let { "$it Discard it." } ?: "Add it, choose what to add, or discard it.",
                        leadingIcon = Icons.Outlined.Inbox,
                        onClick = { onOpenSend(s) },
                    )
                }
            },
        )
    }
    SettingsSectionLabel("Unlink")
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Unlinking never deletes anything from a library.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onUnlinkAll,
                enabled = !busy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Unlink everything") }
        }
    }
}

private fun subtitleOf(d: LinkedDevice, serverTime: Long): String = when {
    d.isMe -> "This phone"
    d.trust == DeviceTrust.KEY_CHANGED -> WebLinkCopy.KEY_CHANGED
    d.trust == DeviceTrust.NOT_VERIFIED -> WebLinkCopy.NOT_VERIFIED
    serverTime <= 0 || d.lastSeenAt <= 0 -> "Linked"
    else -> when (val days = ((serverTime - d.lastSeenAt).coerceAtLeast(0) / 86_400_000L).toInt()) {
        0 -> "Last used today"
        1 -> "Last used yesterday"
        else -> "Last used $days days ago"
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
                    Text("Reading the code…", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is PairingState.Confirm -> {
                    Text("Link ${state.browserName}?", style = MaterialTheme.typography.titleMedium)
                    Text(
                        buildAnnotatedString {
                            append("Your computer will ask you to check ")
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(PairingSession.spaced(state.code)) }
                            append(". It will see what you're playing here, and you can choose to mirror your library with it.")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.fromLink) {
                        // An App Link can come from anyone (a chat, a web page), and the name above is the browser's own choice.
                        Text(
                            "Only continue if you just opened Link your phone on your own computer, and it's in front of you now.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onClose) { Text("Cancel") }
                        Button(onClick = onLink) { Text("Link") }
                    }
                }
                is PairingState.Waiting -> {
                    Text(
                        buildAnnotatedString {
                            append("Waiting for your computer… · ")
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(PairingSession.spaced(state.code)) }
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Check that ${state.browserName} shows the same six digits, then tap Link there.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedButton(onClick = onClose) { Text("Cancel") }
                }
                is PairingState.Linked -> {
                    Text("Linked to ${state.browserName}", style = MaterialTheme.typography.titleMedium)
                    Text("Linked. Nothing is mirrored yet.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onClose) { Text("Done") }
                }
                is PairingState.Failed -> {
                    state.code?.let {
                        Text(
                            buildAnnotatedString {
                                append("Code ")
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(PairingSession.spaced(it)) }
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onClose) { Text("OK") }
                        Button(onClick = onScanAgain) { Text("Scan again") }
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
                TextButton(onClick = onRename) { Text("Rename") }
                if (!device.isMe) {
                    TextButton(onClick = onRemove, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text("Remove")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun RenameDialog(device: LinkedDevice, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(device.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        title = { Text("Rename", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it.take(60) }, singleLine = true)
                Text(
                    if (device.isMe) "Your linked devices see this name." else "This name stays on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
