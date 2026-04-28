package com.stash.feature.sync.components

import android.text.format.DateUtils
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.theme.StashTheme

/**
 * View-layer row model used by [RecentSyncsCard]. Decouples the card from
 * `SyncHistoryInfo` / DB types — the SyncScreen builds these from
 * `uiState.recentSyncs` and passes them in.
 */
data class RecentSyncRow(
    val id: Long,
    val timestamp: String,
    val summary: String,
    val status: SyncRowStatus,
    val errorMessage: String? = null,
    val diagnostics: String? = null,
)

enum class SyncRowStatus { HEALTHY, PARTIAL, FAILED }

/**
 * Single [GlassCard] that lists recent sync results with internal dividers.
 * Replaces the previous pattern of one [GlassCard] per history row.
 * Hidden when [rows] is empty.
 */
@Composable
fun RecentSyncsCard(rows: List<RecentSyncRow>, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) return
    GlassCard(modifier = modifier) {
        Column {
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.05f)),
                    )
                }
                RecentSyncRowItem(row)
            }
        }
    }
}

@Composable
private fun RecentSyncRowItem(row: RecentSyncRow) {
    val ec = StashTheme.extendedColors
    var expanded by remember { mutableStateOf(false) }

    val (dotColor, marker) = when (row.status) {
        SyncRowStatus.HEALTHY -> ec.success to "✓"
        SyncRowStatus.PARTIAL -> ec.warning to "!"
        SyncRowStatus.FAILED -> Color(0xFFEF4444) to "×"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .animateContentSize(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor, CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(row.timestamp, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = row.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(marker, style = MaterialTheme.typography.titleMedium, color = dotColor)
        }

        if (expanded) {
            Spacer(Modifier.height(4.dp))
            if (!row.errorMessage.isNullOrBlank()) {
                Text(
                    text = "Error: ${row.errorMessage}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(4.dp))
            }
            if (!row.diagnostics.isNullOrBlank()) {
                Text(
                    text = "Diagnostics:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = row.diagnostics,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 20,
                )
            }
            if (row.errorMessage.isNullOrBlank() && row.diagnostics.isNullOrBlank()) {
                Text(
                    text = "No additional details available",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** Formats a Unix-millisecond timestamp into a relative time string (e.g. "3 minutes ago"). */
fun formatRelativeTime(timestampMs: Long): String =
    DateUtils.getRelativeTimeSpanString(
        timestampMs,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
