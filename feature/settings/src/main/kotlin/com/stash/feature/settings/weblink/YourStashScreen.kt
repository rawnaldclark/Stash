package com.stash.feature.settings.weblink

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import com.stash.feature.settings.R
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.weblink.LinkedDevice
import com.stash.core.data.weblink.PairingSession
import com.stash.core.data.weblink.PairingState
import com.stash.core.data.weblink.WebLinkCopy
import com.stash.core.data.weblink.inbox.IncomingSend
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.settings.SupporterClaim
import com.stash.feature.settings.SupporterRow
import com.stash.feature.settings.SupporterViewModel
import com.stash.feature.settings.WebLibraryExportSheet
import com.stash.feature.settings.WebLibraryExportViewModel
import com.stash.feature.settings.cloud.accentOf
import com.stash.feature.settings.cloud.logoOf
import com.stash.feature.settings.components.SettingsGroupCard
import com.stash.feature.settings.components.SettingsNavRow
import com.stash.feature.settings.components.SettingsRowPadH
import com.stash.feature.settings.components.SettingsRowPadV
import com.stash.feature.settings.components.SettingsScaffold
import com.stash.feature.settings.components.SettingsSectionLabel
import com.stash.feature.settings.components.SettingsToggleRow

/**
 * Settings › Library & Storage › Your Stash (owner decision D21, phase P0). Your Stash is the end-to-end encrypted sync
 * space the link with Stash on the web already is: its members are your devices, and there is no account and no email.
 *
 * Top to bottom: the posture in one line; Devices (this phone and the linked browsers, each with when it was last used;
 * linking a browser by scanning its code; a device's Rename and Remove); while linked, what the link does (Pick up where
 * you left off, Mirror, Send my library, Waiting for you); Supporter (the existing Ko-fi claim); Cloud (the user's own
 * cloud accounts, opening Accounts & Sync at them); Backups (the existing Export / Import backup, started on Library &
 * Storage, where their pickers and the restore's restart live); What's coming; and, while linked, Unlink everything.
 *
 * Presentation only: every action already existed, nothing new is stored, and the only server call is the link's existing
 * read of the device list when the page resumes. [yourStashUi] decides what each section shows. The route keeps its name
 * ([WebLinkViewModel.ARG_LINK]): a pairing link opened from outside lands here and goes straight to the confirm sheet.
 *
 * @param onOpenCloud Accounts & Sync, scrolled to Cloud storage.
 * @param onBackupShortcut Library & Storage, with that backup action started.
 */
@Composable
fun YourStashScreen(
    onBack: () -> Unit,
    onOpenCloud: () -> Unit,
    onBackupShortcut: (BackupShortcut) -> Unit,
    viewModel: WebLinkViewModel = hiltViewModel(),
    exportVm: WebLibraryExportViewModel = hiltViewModel(),
    inboxVm: InboxViewModel = hiltViewModel(),
    mirrorVm: MirrorViewModel = hiltViewModel(),
    supporterVm: SupporterViewModel = hiltViewModel(),
    sources: YourStashViewModel = hiltViewModel(),
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
    val supporterRow by sources.supporter.collectAsStateWithLifecycle()
    val cloudAccounts by sources.cloudAccounts.collectAsStateWithLifecycle()
    val ui = yourStashUi(status, supporterRow, sources.cloudAvailable, sources.cloudProviders, cloudAccounts)
    var openSend by remember { mutableStateOf<IncomingSend?>(null) }
    var claimOpen by rememberSaveable { mutableStateOf(false) }

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
        SettingsScaffold(title = stringResource(R.string.settings_your_stash_title), onBack = onBack) {
            PostureLine()
            DevicesSection(
                devices = ui.devices,
                pairing = pairing,
                busy = busy,
                onPick = { picked = it },
                onScan = viewModel::startScan,
                pairingPanel = {
                    PairingPanel(pairing, onLink = viewModel::confirm, onClose = viewModel::closePairing, onScanAgain = viewModel::startScan)
                },
            )
            ui.linkFeatures?.let { link ->
                LinkFeatures(
                    canSend = link.canSend,
                    busy = busy,
                    handoffOn = handoffOn,
                    onHandoff = viewModel::setHandoffEnabled,
                    sends = sends,
                    onSendLibrary = exportVm::openPicker,
                    onOpenSend = { openSend = it },
                    mirror = { MirrorSection(mirror, mirrorVm) },
                )
            }
            SupporterSection(ui.supporter, onClaim = { claimOpen = true })
            CloudSection(ui.cloud, onOpenCloud)
            BackupsSection(ui.backups, onBackupShortcut)
            ComingSection(ui.coming)
            if (ui.linkFeatures != null) UnlinkSection(busy, onUnlinkAll = { unlinkAll = true })
        }
        if (scanning) {
            BackHandler(onBack = viewModel::stopScan)
            QrScannerScreen(onCode = viewModel::openLink, onClose = viewModel::stopScan)
        }
    }

    // The Ko-fi claim, the same sheet Lossless sources opens (supporterVm holds the claim's steps).
    SupporterClaim(supporterVm, ui.supporter, claimOpen, onClose = { claimOpen = false })

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

