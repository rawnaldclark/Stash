package com.stash.feature.settings.cloud

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryMusic
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.auth.model.AuthState
import com.stash.core.auth.model.UserInfo
import com.stash.core.data.db.dao.CloudFolderWithCount
import com.stash.core.data.db.entity.CloudScanStatus
import com.stash.core.model.CloudProvider
import com.stash.feature.settings.R
import com.stash.feature.settings.components.AccountConnectionCard
import com.stash.feature.settings.components.SettingsGroupCard
import com.stash.feature.settings.components.SettingsNavRow
import com.stash.feature.settings.components.SettingsSectionLabel
import com.stash.feature.settings.components.SettingsToggleRow
import com.stash.data.cloud.provider.gdrive.GoogleDriveProvider
import com.stash.data.cloud.upload.UploadStatus

/** Brand accents, as the Discord card does inline. */
private fun accentOf(provider: CloudProvider): Color = when (provider) {
    CloudProvider.DROPBOX -> Color(0xFF0061FE)
    CloudProvider.ONEDRIVE -> Color(0xFF0078D4)
    CloudProvider.GOOGLE_DRIVE -> Color(0xFF1FA463)
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

/** Shows [CloudStorageViewModel.messages] as toasts while the screen is on. */
@Composable
private fun CloudMessages(viewModel: CloudStorageViewModel) {
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
}

/**
 * Settings › Accounts: one card per cloud provider this build offers (Dropbox, OneDrive).
 * Connect opens the provider's sign-in in a Custom Tab; Disconnect asks first and says what
 * leaves the library and what stays. Renders nothing in a build without provider keys.
 */
@Composable
fun CloudAccountsSection(viewModel: CloudStorageViewModel) {
    if (!viewModel.available) return
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val pending by viewModel.pendingDisconnect.collectAsStateWithLifecycle()
    val browse by viewModel.browse.collectAsStateWithLifecycle()
    val driveUploads by viewModel.driveUploads.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val openGooglePicker = rememberGooglePicker(viewModel)
    CloudMessages(viewModel)

    SettingsSectionLabel(stringResource(R.string.settings_cloud_section_title))
    viewModel.providers.forEach { provider ->
        val row = accounts.firstOrNull { it.account.provider == provider }
        AccountConnectionCard(
            serviceName = provider.displayName,
            icon = Icons.Rounded.Cloud,
            accentColor = accentOf(provider),
            authState = row?.let { AuthState.Connected(UserInfo(it.account.id.toString(), it.account.email ?: it.account.displayName)) }
                ?: AuthState.NotConnected,
            // A restored backup brings the account back without its sign-in: reconnect.
            expired = row != null && !row.signedIn,
            onConnect = {
                // Google Drive's picker is its sign-in too (and its reconnect, for the same account).
                if (viewModel.usesPicker(provider)) openGooglePicker(row?.account?.id)
                else viewModel.connect(context, provider)
            },
            onDisconnect = { row?.let { viewModel.requestDisconnect(it.account) } },
            extraContent = row?.takeIf { it.signedIn }?.let { r ->
                {
                    if (viewModel.usesPicker(provider)) {
                        SettingsNavRow(
                            title = stringResource(R.string.settings_cloud_gdrive_add_songs_title),
                            subtitle = stringResource(R.string.settings_cloud_gdrive_add_songs_subtitle),
                            leadingIcon = Icons.Rounded.LibraryAdd,
                            onClick = { openGooglePicker(r.account.id) },
                        )
                        driveUploads?.let { DriveBackupRows(it, r.account.id, viewModel) }
                    } else {
                        SettingsNavRow(
                            title = stringResource(R.string.settings_cloud_folder_add_title),
                            subtitle = stringResource(R.string.settings_cloud_folder_add_subtitle),
                            leadingIcon = Icons.Rounded.CreateNewFolder,
                            onClick = { viewModel.openBrowser(r.account.id, r.account.displayName) },
                        )
                    }
                }
            },
        )
    }

    pending?.let { p ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDisconnect,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            title = { Text(stringResource(R.string.settings_cloud_disconnect_dialog_title, p.provider.displayName), style = MaterialTheme.typography.titleLarge) },
            text = {
                val removedText = if (p.removed > 0) {
                    pluralStringResource(R.plurals.settings_cloud_disconnect_dialog_removed, p.removed, p.removed, p.provider.displayName) + " "
                } else ""
                val keptText = if (p.kept > 0) {
                    pluralStringResource(R.plurals.settings_cloud_disconnect_dialog_kept, p.kept, p.kept) + " "
                } else ""
                val gdriveText = if (p.provider == CloudProvider.GOOGLE_DRIVE) {
                    stringResource(R.string.settings_cloud_disconnect_dialog_gdrive_pending) + " "
                } else ""
                Text(
                    buildString {
                        append(stringResource(R.string.settings_cloud_disconnect_dialog_body_intro)).append(" ")
                        append(removedText)
                        append(keptText)
                        append(gdriveText)
                        append(stringResource(R.string.settings_cloud_disconnect_dialog_untouched, p.provider.displayName))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmDisconnect,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.settings_account_disconnect)) }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelDisconnect) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
    browse?.let { CloudFolderPicker(it, viewModel) }
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
 * The Google Drive card's Save to Drive rows: the backup switch with the upload status under it,
 * then "Back up N existing downloads" (while backup is on and some are waiting) or "Try again"
 * (after Drive was full).
 */
@Composable
private fun DriveBackupRows(status: UploadStatus, accountId: Long, viewModel: CloudStorageViewModel) {
    SettingsToggleRow(
        title = stringResource(R.string.settings_cloud_gdrive_backup_title),
        subtitle = status.line
            ?: stringResource(R.string.settings_cloud_gdrive_backup_default_subtitle),
        checked = status.backupOn,
        onCheckedChange = { viewModel.setDriveBackup(accountId, it) },
    )
    when {
        status.driveFull -> SettingsNavRow(
            title = stringResource(R.string.settings_cloud_backup_try_again_title),
            subtitle = stringResource(R.string.settings_cloud_backup_no_space_subtitle),
            leadingIcon = Icons.Rounded.Refresh,
            onClick = viewModel::retryDriveUploads,
        )
        status.backupOn && status.existing > 0 -> SettingsNavRow(
            title = pluralStringResource(R.plurals.settings_cloud_backup_existing_title, status.existing, status.existing),
            subtitle = stringResource(R.string.settings_cloud_backup_backfill_subtitle),
            leadingIcon = Icons.Rounded.CloudUpload,
            onClick = viewModel::backUpExistingDownloads,
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
