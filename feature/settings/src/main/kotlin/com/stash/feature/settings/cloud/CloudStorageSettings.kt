package com.stash.feature.settings.cloud

import android.content.Context
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.auth.model.AuthState
import com.stash.core.auth.model.UserInfo
import com.stash.core.data.db.dao.CloudFolderWithCount
import com.stash.core.data.db.entity.CloudScanStatus
import com.stash.core.model.CloudProvider
import com.stash.feature.settings.R
import com.stash.data.cloud.CloudAccount
import com.stash.feature.settings.components.AccountConnectionCard
import com.stash.feature.settings.components.LogoSize
import com.stash.feature.settings.components.SettingsGroupCard
import com.stash.feature.settings.components.SettingsNavRow
import com.stash.feature.settings.components.SettingsPickerRow
import com.stash.feature.settings.components.SettingsRowPadH
import com.stash.feature.settings.components.SettingsRowPadV
import com.stash.feature.settings.components.SettingsSectionLabel
import com.stash.feature.settings.components.SettingsToggleRow
import com.stash.data.cloud.provider.gdrive.GoogleDriveProvider
import com.stash.data.cloud.upload.UploadStatus
import com.stash.core.ui.R as UiR

/** Brand accents, as the Discord card does inline. */
internal fun accentOf(provider: CloudProvider): Color = when (provider) {
    CloudProvider.DROPBOX -> Color(0xFF0061FE)
    CloudProvider.ONEDRIVE -> Color(0xFF0078D4)
    CloudProvider.GOOGLE_DRIVE -> Color(0xFF1FA463)
}

@DrawableRes
internal fun logoOf(provider: CloudProvider): Int = when (provider) {
    CloudProvider.DROPBOX -> UiR.drawable.ic_brand_dropbox
    CloudProvider.ONEDRIVE -> UiR.drawable.ic_brand_onedrive
    CloudProvider.GOOGLE_DRIVE -> UiR.drawable.ic_brand_google_drive
}

/**
 * Google's picker, ready to launch: call it with an account id ("Add songs", "Reconnect") or null
 * (connect a new account; its first pick is the sign-in). The result goes back to the ViewModel.
 */
@Composable
private fun rememberGooglePicker(viewModel: CloudStorageViewModel): (Long?) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.finishGooglePick(result.data)
    }
    return { accountId ->
        viewModel.startGooglePick(accountId) { sender -> launcher.launch(IntentSenderRequest.Builder(sender).build()) }
    }
}

/** Shows [CloudStorageViewModel.messages] and its notices as toasts while the screen is on. */
@Composable
private fun CloudMessages(viewModel: CloudStorageViewModel) {
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    LaunchedEffect(viewModel) {
        viewModel.notices.collect { notice ->
            Toast.makeText(context, notice.text(context), Toast.LENGTH_LONG).show()
        }
    }
}

/** A [CloudNotice] in words. */
internal fun CloudNotice.text(context: Context): String = when (this) {
    CloudNotice.AccountUnidentified -> context.getString(R.string.settings_cloud_gdrive_account_unidentified)
    is CloudNotice.AlreadyConnected ->
        if (added > 0) context.resources.getQuantityString(R.plurals.settings_cloud_gdrive_already_connected_adding, added, account, added)
        else context.getString(R.string.settings_cloud_gdrive_already_connected, account)
    is CloudNotice.SavingTo -> context.getString(R.string.settings_cloud_gdrive_saving_to, account)
    is CloudNotice.Disconnected -> withKept(context, context.getString(R.string.settings_cloud_disconnected, account), kept)
    is CloudNotice.DisconnectFailed -> context.getString(R.string.settings_cloud_disconnect_failed, account)
    is CloudNotice.FolderRemoved -> withKept(context, context.getString(R.string.settings_cloud_folder_removed), kept)
    CloudNotice.FolderRemoveFailed -> context.getString(R.string.settings_cloud_folder_remove_failed)
}

/** [first], then how many downloads stay on the phone, if any. */
private fun withKept(context: Context, first: String, kept: Int): String =
    if (kept > 0) first + " " + context.resources.getQuantityString(R.plurals.settings_cloud_disconnect_dialog_kept, kept, kept) else first

/**
 * Settings › Accounts: the cloud accounts ([CloudAccountCards]). Connect opens the provider's
 * sign-in (Google Drive: its picker); Disconnect asks first and says what leaves the library and
 * what stays. Renders nothing in a build without provider keys.
 */
