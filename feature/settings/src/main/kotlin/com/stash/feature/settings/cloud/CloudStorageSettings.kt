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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.auth.model.AuthState
import com.stash.core.auth.model.UserInfo
import com.stash.core.data.db.dao.CloudFolderWithCount
import com.stash.core.data.db.entity.CloudScanStatus
import com.stash.core.model.CloudProvider
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

    SettingsSectionLabel("Cloud storage")
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
                            title = "Add songs from Google Drive",
                            subtitle = "Stash sees only the songs you pick.",
                            leadingIcon = Icons.Rounded.LibraryAdd,
                            onClick = { openGooglePicker(r.account.id) },
                        )
                        driveUploads?.let { DriveBackupRows(it, r.account.id, viewModel) }
                    } else {
                        SettingsNavRow(
                            title = "Add a music folder",
                            subtitle = "Read-only. Your files stay as they are.",
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
            title = { Text("Disconnect ${p.provider.displayName}?", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    buildString {
                        append("Stash forgets this sign-in on this phone. ")
                        append(
                            if (p.removed > 0) "${p.removed} songs that only stream from ${p.provider.displayName} leave your library, with their play history. "
                            else "",
                        )
                        if (p.kept > 0) append("${p.kept} downloaded songs stay on this phone. ")
                        if (p.provider == CloudProvider.GOOGLE_DRIVE) append("Songs waiting to be saved to it won't be. ")
                        append("Your files in ${p.provider.displayName} are not touched.")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmDisconnect,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelDisconnect) { Text("Cancel") } },
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

    SettingsSectionLabel("Cloud folders")
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
                    title = "Add songs from Google Drive",
                    leadingIcon = Icons.Rounded.LibraryAdd,
                    onClick = { openGooglePicker(row.account.id) },
                )
            } else {
                SettingsNavRow(
                    title = if (accounts.size > 1) "Add a folder from ${row.account.provider.displayName}" else "Add a folder",
                    leadingIcon = Icons.Rounded.CreateNewFolder,
                    onClick = { viewModel.openBrowser(row.account.id, row.account.displayName) },
                )
            }
        }
    }
    if (folders.isNotEmpty()) {
        rows += {
            SettingsNavRow(
                title = "Rescan now",
                subtitle = "Picks up songs added, changed or removed in your cloud. Stash also checks twice a day.",
                leadingIcon = Icons.Rounded.Refresh,
                onClick = viewModel::rescan,
            )
        }
    }
    SettingsGroupCard(rows = rows)

    confirmRemove?.let { id ->
        val name = folders.firstOrNull { it.id == id }?.name ?: "this folder"
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            title = { Text("Remove $name?", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "Its songs leave your library, except the ones you downloaded, which stay on this phone. " +
                        "Nothing is deleted from your cloud.",
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
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }
    browse?.let { CloudFolderPicker(it, viewModel) }
}

@Composable
private fun CloudFolderRow(folder: CloudFolderWithCount, provider: CloudProvider?, onRemove: (() -> Unit)?) {
    val status = when (folder.lastScanStatus) {
        CloudScanStatus.RUNNING.name -> "Scanning… ${folder.trackCount} songs so far"
        CloudScanStatus.ERROR.name, CloudScanStatus.WAITING.name -> folder.lastScanError ?: "The last scan didn't finish"
        else -> "${folder.trackCount} songs" + (folder.lastScanAt?.let { " · checked ${relativeTime(it)}" } ?: "")
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
                Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = "Remove folder", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
        title = "Back up my downloads to Google Drive",
        subtitle = status.line
            ?: "New downloads are saved to a Stash folder in your Drive. Like syncing, this waits for Wi-Fi when Sync is set to Wi-Fi only.",
        checked = status.backupOn,
        onCheckedChange = { viewModel.setDriveBackup(accountId, it) },
    )
    when {
        status.driveFull -> SettingsNavRow(
            title = "Try again",
            subtitle = "Once there's space in your Google Drive.",
            leadingIcon = Icons.Rounded.Refresh,
            onClick = viewModel::retryDriveUploads,
        )
        status.backupOn && status.existing > 0 -> SettingsNavRow(
            title = if (status.existing == 1) "Back up 1 existing download" else "Back up ${status.existing} existing downloads",
            subtitle = "Downloaded before backup was on.",
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
                    IconButton(onClick = viewModel::up) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Up") }
                }
                Column {
                    Text(state.current?.name ?: "Pick your music folder", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        if (state.current == null) "No folders here." else "No folders inside. Use this one?",
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
            TextButton(onClick = viewModel::pickCurrent, enabled = state.current != null) { Text("Use this folder") }
        },
        dismissButton = { TextButton(onClick = viewModel::closeBrowser) { Text("Cancel") } },
    )
}

private fun relativeTime(epochMs: Long): String {
    val minutes = (System.currentTimeMillis() - epochMs) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}
