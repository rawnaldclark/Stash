package com.stash.feature.sync.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.data.db.dao.FailedDownloadRow
import com.stash.core.model.DownloadFailureType
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.sync.FailedDownloadsGroup
import com.stash.feature.sync.R
import com.stash.feature.sync.display

/**
 * Hard cap on rows rendered in a single expanded group. Without it, a
 * group with thousands of failures (one user hit 2325 in a single group)
 * blows past the Compose layout budget and OOMs the app on screen open
 * because the body is a [Column], not a [androidx.compose.foundation.lazy.LazyColumn].
 * The "+N more" footer surfaces the excess; the user can still operate
 * on them via the group-level retry button.
 */
private const val MAX_VISIBLE_ROWS_PER_GROUP = 100

/**
 * Row count above which a group starts collapsed by default — keeps the
 * Failed Downloads screen from rendering hundreds of rows in the initial
 * frame even when the cap above would catch the absolute worst case.
 */
private const val AUTO_COLLAPSE_THRESHOLD = 50

/**
 * Collapsible card representing one [FailedDownloadsGroup] — a single
 * [DownloadFailureType] bucket and every row currently classified under it.
 *
 * - Header (always visible): failure-type icon + group title + count +
 *   expand/collapse chevron. Clicking the header toggles the body.
 * - Body (when expanded): a "Retry N tracks" group action followed by every
 *   [FailedDownloadRow] rendered as an [FailedDownloadRowItem] with per-row
 *   retry + block actions.
 *
 * Visual rhythm matches `FailedMatchesScreen.UnmatchedTrackRow` (48dp album
 * art, 12dp gutter, 40dp icon buttons) layered over the project's standard
 * [StashTheme.extendedColors.glassBackground] surface.
 */
@Composable
fun FailedDownloadsGroupCard(
    group: FailedDownloadsGroup,
    onRetryRow: (queueId: Long) -> Unit,
    onBlockRow: (trackId: Long) -> Unit,
    onRetryGroup: (DownloadFailureType) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Groups with more than AUTO_COLLAPSE_THRESHOLD rows start collapsed so
    // the screen doesn't try to render hundreds of items on first open.
    var expanded by remember(group.type) {
        mutableStateOf(group.rows.size <= AUTO_COLLAPSE_THRESHOLD)
    }
    val display = group.type.display()
    val extendedColors = StashTheme.extendedColors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(extendedColors.glassBackground)
            .border(0.5.dp, extendedColors.glassBorder, RoundedCornerShape(14.dp)),
    ) {
        // -- Header (always visible) --
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = display.icon,
                contentDescription = null,
                tint = display.tint,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(display.groupTitleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = pluralStringResource(R.plurals.sync_tracks_count, group.rows.size, group.rows.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = stringResource(if (expanded) R.string.sync_cd_collapse else R.string.sync_cd_expand),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }

        // -- Body (collapsible: group action + per-row entries) --
        AnimatedVisibility(visible = expanded) {
            Column {
                OutlinedButton(
                    onClick = { onRetryGroup(group.type) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(pluralStringResource(R.plurals.sync_failed_downloads_retry_tracks, group.rows.size, group.rows.size))
                }
                Spacer(Modifier.height(4.dp))
                // Cap visible rows so a giant group (e.g. one user reported
                // 2325 NETWORK rows after a cancelled sync) doesn't OOM
                // the layout. The group-level retry button above still
                // operates on every row, capped or not.
                val visibleRows = group.rows.take(MAX_VISIBLE_ROWS_PER_GROUP)
                visibleRows.forEachIndexed { index, row ->
                    FailedDownloadRowItem(
                        row = row,
                        onRetry = { onRetryRow(row.queueId) },
                        onBlock = { onBlockRow(row.trackId) },
                    )
                    if (index < visibleRows.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 72.dp, end = 14.dp),
                            thickness = 0.5.dp,
                            color = extendedColors.glassBorder,
                        )
                    }
                }
                if (group.rows.size > MAX_VISIBLE_ROWS_PER_GROUP) {
                    val excess = group.rows.size - MAX_VISIBLE_ROWS_PER_GROUP
                    Text(
                        text = pluralStringResource(
                            R.plurals.sync_failed_downloads_more_count,
                            excess,
                            excess,
                            MAX_VISIBLE_ROWS_PER_GROUP,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun FailedDownloadRowItem(
    row: FailedDownloadRow,
    onRetry: () -> Unit,
    onBlock: () -> Unit,
) {
    val display = row.failureType.display()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Album art (gradient placeholder when missing — mirrors UnmatchedTrackRow)
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f),
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (!row.albumArtUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = row.albumArtUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // Title + subtitle ("<playlist> · <short reason>")
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.sync_failed_downloads_title_artist, row.title, row.artist),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val shortReason = stringResource(display.shortLabelRes)
            val playlist = row.playlistName
            val subtitle = if (!playlist.isNullOrBlank()) {
                stringResource(R.string.sync_failed_downloads_playlist_reason, playlist, shortReason)
            } else {
                shortReason
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Per-row actions
        IconButton(onClick = onRetry, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = stringResource(R.string.sync_failed_downloads_cd_retry),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = onBlock, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Default.Block,
                contentDescription = stringResource(R.string.sync_failed_downloads_cd_block),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
