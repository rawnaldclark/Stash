package com.stash.feature.settings.spotify

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stash.core.data.sync.MAX_EXTRA_SPOTIFY_ACCOUNTS
import com.stash.core.ui.components.sheetBarsFollow
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.settings.R
import com.stash.feature.settings.cloud.rememberCloudMessages
import com.stash.feature.settings.components.AccountCard
import com.stash.feature.settings.components.AccountConnectButton
import com.stash.feature.settings.components.AccountLetterAvatar
import com.stash.feature.settings.components.AccountStatusTone
import com.stash.feature.settings.components.SettingsGroupCard
import com.stash.feature.settings.components.SettingsNavRow
import com.stash.feature.settings.components.SettingsRowPadH
import com.stash.feature.settings.components.SettingsRowPadV
import com.stash.feature.settings.components.SettingsToggleRow
import com.stash.feature.settings.components.SettingsValueRow
import com.stash.core.ui.R as UiR

/**
 * Settings › Accounts › Spotify, under the connected account's card: one card per other Spotify
 * account (plan 2026-11-04 §3), then "Add another Spotify account". Laid out as Cloud storage lays
 * out several Google accounts: a card each, a letter avatar to tell them apart, and the add row
 * as its own group under them.
 *
 * Each card says the account is read-only and who hearts go to; one whose sign-in is gone leads
 * with "Sign in again" instead. Tapping a card opens its sheet ([OtherAccountSheet]).
 *
 * @param mainName The connected account's name (hearts go to it); null while none is connected.
 * @param canAdd Shows the add row: a main account is connected and there is room.
 */
@Composable
internal fun SpotifyOtherAccountCards(
    accounts: List<OtherSpotifyAccount>,
    mainName: String?,
    canAdd: Boolean,
    onOpen: (Long) -> Unit,
    onSignInAgain: (Long) -> Unit,
    onAdd: () -> Unit,
) {
    accounts.forEach { account ->
        key(account.id) {
            OtherAccountCard(account, mainName, onOpen = { onOpen(account.id) }, onSignInAgain = { onSignInAgain(account.id) })
        }
    }
    if (canAdd) {
        SettingsGroupCard(
            rows = listOf(
                {
                    SettingsNavRow(
                        title = stringResource(R.string.settings_spotify_others_add_title),
                        subtitle = mainName?.let { stringResource(R.string.settings_spotify_others_add_subtitle, it) }
                            ?: stringResource(R.string.settings_spotify_others_add_subtitle_unnamed),
                        leadingIcon = Icons.Rounded.PersonAdd,
                        onClick = onAdd,
                    )
                },
            ),
        )
    }
}

/** The line under an other account's name, and its colour. */
@Composable
private fun otherAccountStatus(account: OtherSpotifyAccount, mainName: String?): Pair<String, AccountStatusTone> = when {
    account.needsSignIn -> stringResource(R.string.settings_spotify_others_signed_out) to AccountStatusTone.EXPIRED
    mainName != null -> stringResource(R.string.settings_spotify_others_read_only, mainName) to AccountStatusTone.NEUTRAL
    else -> stringResource(R.string.settings_spotify_others_read_only_unnamed) to AccountStatusTone.NEUTRAL
}

/** One other account: the account card's shell, the whole of it a tap to its sheet. */
@Composable
private fun OtherAccountCard(
    account: OtherSpotifyAccount,
    mainName: String?,
    onOpen: () -> Unit,
    onSignInAgain: () -> Unit,
) {
    val green = StashTheme.extendedColors.spotifyGreen
    val (status, tone) = otherAccountStatus(account, mainName)
    AccountCard(
        serviceName = account.name,
        icon = ImageVector.vectorResource(UiR.drawable.ic_brand_spotify),
        accentColor = green,
        statusText = status,
        statusTone = tone,
        // Clipped first, so the press ripple keeps to the card's rounded corners.
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .clickable(
                onClickLabel = stringResource(R.string.settings_spotify_others_open),
                role = Role.Button,
                onClick = onOpen,
            ),
        leading = { AccountLetterAvatar(account.name, green) },
    ) {
        if (account.needsSignIn) {
            AccountConnectButton(green, onSignInAgain, label = stringResource(R.string.settings_spotify_others_sign_in_again))
        } else {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = StashTheme.extendedColors.textTertiary,
            )
        }
    }
}

/**
 * Everything the other accounts open over the Accounts page: an account's sheet and its rename
 * and remove dialogs, a refused sign-in's explanation, the new account's name, "Sync now?", and
 * the short messages. The sign-in screen and the paste dialog are the page's own (it gives the
 * whole screen to the sign-in), see `SettingsAccountsScreen`.
 */