@Composable
fun CloudAccountsSection(viewModel: CloudStorageViewModel) {
    if (!viewModel.available) return
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val pending by viewModel.pendingDisconnect.collectAsStateWithLifecycle()
    val browse by viewModel.browse.collectAsStateWithLifecycle()
    val driveUploads by viewModel.driveUploads.collectAsStateWithLifecycle()
    val saveAccount by viewModel.saveAccount.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val openGooglePicker = rememberGooglePicker(viewModel)
    CloudMessages(viewModel)

    SettingsSectionLabel(stringResource(R.string.settings_cloud_section_title))
    CloudAccountCards(
        providers = viewModel.providers,
        accounts = accounts,
        uploads = driveUploads,
        saveAccount = saveAccount,
        actions = CloudCardActions(
            usesPicker = viewModel::usesPicker,
            connect = { viewModel.connect(context, it) },
            openGooglePicker = openGooglePicker,
            disconnect = viewModel::requestDisconnect,
            addFolder = { viewModel.openBrowser(it.id, it.displayName) },
            setBackup = viewModel::setDriveBackup,
            setSaveAccount = viewModel::setSaveAccount,
            backUpExisting = viewModel::backUpExistingDownloads,
            retryUploads = viewModel::retryDriveUploads,
        ),
    )
    pending?.let { CloudDisconnectDialog(it, viewModel::confirmDisconnect, viewModel::cancelDisconnect) }
    browse?.let { CloudFolderPicker(it, viewModel) }
}

/** What the cards do, so [CloudAccountCards] draws without a ViewModel. */
internal class CloudCardActions(
    val usesPicker: (CloudProvider) -> Boolean,
    val connect: (CloudProvider) -> Unit,
    /** Google's picker for an account, or null for a new one (Google asks which). */
    val openGooglePicker: (Long?) -> Unit,
    val disconnect: (CloudAccount) -> Unit,
    val addFolder: (CloudAccount) -> Unit,
    val setBackup: (Long, Boolean) -> Unit,
    val setSaveAccount: (CloudAccount) -> Unit,
    val backUpExisting: (Long) -> Unit,
    val retryUploads: (Long) -> Unit,
)

/**
 * The cloud accounts, provider by provider: one card per connected account (each with its own
 * Reconnect and upload status), or one Connect card for a provider with none. A provider that signs
 * in through its picker (Google Drive) also offers another account; the rest don't yet (Dropbox has
 * no way to force its account chooser).
 *
 * @param uploads Each Google Drive account's upload status, by account id.
 * @param saveAccount The Drive account that takes saves and backup.
 */
@Composable
internal fun CloudAccountCards(
    providers: List<CloudProvider>,
    accounts: List<CloudAccountRow>,
    uploads: Map<Long, UploadStatus>,
    saveAccount: Long?,
    actions: CloudCardActions,
) {
    providers.forEach { provider ->
        val rows = accounts.filter { it.account.provider == provider }
        val onConnect: (Long?) -> Unit = { accountId ->
            // Google Drive's picker is its sign-in too (and its reconnect, for the same account).
            if (actions.usesPicker(provider)) actions.openGooglePicker(accountId) else actions.connect(provider)
        }
        if (rows.isEmpty()) {
            AccountConnectionCard(
                serviceName = provider.displayName,
                icon = ImageVector.vectorResource(logoOf(provider)),
                accentColor = accentOf(provider),
                authState = AuthState.NotConnected,
                onConnect = { onConnect(null) },
                onDisconnect = {},
            )
        }
        rows.forEach { row ->
            key(row.account.id) {
                CloudAccountCard(
                    row = row,
                    several = rows.size > 1,
                    uploads = uploads[row.account.id],
                    takesSaves = row.account.id == saveAccount,
                    onConnect = { onConnect(row.account.id) },
                    actions = actions,
                )
            }
        }
        if (rows.isNotEmpty() && actions.usesPicker(provider)) {
            SettingsGroupCard(
                rows = listOf(
                    {
                        SettingsNavRow(
                            title = stringResource(R.string.settings_cloud_gdrive_add_account_title),
                            subtitle = stringResource(R.string.settings_cloud_gdrive_add_account_subtitle),
                            leadingIcon = Icons.Rounded.PersonAdd,
                            onClick = { onConnect(null) },
                        )
                    },
                ),
            )
        }
    }
}

