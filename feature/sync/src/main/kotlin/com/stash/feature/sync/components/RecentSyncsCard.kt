package com.stash.feature.sync.components

import android.text.format.DateUtils
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.theme.SpaceGrotesk
import com.stash.core.ui.theme.StashTheme

/**
 * View-layer row model used by [RecentSyncsCard]. Decouples the card from
 * `SyncHistoryInfo` / DB types — the SyncScreen builds these from
 * `uiState.recentSyncs` and passes them in.
 */
data class RecentSyncRow(
    val id: Long,
    val modeLabel: String?,     // "Online" / "Offline"; null on pre-migration rows
    val relativeTime: String,   // "35m ago"
    val duration: String?,      // "45s" / "1m 12s"; null when unknown/interrupted
    val added: Int,             // surfaced (online) or downloaded (offline/legacy) this run
    val addedNoun: String,      // "surfaced" (online) / "downloaded" (offline/legacy)
    val playlists: Int,         // playlists checked
    val sizeLabel: String?,     // "340 MB"; null when nothing downloaded
    val failed: Int,            // tracks that failed
    val status: SyncRowStatus,
    val errorMessage: String? = null,
    val diagnostics: String? = null,
)

enum class SyncRowStatus { HEALTHY, PARTIAL, FAILED, CANCELLED }

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
    var expanded by remember { mutableStateOf(false) }
    val success = StashTheme.extendedColors.success
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val fail = Color(0xFFF87171)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .animateContentSize()
            .padding(vertical = 11.dp),
    ) {
        // Line 1 — mode · when   ····   duration
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = buildAnnotatedString {
                    if (row.modeLabel != null) {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)) {
                            append(row.modeLabel)
                        }
                        withStyle(SpanStyle(color = dim)) { append("  ·  ${row.relativeTime}") }
                    } else {
                        withStyle(SpanStyle(color = dim)) { append(row.relativeTime) }
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (row.duration != null) {
                Text(
                    text = row.duration,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = SpaceGrotesk),
                    color = dim,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Line 2 — the receipt: additions in green, neutral stats, failures stand out
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (row.status == SyncRowStatus.CANCELLED) {
                Pill("Cancelled", dim)          // neutral, not the red fail tint
                Spacer(Modifier.weight(1f))
            } else if (row.status == SyncRowStatus.FAILED) {
                // Any FAILED row shows the pill — never a green receipt line.
                // (Online rows bind `added` to surfaced-count, which can be >0
                // on a late failure, so a count guard here would let a failed
                // sync masquerade as success.)
                Pill("Sync failed", fail)
                Spacer(Modifier.weight(1f))
            } else {
                Text(
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = success, fontWeight = FontWeight.Medium)) { append("+${row.added} ") }
                        withStyle(SpanStyle(color = dim)) { append(row.addedNoun) }
                        if (row.playlists > 0) withStyle(SpanStyle(color = dim)) { append("  ·  ${row.playlists} playlists") }
                        if (row.sizeLabel != null) withStyle(SpanStyle(color = dim)) { append("  ·  ${row.sizeLabel}") }
                    },
                )
                if (row.failed > 0) {
                    Pill("⚠ ${row.failed} failed", fail)
                    Spacer(Modifier.width(8.dp))
                }
            }
            Text("›", style = MaterialTheme.typography.titleMedium, color = dim.copy(alpha = 0.55f))
        }

        if (expanded) {
            Spacer(Modifier.height(8.dp))
            if (!row.errorMessage.isNullOrBlank()) {
                Text("Error: ${row.errorMessage}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(4.dp))
            }
            if (!row.diagnostics.isNullOrBlank()) {
                Text("Diagnostics", style = MaterialTheme.typography.labelSmall, color = dim, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(row.diagnostics, style = MaterialTheme.typography.bodySmall, color = dim, maxLines = 20)
            }
            if (row.errorMessage.isNullOrBlank() && row.diagnostics.isNullOrBlank()) {
                Text("No further details recorded", style = MaterialTheme.typography.bodySmall, color = dim)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** Small tinted status pill (e.g. a red "⚠ 3 failed" that pulls the eye). */
@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.13f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

/** Compact duration label from a millisecond span, e.g. "45s" or "1m 12s". */
fun formatSyncDuration(startedAt: Long, completedAt: Long?): String? {
    if (completedAt == null || completedAt <= startedAt) return null
    val secs = (completedAt - startedAt) / 1000
    return if (secs < 60) "${secs}s" else "${secs / 60}m ${secs % 60}s"
}

/** Compact byte-size label, e.g. "340 MB" or "1.2 GB". Blank input → null. */
fun formatSyncBytes(bytes: Long): String? = when {
    bytes <= 0 -> null
    bytes < 1024L * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
}

/** Formats a Unix-millisecond timestamp into a relative time string (e.g. "3 minutes ago"). */
fun formatRelativeTime(timestampMs: Long): String =
    DateUtils.getRelativeTimeSpanString(
        timestampMs,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
