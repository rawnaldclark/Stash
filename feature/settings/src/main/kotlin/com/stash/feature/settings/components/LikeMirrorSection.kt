package com.stash.feature.settings.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.stash.feature.settings.R

/**
 * "Sync your likes" (Beta), one row per account: mirrors the Stash heart to
 * that service — symmetric (un-heart removes the remote Like), forward-only
 * (new likes only; no backfill). It sits inside the account's own card, so it
 * only shows while that account is connected and needs no "connect first" hint.
 * Turning it on goes through the [LikeMirrorWarningDialog] ack, which the
 * caller owns.
 *
 * Pure presentation; the caller owns all state.
 */
@Composable
fun LikeMirrorToggleRow(
    title: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsToggleRow(
        title = title,
        subtitle = stringResource(R.string.settings_like_mirror_row_subtitle),
        checked = checked,
        onCheckedChange = onToggle,
        modifier = modifier,
        titleTrailing = { BetaPill() },
    )
}
