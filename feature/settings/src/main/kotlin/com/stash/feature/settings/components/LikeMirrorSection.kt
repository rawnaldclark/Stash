package com.stash.feature.settings.components

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.stash.core.data.social.LikeMirrorError
import com.stash.core.data.social.LikeMirrorStatus
import com.stash.feature.settings.R

/**
 * "Like on … too" / "Love on Last.fm too", one row per account: mirrors the Stash heart to that service, symmetric (un-heart
 * removes a Like Stash made there, never one that was already there) and forward-only (new likes only; no backfill). It sits
 * inside the account's own card, so it only shows while that account is connected and needs no "connect first" hint. Turning
 * Spotify or YouTube Music on goes through the [LikeMirrorWarningDialog] ack, which the caller owns.
 *
 * While the switch is on, a line under it says how the likes are getting there ([status]): "Up to date", "3 waiting · retrying",
 * or, when the listener has to act, "Last error: sign-in expired · tap to retry" ([onRetry]).
 *
 * Pure presentation; the caller owns all state.
 */
@Composable
fun LikeMirrorToggleRow(
    title: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    status: LikeMirrorStatus? = null,
    onRetry: () -> Unit = {},
) {
    SettingsToggleRow(
        title = title,
        subtitle = stringResource(R.string.settings_like_mirror_row_subtitle),
        checked = checked,
        onCheckedChange = onToggle,
        modifier = modifier,
        supporting = if (checked && status != null) {
            { LikeMirrorStatusLine(status, onRetry) }
        } else {
            null
        },
    )
}

/** The status line itself: quiet while things flow, the error colour and tappable when the listener has to act. */
@Composable
private fun LikeMirrorStatusLine(status: LikeMirrorStatus, onRetry: () -> Unit) {
    val needsYou = status.state == LikeMirrorStatus.State.NEEDS_YOU
    val text = LikeMirrorStatusText.of(status, LocalContext.current.resources)
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (needsYou) MaterialTheme.colorScheme.error else statusColor(status.state),
        modifier = Modifier
            .then(if (needsYou) Modifier.clickable(role = Role.Button, onClick = onRetry) else Modifier)
            .padding(top = 2.dp),
    )
}

@Composable
private fun statusColor(state: LikeMirrorStatus.State): Color = when (state) {
    LikeMirrorStatus.State.UP_TO_DATE -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** The words for a [LikeMirrorStatus], in the screen's language. Kept apart from Compose so a test can read them. */
object LikeMirrorStatusText {
    fun of(status: LikeMirrorStatus, res: Resources): String = when (status.state) {
        LikeMirrorStatus.State.UP_TO_DATE -> res.getString(R.string.settings_like_status_up_to_date)
        LikeMirrorStatus.State.WAITING -> res.getString(R.string.settings_like_status_waiting, status.waiting)
        LikeMirrorStatus.State.OFFLINE -> res.getString(R.string.settings_like_status_offline, status.waiting)
        LikeMirrorStatus.State.RETRYING -> res.getString(R.string.settings_like_status_retrying, status.waiting)
        LikeMirrorStatus.State.NEEDS_YOU ->
            res.getString(R.string.settings_like_status_needs_you, reason(status.error ?: LikeMirrorError.REFUSED, res))
    }

    private fun reason(error: LikeMirrorError, res: Resources): String = res.getString(
        when (error) {
            LikeMirrorError.SIGNED_OUT -> R.string.settings_like_error_signed_out
            LikeMirrorError.RATE_LIMITED -> R.string.settings_like_error_rate_limited
            LikeMirrorError.OFFLINE -> R.string.settings_like_error_offline
            LikeMirrorError.REFUSED, LikeMirrorError.PERMANENT -> R.string.settings_like_error_refused
        },
    )
}