@Composable
internal fun SpotifyOtherAccountsOverlays(
    viewModel: SpotifyAccountsViewModel,
    state: OtherAccountsUiState,
    accounts: List<OtherSpotifyAccount>,
    mainName: String?,
) {
    val context = LocalContext.current
    val show = rememberCloudMessages()
    LaunchedEffect(viewModel, show) {
        viewModel.notices.collect { show(it.text(context)) }
    }

    state.openAccountId?.let { id ->
        // Gone meanwhile (removed): the sheet closes itself.
        val account = accounts.firstOrNull { it.id == id }
        if (account != null) {
            OtherAccountSheet(
                account = account,
                mainName = mainName,
                onRename = { viewModel.startRename(id) },
                onSignInAgain = { viewModel.startSignInAgain(id) },
                onSetPlaylists = { viewModel.setSyncPlaylists(id, it) },
                onSetLiked = { viewModel.setSyncLiked(id, it) },
                onSetAlbums = { viewModel.setSyncAlbums(id, it) },
                onSetMixes = { viewModel.setSyncMixes(id, it) },
                onRemove = { viewModel.requestRemove(id) },
                onDismiss = viewModel::closeAccount,
            )
        }
    }

    state.renamingId?.let { id ->
        accounts.firstOrNull { it.id == id }?.let { account ->
            OtherAccountNameDialog(
                title = stringResource(R.string.settings_spotify_others_rename_title),
                initial = account.name,
                dismissLabel = stringResource(R.string.settings_cancel),
                onSave = { viewModel.rename(id, it) },
                onDismiss = viewModel::cancelRename,
            )
        }
    }

    state.confirmRemoveId?.let { id ->
        val name = accounts.firstOrNull { it.id == id }?.name.orEmpty()
        AlertDialog(
            onDismissRequest = viewModel::cancelRemove,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            title = { Text(stringResource(R.string.settings_spotify_others_remove_title, name), style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    stringResource(R.string.settings_spotify_others_remove_body, name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmRemove,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.settings_spotify_others_remove_confirm)) }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelRemove) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }

    state.refusal?.let { refusal ->
        val (title, body) = refusal.words()
        SpotifyNoticeDialog(title = title, body = body, onDismiss = viewModel::dismissRefusal)
    }

    state.naming?.let { naming ->
        OtherAccountNameDialog(
            title = stringResource(R.string.settings_spotify_others_name_title),
            initial = naming.suggested,
            dismissLabel = stringResource(R.string.settings_spotify_others_name_skip),
            onSave = viewModel::confirmName,
            onDismiss = viewModel::skipName,
        )
    }

    state.offerSyncNow?.let { name ->
        AlertDialog(
            onDismissRequest = viewModel::syncLater,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            title = { Text(stringResource(R.string.settings_spotify_others_sync_now_title), style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    stringResource(R.string.settings_spotify_others_sync_now_body, name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = { TextButton(onClick = viewModel::syncNow) { Text(stringResource(R.string.settings_spotify_others_sync_now)) } },
            dismissButton = { TextButton(onClick = viewModel::syncLater) { Text(stringResource(R.string.settings_spotify_others_sync_later)) } },
        )
    }
}

/**
 * Says why something about a Spotify account didn't happen (nothing changed), with one OK: as the
 * Accounts page's other refusals are drawn (a different account's sign-in, a YouTube error).
 */
@Composable
internal fun SpotifyNoticeDialog(title: String, body: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_ok)) } },
    )
}

/** A refusal's title and body. */
@Composable
private fun OtherAccountRefusal.words(): Pair<String, String> = when (this) {
    is OtherAccountRefusal.AlreadyConnected -> stringResource(R.string.settings_spotify_others_already_connected_title) to (
        mainName?.let { stringResource(R.string.settings_spotify_others_already_connected_body, it) }
            ?: stringResource(R.string.settings_spotify_others_already_connected_body_unnamed)
        )
    is OtherAccountRefusal.MainUnchecked -> stringResource(R.string.settings_spotify_others_main_unchecked_title) to (
        mainName?.let { stringResource(R.string.settings_spotify_others_main_unchecked_body, it) }
            ?: stringResource(R.string.settings_spotify_others_main_unchecked_body_unnamed)
        )
    OtherAccountRefusal.Unidentified -> stringResource(R.string.settings_spotify_others_unidentified_title) to
        stringResource(R.string.settings_spotify_others_unidentified_body)
    is OtherAccountRefusal.DifferentAccount -> stringResource(R.string.settings_spotify_other_account_title) to
        stringResource(R.string.settings_spotify_others_different_body, name)
    OtherAccountRefusal.Full -> stringResource(R.string.settings_spotify_others_full_title) to
        stringResource(R.string.settings_spotify_others_full_body, MAX_EXTRA_SPOTIFY_ACCOUNTS)
}

/** An [OtherAccountNotice] in words. */
internal fun OtherAccountNotice.text(context: Context): String = when (this) {
    is OtherAccountNotice.SignedInAgain -> context.getString(R.string.settings_spotify_others_signed_in_again, name)
    is OtherAccountNotice.Removed -> context.getString(R.string.settings_spotify_others_removed, name)
    OtherAccountNotice.AlreadyRemoved -> context.getString(R.string.settings_spotify_others_already_removed)
    is OtherAccountNotice.NotRemoved -> context.getString(R.string.settings_spotify_others_not_removed, name)
    is OtherAccountNotice.RemoveUnfinished -> context.getString(R.string.settings_spotify_others_remove_unfinished, name)
    is OtherAccountNotice.AddedForFirstSync -> context.getString(R.string.settings_spotify_others_added_first_sync, name)
    OtherAccountNotice.Syncing -> context.getString(R.string.settings_spotify_others_syncing)
}

