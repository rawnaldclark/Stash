package com.stash.feature.sync.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stash.core.data.sync.SyncPhase
import com.stash.feature.sync.R

/**
 * Multi-phase sync progress widget shown inside [SyncHeroCard] while a sync
 * is running.  Extracted from the old SyncActionSection so the hero card
 * can embed it without owning the Sync Now button logic.
 *
 * @param phase      Current [SyncPhase] (drives the status label).
 * @param progress   0f–1f overall progress, forwarded to the linear indicator.
 * @param onStopSync Invoked when the user taps "Stop sync" (or "Cancel" while a
 *                   "Sync now" is still waiting for a network).
 */
@Composable
fun SyncActionProgress(
    phase: SyncPhase,
    progress: Float,
    onStopSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Disabled "Syncing…" row mirrors the current phase label. A solid container under its own
        // ink: onPrimary on a 40% primary read ~2.7:1 once onPrimary turned near-black (D9).
        Button(
            onClick = { /* disabled while syncing */ },
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                disabledContainerColor = MaterialTheme.colorScheme.primaryContainer,
                disabledContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            if (phase is SyncPhase.WaitingForNetwork) {
                // Nothing is running yet: no spinner, which would read as work.
                Icon(
                    imageVector = Icons.Filled.CloudOff,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    strokeWidth = 2.dp,
                )
            }
            Spacer(Modifier.width(8.dp))
            val context = LocalContext.current
            Text(
                text = phaseLabel(phase, context),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        // Distinct error-coloured Stop button so the user can always bail out.
        Button(
            onClick = onStopSync,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.PauseCircle,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                // A sync still waiting for a network has nothing to stop: it's a queued tap.
                text = stringResource(
                    if (phase is SyncPhase.WaitingForNetwork) R.string.sync_cancel
                    else R.string.sync_action_stop_sync
                ),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

/** Human-readable label for the current sync phase. */
internal fun phaseLabel(phase: SyncPhase, context: Context? = null): String = when (phase) {
    // "Sync now" tapped offline: it starts by itself when a connection returns.
    is SyncPhase.WaitingForNetwork ->
        context?.getString(R.string.sync_phase_waiting_network) ?: "Waiting for network..."
    is SyncPhase.Authenticating ->
        context?.getString(R.string.sync_phase_authenticating) ?: "Authenticating..."
    is SyncPhase.FetchingPlaylists -> {
        // No fixed total is known during fetch (Spotify's folder-walked
        // playlist count and YouTube's aren't known until the fetch itself
        // finishes) — so this is a live running count, not a fraction.
        // Still gives the user visible proof of forward progress on a
        // long fetch instead of a static "Fetching playlists..." that
        // looked frozen for 40+ minutes on large libraries.
        if (phase.playlistsFetched > 0) {
            context?.resources?.getQuantityString(
                R.plurals.sync_phase_fetching_playlists,
                phase.playlistsFetched,
                phase.playlistsFetched,
            ) ?: "Fetched ${phase.playlistsFetched} playlist${if (phase.playlistsFetched != 1) "s" else ""}..."
        } else {
            context?.getString(R.string.sync_phase_fetching_playlists_init) ?: "Fetching playlists..."
        }
    }
    is SyncPhase.Diffing -> {
        if (phase.totalPlaylists > 0) {
            context?.getString(
                R.string.sync_phase_comparing_changes_count,
                phase.playlistsDiffed,
                phase.totalPlaylists,
            ) ?: "Comparing changes (${phase.playlistsDiffed}/${phase.totalPlaylists})..."
        } else {
            context?.getString(R.string.sync_phase_comparing_changes) ?: "Comparing changes..."
        }
    }

    is SyncPhase.VerifyingLibrary ->
        context?.getString(
            R.string.sync_phase_verifying_library,
            phase.step,
            phase.total,
        ) ?: "Checking your library (${phase.step}/${phase.total})..."
    is SyncPhase.Downloading ->
        if (phase.deferred > 0) {
            context?.getString(
                R.string.sync_phase_downloading_deferred,
                phase.downloaded,
                phase.total,
                phase.deferred,
            ) ?: "Downloading ${phase.downloaded}/${phase.total} · ${phase.deferred} waiting..."
        } else {
            context?.getString(
                R.string.sync_phase_downloading_progress,
                phase.downloaded,
                phase.total,
            ) ?: "Downloading ${phase.downloaded}/${phase.total}..."
        }
    is SyncPhase.Finalizing ->
        context?.getString(R.string.sync_phase_finalizing) ?: "Finalizing..."
    is SyncPhase.Completed ->
        context?.getString(R.string.sync_phase_complete) ?: "Complete"
    is SyncPhase.Error ->
        context?.getString(R.string.sync_phase_error) ?: "Error"
    else ->
        context?.getString(R.string.sync_phase_syncing) ?: "Syncing..."
}