/** One connected account. With [several] of its provider, a letter tells it from the others. */
@Composable
private fun CloudAccountCard(
    row: CloudAccountRow,
    several: Boolean,
    uploads: UploadStatus?,
    takesSaves: Boolean,
    onConnect: () -> Unit,
    actions: CloudCardActions,
) {
    val account = row.account
    val provider = account.provider
    AccountConnectionCard(
        serviceName = provider.displayName,
        icon = ImageVector.vectorResource(logoOf(provider)),
        accentColor = accentOf(provider),
        authState = AuthState.Connected(UserInfo(account.id.toString(), account.label)),
        // A restored backup brings the account back without its sign-in: reconnect.
        expired = !row.signedIn,
        onConnect = onConnect,
        onDisconnect = { actions.disconnect(account) },
        leading = if (several) ({ AccountLetterAvatar(account.label, accentOf(provider)) }) else null,
        accountName = account.label.takeIf { several },
        extraContent = when {
            row.signedIn -> ({
                if (actions.usesPicker(provider)) {
                    SettingsNavRow(
                        title = stringResource(R.string.settings_cloud_gdrive_add_songs_title),
                        subtitle = stringResource(R.string.settings_cloud_gdrive_add_songs_subtitle),
                        leadingIcon = Icons.Rounded.LibraryAdd,
                        onClick = { actions.openGooglePicker(account.id) },
                    )
                    uploads?.let { DriveUploadRows(it, account, several, takesSaves, actions) }
                } else {
                    SettingsNavRow(
                        title = stringResource(R.string.settings_cloud_folder_add_title),
                        subtitle = stringResource(R.string.settings_cloud_folder_add_subtitle),
                        leadingIcon = Icons.Rounded.CreateNewFolder,
                        onClick = { actions.addFolder(account) },
                    )
                }
            })
            several && uploads != null && actions.usesPicker(provider) && (takesSaves || uploads.queued > 0) ->
                ({ SignedOutUploadRows(uploads, takesSaves) })
            else -> null
        },
    )
}

/**
 * A signed-out Drive card among several: nothing can be done here but Reconnect, yet it must not look
 * as if no account takes saves, or as if its songs had gone anywhere. The account chosen for saves
 * keeps its selected radio, and either way the card says what waits for that Reconnect. It offers
 * no radio otherwise: a song saved to it could only wait.
 */
@Composable
private fun SignedOutUploadRows(status: UploadStatus, takesSaves: Boolean) {
    // Not status.line, which blames the network for what waits: here the sign-in is what's missing.
    val line = if (status.queued > 0) {
        pluralStringResource(R.plurals.settings_cloud_gdrive_waiting_for_reconnect, status.queued, status.queued)
    } else {
        status.line
    }
    if (takesSaves) {
        SettingsPickerRow(
            selected = true,
            title = stringResource(R.string.settings_cloud_gdrive_save_here_title),
            subtitle = line,
            onClick = {},
            modifier = Modifier.semantics {
                selected = true
                role = Role.RadioButton
            },
        )
    } else if (line != null) {
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = SettingsRowPadH, vertical = SettingsRowPadV),
        )
    }
}

/**
 * The first letter of an account's email in the provider's colour. Telling two accounts apart is
 * all it does: a Google profile photo would need a new column for little more.
 */
@Composable
private fun AccountLetterAvatar(label: String, color: Color) {
    Box(
        modifier = Modifier.size(LogoSize).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
        )
    }
}

