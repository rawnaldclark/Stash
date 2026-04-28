package com.stash.feature.sync.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.dp
import com.stash.core.data.sync.SyncPhase

/**
 * Multi-phase sync progress widget shown inside [SyncHeroCard] while a sync
 * is running.  Extracted from the old SyncActionSection so the hero card
 * can embed it without owning the Sync Now button logic.
 *
 * @param phase      Current [SyncPhase] (drives the status label).
 * @param progress   0f–1f overall progress, forwarded to the linear indicator.
 * @param onStopSync Invoked when the user taps "Stop sync".
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
        // Disabled "Syncing…" row mirrors the current phase label.
        Button(
            onClick = { /* disabled while syncing */ },
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                disabledContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = phaseLabel(phase),
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
                text = "Stop sync",
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
internal fun phaseLabel(phase: SyncPhase): String = when (phase) {
    is SyncPhase.Authenticating -> "Authenticating..."
    is SyncPhase.FetchingPlaylists -> "Fetching playlists..."
    is SyncPhase.Diffing -> "Comparing changes..."
    is SyncPhase.Downloading -> "Downloading ${phase.downloaded}/${phase.total}..."
    is SyncPhase.Finalizing -> "Finalizing..."
    is SyncPhase.Completed -> "Complete"
    is SyncPhase.Error -> "Error"
    else -> "Syncing..."
}
