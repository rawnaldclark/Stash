package com.stash.feature.sync.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.sync.R
import com.stash.feature.sync.SyncViewModel

/**
 * The Downloads row above Auto-sync, in [SyncLinkRow]'s shape: "Downloads" and, on the right, what's going
 * on ("2 downloading · 14 waiting · 1 failed", or "None running"). While songs download, a thin bar under
 * the row shows how far they are together. A tap opens the Downloads screen.
 */
@Composable
internal fun SyncDownloadsRow(
    summary: SyncViewModel.DownloadsSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val parts = buildList {
        if (summary.downloading > 0) {
            add(pluralStringResource(R.plurals.sync_downloads_downloading, summary.downloading, summary.downloading))
        }
        if (summary.waiting > 0) add(pluralStringResource(R.plurals.sync_downloads_waiting, summary.waiting, summary.waiting))
        if (summary.failed > 0) add(pluralStringResource(R.plurals.sync_downloads_failed, summary.failed, summary.failed))
    }
    val trailing = if (parts.isEmpty()) stringResource(R.string.sync_downloads_none) else parts.joinToString(" · ")
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(StashTheme.extendedColors.glassBorder))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(onClick = onClick, role = Role.Button)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.sync_downloads_row),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = trailing,
                style = MaterialTheme.typography.bodySmall,
                color = if (summary.failed > 0 && summary.downloading == 0) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        summary.progress?.let { progress ->
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp).height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = StashTheme.extendedColors.glassBorder,
            )
        }
    }
}