/** The paste dialog's error line for [error]. */
@Composable
internal fun otherAccountErrorText(error: OtherAccountSignInError): String = when (error) {
    OtherAccountSignInError.INVALID -> stringResource(R.string.settings_spotify_others_error_invalid)
    OtherAccountSignInError.NOT_SAVED -> stringResource(R.string.settings_spotify_others_error_not_saved)
}

/**
 * An other account's sheet: who it is, Sign in again (when needed), its name, what it syncs, and
 * Remove. Drawn as the other Settings sheets are ([com.stash.feature.settings.components.SettingsChoiceSheet]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OtherAccountSheet(
    account: OtherSpotifyAccount,
    mainName: String?,
    onRename: () -> Unit,
    onSignInAgain: () -> Unit,
    onSetPlaylists: (Boolean) -> Unit,
    onSetLiked: (Boolean) -> Unit,
    onSetAlbums: (Boolean) -> Unit,
    onSetMixes: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val extended = StashTheme.extendedColors
    val (status, tone) = otherAccountStatus(account, mainName)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = extended.elevatedSurface,
        properties = sheetBarsFollow(extended.elevatedSurface),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsRowPadH + 5.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccountLetterAvatar(account.name, extended.spotifyGreen)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = account.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (tone == AccountStatusTone.EXPIRED) extended.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (account.needsSignIn) {
                    Spacer(Modifier.width(8.dp))
                    AccountConnectButton(extended.spotifyGreen, onSignInAgain, label = stringResource(R.string.settings_spotify_others_sign_in_again))
                }
            }
            Column(Modifier.padding(horizontal = 5.dp, vertical = 8.dp)) {
                SettingsValueRow(
                    title = stringResource(R.string.settings_spotify_others_name_row),
                    value = account.name,
                    onClick = onRename,
                )
                Text(
                    text = stringResource(R.string.settings_spotify_others_sync_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = SettingsRowPadH, end = SettingsRowPadH, top = 12.dp, bottom = 2.dp),
                )
                SettingsToggleRow(
                    title = stringResource(R.string.settings_spotify_others_playlists_title),
                    subtitle = stringResource(R.string.settings_spotify_others_playlists_subtitle),
                    checked = account.syncPlaylists,
                    onCheckedChange = onSetPlaylists,
                )
                SettingsToggleRow(
                    title = stringResource(R.string.settings_spotify_others_liked_title),
                    subtitle = stringResource(R.string.settings_spotify_others_liked_subtitle),
                    checked = account.syncLiked,
                    onCheckedChange = onSetLiked,
                )
                SettingsToggleRow(
                    title = stringResource(R.string.settings_spotify_others_albums_title),
                    subtitle = stringResource(R.string.settings_spotify_others_albums_subtitle),
                    checked = account.syncAlbums,
                    onCheckedChange = onSetAlbums,
                )
                SettingsToggleRow(
                    title = stringResource(R.string.settings_spotify_others_mixes_title),
                    subtitle = stringResource(R.string.settings_spotify_others_mixes_subtitle),
                    checked = account.syncMixes,
                    onCheckedChange = onSetMixes,
                )
                // Quiet, at the bottom, and it asks first: as Disconnect is on the account cards.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clickable(role = Role.Button, onClick = onRemove)
                        .padding(horizontal = SettingsRowPadH, vertical = SettingsRowPadV),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.RemoveCircleOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.settings_spotify_others_remove, account.name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * Names an account: right after it is added (pre-filled with Spotify's name; [dismissLabel]
 * "Skip" keeps that), or later from its sheet. As Your Stash's rename of a linked device.
 */
@Composable
private fun OtherAccountNameDialog(
    title: String,
    initial: String,
    dismissLabel: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(SpotifyAccountsViewModel.MAX_NAME_LENGTH) },
                    singleLine = true,
                )
                Text(
                    stringResource(R.string.settings_spotify_others_name_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

/**
 * Disconnect on the Spotify card while other accounts are added (plan 2026-11-04 §3.5, v1): every
 * Spotify account goes, the other accounts each as their own Remove does. Drawn as the other
 * Disconnect confirmations ([com.stash.feature.settings.components.DisconnectAccountDialog]).
 *
 * @param names Every Spotify account's name, the connected one first.
 * @param note One more thing this disconnect means, after the usual text (Refresh mode), if any.
 */
@Composable
internal fun DisconnectAllSpotifyDialog(
    names: List<String>,
    note: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        title = { Text(stringResource(R.string.settings_spotify_disconnect_all_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            val body = stringResource(R.string.settings_spotify_disconnect_all_body, names.joinToString(", "))
            Text(
                if (note == null) body else "$body\n\n$note",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.settings_spotify_disconnect_all_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}