/** Asks before a disconnect, saying what leaves the library, what stays and what moves. */
@Composable
internal fun CloudDisconnectDialog(p: PendingCloudDisconnect, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        title = { Text(stringResource(R.string.settings_cloud_disconnect_dialog_title, p.name ?: p.provider.displayName), style = MaterialTheme.typography.titleLarge) },
        text = {
            val removedText = if (p.removed > 0) {
                pluralStringResource(R.plurals.settings_cloud_disconnect_dialog_removed, p.removed, p.removed, p.name ?: p.provider.displayName) + " "
            } else ""
            val keptText = if (p.kept > 0) {
                pluralStringResource(R.plurals.settings_cloud_disconnect_dialog_kept, p.kept, p.kept) + " "
            } else ""
            val gdriveText = if (p.provider == CloudProvider.GOOGLE_DRIVE) {
                stringResource(R.string.settings_cloud_disconnect_dialog_gdrive_pending) + " "
            } else ""
            val backupText = if (p.backupTurnsOff) {
                stringResource(R.string.settings_cloud_disconnect_dialog_backup_off) + " "
            } else ""
            val savesText = p.savesMoveTo?.let { stringResource(R.string.settings_cloud_disconnect_dialog_saves_move, it) + " " }.orEmpty()
            Text(
                buildString {
                    append(stringResource(R.string.settings_cloud_disconnect_dialog_body_intro)).append(" ")
                    append(removedText)
                    append(keptText)
                    append(gdriveText)
                    append(backupText)
                    append(savesText)
                    append(stringResource(R.string.settings_cloud_disconnect_dialog_untouched, p.provider.displayName))
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.settings_account_disconnect)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

/**
 * Settings › Library & Storage › Cloud folders: the picked folders with their song counts and
 * last scan, removal (asks first), adding another, and Rescan now.
 */
@Composable
fun CloudFoldersSection(viewModel: CloudStorageViewModel) {
    if (!viewModel.available) return
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    if (accounts.isEmpty()) return
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val browse by viewModel.browse.collectAsStateWithLifecycle()
    var confirmRemove by rememberSaveable { mutableStateOf<Long?>(null) }
    val openGooglePicker = rememberGooglePicker(viewModel)
    CloudMessages(viewModel)

    SettingsSectionLabel(stringResource(R.string.settings_cloud_folders_section_title))
    val rows = mutableListOf<@Composable () -> Unit>()
    folders.forEach { folder ->
        val provider = accounts.firstOrNull { it.account.id == folder.accountId }?.account?.provider
        // "Saved from Stash" isn't a folder the user picked: it can't be removed (it would come back).
        val removable = folder.itemId != GoogleDriveProvider.UPLOADS_ITEM_ID
        rows += { CloudFolderRow(folder, provider, onRemove = if (removable) ({ confirmRemove = folder.id }) else null) }
    }
    accounts.filter { it.signedIn }.forEach { row ->
        rows += {
            if (viewModel.usesPicker(row.account.provider)) {
                SettingsNavRow(
                    title = stringResource(R.string.settings_cloud_gdrive_add_songs_title),
                    // Two identical rows otherwise, with two accounts.
                    subtitle = row.account.label.takeIf { accounts.count { it.account.provider == row.account.provider } > 1 },
                    leadingIcon = Icons.Rounded.LibraryAdd,
                    onClick = { openGooglePicker(row.account.id) },
                )
            } else {
                SettingsNavRow(
                    title = if (accounts.size > 1) {
                        stringResource(R.string.settings_cloud_add_folder_from_provider, row.account.provider.displayName)
                    } else {
                        stringResource(R.string.settings_cloud_add_folder)
                    },
                    leadingIcon = Icons.Rounded.CreateNewFolder,
                    onClick = { viewModel.openBrowser(row.account.id, row.account.displayName) },
                )
            }
        }
    }
    if (folders.isNotEmpty()) {
        rows += {
            SettingsNavRow(
                title = stringResource(R.string.settings_cloud_rescan_title),
                subtitle = stringResource(R.string.settings_cloud_rescan_subtitle),
                leadingIcon = Icons.Rounded.Refresh,
                onClick = viewModel::rescan,
            )
        }
    }
    SettingsGroupCard(rows = rows)

    confirmRemove?.let { id ->
        val fallbackName = stringResource(R.string.settings_cloud_folder_fallback_name)
        val name = folders.firstOrNull { it.id == id }?.name ?: fallbackName
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            title = { Text(stringResource(R.string.settings_cloud_remove_folder_dialog_title, name), style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    stringResource(R.string.settings_cloud_remove_folder_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = null
                        viewModel.removeFolder(id)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.settings_web_link_action_remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
    browse?.let { CloudFolderPicker(it, viewModel) }
}

@Composable
private fun CloudFolderRow(folder: CloudFolderWithCount, provider: CloudProvider?, onRemove: (() -> Unit)?) {
    val status = when (folder.lastScanStatus) {
        CloudScanStatus.RUNNING.name -> pluralStringResource(
            R.plurals.settings_cloud_scan_running_songs_so_far,
            folder.trackCount,
            folder.trackCount,
        )
        CloudScanStatus.ERROR.name, CloudScanStatus.WAITING.name -> folder.lastScanError ?: stringResource(R.string.settings_cloud_scan_didnt_finish)
        else -> pluralStringResource(R.plurals.settings_cloud_folder_songs_count, folder.trackCount, folder.trackCount) +
            (folder.lastScanAt?.let { " · " + stringResource(R.string.settings_cloud_last_checked, relativeTime(it)) } ?: "")
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Google Drive's "folder" is the set of songs the user picked, not a place in the drive.
        val picked = provider == CloudProvider.GOOGLE_DRIVE
        Icon(
            if (picked) Icons.Rounded.LibraryMusic else Icons.Rounded.Folder,
            contentDescription = null,
            tint = provider?.let(::accentOf) ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (picked) folder.name else folder.path.ifEmpty { "/" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(provider?.displayName, status).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (folder.lastScanStatus == CloudScanStatus.ERROR.name) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = stringResource(R.string.settings_cloud_remove_folder_content_desc), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * A Google Drive card's Save to Drive rows: the backup switch with the upload status under it,
 * then "Back up N existing downloads" (while backup is on and some are waiting) or "Try again"
 * (after this Drive was full).
 *
 * With [several] accounts one takes saves and backup: a radio picks it, the backup switch lives on
 * its card, and the others say how their own uploads stand under their radio.
 */
@Composable
private fun DriveUploadRows(
    status: UploadStatus,
    account: CloudAccount,
    several: Boolean,
    takesSaves: Boolean,
    actions: CloudCardActions,
) {
    if (several) {
        SettingsPickerRow(
            selected = takesSaves,
            title = stringResource(R.string.settings_cloud_gdrive_save_here_title),
            subtitle = status.line.takeUnless { takesSaves },
            onClick = { if (!takesSaves) actions.setSaveAccount(account) },
            modifier = Modifier.semantics {
                selected = takesSaves
                role = Role.RadioButton
            },
        )
    }
    if (!several || takesSaves) {
        SettingsToggleRow(
            title = stringResource(R.string.settings_cloud_gdrive_backup_title),
            subtitle = status.line
                ?: stringResource(R.string.settings_cloud_gdrive_backup_default_subtitle),
            checked = status.backupOn,
            onCheckedChange = { actions.setBackup(account.id, it) },
        )
    }
    when {
        status.driveFull -> SettingsNavRow(
            title = stringResource(R.string.settings_cloud_backup_try_again_title),
            subtitle = stringResource(R.string.settings_cloud_backup_no_space_subtitle),
            leadingIcon = Icons.Rounded.Refresh,
            onClick = { actions.retryUploads(account.id) },
        )
        status.backupOn && status.existing > 0 -> SettingsNavRow(
            title = pluralStringResource(R.plurals.settings_cloud_backup_existing_title, status.existing, status.existing),
            subtitle = stringResource(R.string.settings_cloud_backup_backfill_subtitle),
            leadingIcon = Icons.Rounded.CloudUpload,
            onClick = { actions.backUpExisting(account.id) },
        )
    }
}

/** Walks the drive's folders; "Use this folder" picks the one you're in. */
@Composable
private fun CloudFolderPicker(state: CloudBrowseState, viewModel: CloudStorageViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::closeBrowser,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.trail.isNotEmpty()) {
                    IconButton(onClick = viewModel::up) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.settings_cloud_folder_picker_up_desc)) }
                }
                Column {
                    Text(state.current?.name ?: stringResource(R.string.settings_cloud_picker_default_title), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.accountName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        text = {
            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp)) {
                when {
                    state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 2.dp)
                    state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.Center))
                    state.entries.isEmpty() -> Text(
                        if (state.current == null) stringResource(R.string.settings_cloud_folder_picker_empty_root) else stringResource(R.string.settings_cloud_folder_picker_empty_subfolder),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(state.entries, key = { it.id }) { entry ->
                            Row(
                                Modifier.fillMaxWidth().clickable { viewModel.enter(entry) }.padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::pickCurrent, enabled = state.current != null) { Text(stringResource(R.string.settings_cloud_use_this_folder)) }
        },
        dismissButton = { TextButton(onClick = viewModel::closeBrowser) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

@Composable
private fun relativeTime(epochMs: Long): String {
    val minutes = (System.currentTimeMillis() - epochMs) / 60_000
    return when {
        minutes < 1 -> stringResource(R.string.settings_cloud_time_just_now)
        minutes < 60 -> pluralStringResource(R.plurals.settings_cloud_time_minutes_ago, minutes.toInt(), minutes.toInt())
        minutes < 48 * 60 -> {
            val hours = (minutes / 60).toInt()
            pluralStringResource(R.plurals.settings_cloud_time_hours_ago, hours, hours)
        }
        else -> {
            val days = (minutes / (24 * 60)).toInt()
            pluralStringResource(R.plurals.settings_cloud_time_days_ago, days, days)
        }
    }
}