/** The page's first line: what Your Stash is, and that nothing leaves the phone unencrypted. */
@Composable
private fun PostureLine() {
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp).size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.settings_your_stash_posture),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Devices: the pairing steps while a pairing runs, then this phone and the linked browsers (tap one: Rename, Remove), and
 * the scanner row. Not linked: this phone alone and "Link Stash on the web" (the name the web player's instructions use),
 * hidden while a pairing runs, as the old screen's not-linked card was.
 */
@Composable
private fun DevicesSection(
    devices: YourStashDevices,
    pairing: PairingState,
    busy: Boolean,
    onPick: (LinkedDevice) -> Unit,
    onScan: () -> Unit,
    pairingPanel: @Composable () -> Unit,
) {
    SettingsSectionLabel(stringResource(R.string.settings_your_stash_section_devices))
    val pairingIdle = pairing == PairingState.Idle
    if (!pairingIdle) pairingPanel()
    when (devices) {
        YourStashDevices.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
        YourStashDevices.ThisPhoneOnly -> if (pairingIdle) {
            val linkSubtitle = stringResource(R.string.settings_web_link_nav_subtitle) + "\n" +
                stringResource(R.string.settings_web_link_not_linked_instructions)
            SettingsGroupCard(
                rows = listOf(
                    {
                        StaticRow(
                            icon = Icons.Outlined.PhoneAndroid,
                            title = stringResource(R.string.settings_web_link_device_this_phone),
                            subtitle = stringResource(R.string.settings_your_stash_no_browser_yet),
                        )
                    },
                    {
                        SettingsNavRow(
                            title = stringResource(R.string.settings_web_link_nav_title),
                            subtitle = linkSubtitle,
                            leadingIcon = Icons.Outlined.QrCodeScanner,
                            onClick = onScan,
                        )
                    },
                ),
            )
        }
        is YourStashDevices.Linked -> {
            devices.problem?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val rows = mutableListOf<@Composable () -> Unit>()
            for (r in devices.rows) {
                rows += {
                    SettingsNavRow(
                        title = r.device.name,
                        subtitle = seenText(r.seen),
                        leadingIcon = if (r.device.type == "phone") Icons.Outlined.PhoneAndroid else Icons.Outlined.Computer,
                        onClick = { if (!busy) onPick(r.device) },
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
        }
    }
}

/** A device row's second line in words. */
@Composable
private fun seenText(seen: DeviceSeen): String = when (seen) {
    DeviceSeen.ThisPhone -> stringResource(R.string.settings_web_link_device_this_phone)
    DeviceSeen.KeyChanged -> WebLinkCopy.KEY_CHANGED
    DeviceSeen.NotVerified -> WebLinkCopy.NOT_VERIFIED
    DeviceSeen.Linked -> stringResource(R.string.settings_web_link_device_linked)
    is DeviceSeen.DaysAgo -> when (seen.days) {
        0 -> stringResource(R.string.settings_web_link_device_last_used_today)
        1 -> stringResource(R.string.settings_web_link_device_last_used_yesterday)
        else -> pluralStringResource(R.plurals.settings_web_link_device_last_used_days, seen.days, seen.days)
    }
}

/** What the link does, while linked: unchanged from the old screen, in its order. */
@Composable
private fun LinkFeatures(
    canSend: Boolean,
    busy: Boolean,
    handoffOn: Boolean,
    onHandoff: (Boolean) -> Unit,
    sends: List<IncomingSend>,
    onSendLibrary: () -> Unit,
    onOpenSend: (IncomingSend) -> Unit,
    mirror: @Composable () -> Unit,
) {
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
                onClick = { if (!busy && canSend) onSendLibrary() },
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
}

/**
 * Supporter: the Ko-fi claim's state as Lossless sources shows it. Not claimed: the existing claim sheet. Claimed: says so
 * (removing the code stays on Lossless sources). No section when no owner relay is configured: there is nothing to lift.
 */
@Composable
private fun SupporterSection(row: SupporterRow, onClaim: () -> Unit) {
    if (row == SupporterRow.Hidden) return
    SettingsSectionLabel(stringResource(R.string.settings_your_stash_section_supporter))
    SettingsGroupCard(
        rows = listOf {
            when (row) {
                is SupporterRow.Offer -> SettingsNavRow(
                    title = stringResource(R.string.settings_supporter_offer),
                    subtitle = stringResource(R.string.settings_your_stash_supporter_offer_subtitle),
                    leadingIcon = Icons.Outlined.FavoriteBorder,
                    onClick = onClaim,
                )
                is SupporterRow.Supporter -> StaticRow(
                    icon = Icons.Outlined.Favorite,
                    title = stringResource(R.string.settings_supporter_active),
                    subtitle = if (row.hasToken) stringResource(R.string.settings_your_stash_supporter_code_here) else null,
                    titleColor = StashTheme.extendedColors.success,
                )
                SupporterRow.Hidden -> Unit
            }
        },
    )
}

/**
 * Cloud: the user's own cloud accounts, signed in on this phone (no Stash identity). Every row opens Accounts & Sync at
 * Cloud storage, where they are connected, reconnected and disconnected.
 */
@Composable
private fun CloudSection(cloud: YourStashCloud, onOpenCloud: () -> Unit) {
    val rows: List<@Composable () -> Unit> = when (cloud) {
        YourStashCloud.Hidden -> return
        is YourStashCloud.NoneConnected -> listOf {
            SettingsNavRow(
                title = stringResource(R.string.settings_your_stash_cloud_connect_title),
                // Brand names, not translated.
                subtitle = cloud.providers.joinToString(", ") { it.displayName },
                leadingIcon = Icons.Outlined.Cloud,
                onClick = onOpenCloud,
            )
        }
        is YourStashCloud.Connected -> cloud.accounts.map { row ->
            @Composable {
                val provider = row.account.provider
                SettingsNavRow(
                    title = provider.displayName,
                    subtitle = cloudRowSubtitle(
                        row,
                        several = cloud.accounts.count { it.account.provider == provider } > 1,
                        signedOut = stringResource(R.string.settings_your_stash_cloud_signed_out),
                    ),
                    leadingContent = {
                        Icon(
                            imageVector = ImageVector.vectorResource(logoOf(provider)),
                            contentDescription = null,
                            tint = accentOf(provider),
                            // Brand logos fill their 24-unit box; the same inset as the account cards.
                            modifier = Modifier.size(18.dp).padding(1.5.dp),
                        )
                    },
                    onClick = onOpenCloud,
                )
            }
        }
    }
    SettingsSectionLabel(stringResource(R.string.settings_your_stash_section_cloud))
    SettingsGroupCard(rows = rows)
}

/**
 * Backups: the existing Export backup and Import backup, offered whether linked or not. Each opens Library & Storage with
 * that action started, since the pickers, scope dialogs and a restore's restart live there. When the last backup was made
 * isn't recorded anywhere today, so there is no time to show (P1's nightly backup brings one).
 */
@Composable
private fun BackupsSection(backups: List<BackupShortcut>, onBackupShortcut: (BackupShortcut) -> Unit) {
    SettingsSectionLabel(stringResource(R.string.settings_your_stash_section_backups))
    SettingsGroupCard(
        rows = backups.map { shortcut ->
            @Composable {
                when (shortcut) {
                    BackupShortcut.BACK_UP -> SettingsNavRow(
                        title = stringResource(R.string.settings_storage_export_backup_button),
                        subtitle = stringResource(R.string.settings_storage_backup_subtitle),
                        leadingIcon = Icons.Outlined.Backup,
                        onClick = { onBackupShortcut(shortcut) },
                    )
                    BackupShortcut.RESTORE -> SettingsNavRow(
                        title = stringResource(R.string.settings_storage_import_backup_button),
                        leadingIcon = Icons.Outlined.Restore,
                        onClick = { onBackupShortcut(shortcut) },
                    )
                }
            }
        },
    )
}

/** What's coming: D21's later phases, worded as future, no dates, nothing to tap. */
@Composable
private fun ComingSection(coming: List<YourStashComing>) {
    SettingsSectionLabel(stringResource(R.string.settings_your_stash_section_coming))
    SettingsGroupCard(
        rows = coming.map { item ->
            @Composable {
                when (item) {
                    YourStashComing.PASSKEY_RECOVERY -> StaticRow(
                        icon = Icons.Outlined.Key,
                        title = stringResource(R.string.settings_your_stash_coming_passkey_title),
                        subtitle = stringResource(R.string.settings_your_stash_coming_passkey_desc),
                    )
                    YourStashComing.NIGHTLY_BACKUP -> StaticRow(
                        icon = Icons.Outlined.Schedule,
                        title = stringResource(R.string.settings_your_stash_coming_backup_title),
                        subtitle = stringResource(R.string.settings_your_stash_coming_backup_desc),
                    )
                    YourStashComing.RESTORE_NEW_PHONE -> StaticRow(
                        icon = Icons.Outlined.SystemUpdate,
                        title = stringResource(R.string.settings_your_stash_coming_restore_title),
                        subtitle = stringResource(R.string.settings_your_stash_coming_restore_desc),
                    )
                }
            }
        },
    )
    Text(
        stringResource(R.string.settings_your_stash_coming_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** Unlink everything, last on the page as a sign-out is: deletes the space; no library changes. */
@Composable
private fun UnlinkSection(busy: Boolean, onUnlinkAll: () -> Unit) {
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

/**
 * A row that only says something: [SettingsNavRow]'s layout (icon, title, second line) with no chevron and no tap, for
 * this phone before anything is linked, a supporter's status and What's coming.
 */
@Composable
private fun StaticRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsRowPadH, vertical = SettingsRowPadV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, color = titleColor)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = StashTheme.extendedColors.textTertiary,
                )
            }
        }
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
