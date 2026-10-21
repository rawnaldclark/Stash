package com.stash.feature.settings

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.stash.feature.settings.components.SettingsRowPadH
import com.stash.feature.settings.components.SettingsRowPadV
import java.text.SimpleDateFormat
import java.util.Date

/**
 * "Re-check library for word-synced lyrics" (Settings → Playback → Lyrics). The only way the app
 * walks the whole library for lyrics: nothing does it automatically. Idle, a tap opens a confirm
 * dialog (it can take hours and leans on free lyrics services) and only then starts the sweep.
 * While it waits or runs, the row shows where it is and a Cancel button instead.
 */
@Composable
internal fun LyricsRecheckRow(
    state: LyricsRecheckUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val idle = state is LyricsRecheckUiState.Idle

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = idle, role = Role.Button) { confirming = true }
            .padding(horizontal = SettingsRowPadH, vertical = SettingsRowPadV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_lyrics_recheck_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_lyrics_recheck_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RecheckStatusLine(state)
        }
        if (!idle) {
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_cancel)) }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            title = {
                Text(
                    text = stringResource(R.string.settings_lyrics_recheck_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.settings_lyrics_recheck_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onStart()
                    },
                ) { Text(stringResource(R.string.settings_lyrics_recheck_dialog_confirm)) }
            },
        )
    }
}

/** The row's third line: progress while it runs, else what the last sweep came to (if anything). */
@Composable
private fun RecheckStatusLine(state: LyricsRecheckUiState) {
    when (state) {
        is LyricsRecheckUiState.Checking -> {
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (state.total > 0) {
                    stringResource(R.string.settings_lyrics_recheck_progress, state.done, state.total)
                } else {
                    stringResource(R.string.settings_lyrics_recheck_starting)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            if (state.total > 0) {
                LinearProgressIndicator(
                    progress = { (state.done.toFloat() / state.total.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        LyricsRecheckUiState.Waiting -> StatusText(stringResource(R.string.settings_lyrics_recheck_waiting))
        is LyricsRecheckUiState.Idle -> when {
            state.stoppedEarly -> StatusText(
                stringResource(R.string.settings_lyrics_recheck_stopped_early),
                color = MaterialTheme.colorScheme.error,
            )
            state.partway -> StatusText(stringResource(R.string.settings_lyrics_recheck_partway))
            state.lastCheckedAtMs != null -> StatusText(
                stringResource(R.string.settings_lyrics_recheck_last_checked, shortDate(state.lastCheckedAtMs)),
            )
            else -> Unit
        }
    }
}

@Composable
private fun StatusText(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Spacer(Modifier.height(4.dp))
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
}

/** "Oct 21" in the user's locale (month + day; the year is rarely worth the width here). */
@Composable
private fun shortDate(epochMs: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale) }
    return format.format(Date(epochMs))
}
