package com.stash.feature.sync.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import com.stash.core.model.DownloadStatus
import com.stash.feature.sync.DownloadManagementAction
import com.stash.feature.sync.DownloadManagementItem
import com.stash.feature.sync.R

@Composable
fun DownloadManagementRow(
    item: DownloadManagementItem,
    queuePosition: Int? = null,
    onCancel: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (item.albumArtUrl.isNullOrBlank()) {
                Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.55f))
            } else {
                AsyncImage(
                    model = item.albumArtUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(item.artist)
                    item.playlistName?.takeIf(String::isNotBlank)?.let { append(" · "); append(it) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            val phase = if (item.phaseLabelRes != 0) stringResource(item.phaseLabelRes) else item.phaseLabel
            Text(
                text = if (queuePosition != null) stringResource(R.string.sync_downloads_queue_position, queuePosition, phase) else phase,
                style = MaterialTheme.typography.labelSmall,
                color = if (item.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            if (item.status == DownloadStatus.IN_PROGRESS) {
                Spacer(Modifier.height(5.dp))
                if (item.progress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        when (item.action) {
            DownloadManagementAction.CANCEL -> IconButton(onClick = { onCancel(item.queueId) }) {
                Icon(Icons.Default.Close, stringResource(R.string.sync_downloads_cd_cancel, item.title))
            }
            DownloadManagementAction.RETRY -> IconButton(onClick = { onRetry(item.queueId) }) {
                Icon(Icons.Default.Refresh, stringResource(R.string.sync_downloads_cd_retry, item.title))
            }
            DownloadManagementAction.NONE -> if (item.status == DownloadStatus.IN_PROGRESS) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
}
