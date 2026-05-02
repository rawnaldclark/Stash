package com.stash.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.model.PlayerState
import com.stash.core.ui.theme.StashTheme

@Composable
fun MiniPlayerBar(playerState: PlayerState, onPlayPauseClick: () -> Unit, onSkipNextClick: () -> Unit, onBarClick: () -> Unit, modifier: Modifier = Modifier) {
    val extendedColors = StashTheme.extendedColors
    val track = playerState.currentTrack ?: return
    val progress = if (playerState.durationMs > 0) playerState.positionMs.toFloat() / playerState.durationMs.toFloat() else 0f

    Surface(modifier = modifier.fillMaxWidth().clickable(onClick = onBarClick), color = extendedColors.glassBackground, border = BorderStroke(1.dp, extendedColors.glassBorder), shape = MaterialTheme.shapes.small) {
        Column {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary, trackColor = extendedColors.glassBackground)
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Album art or fallback music note icon
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(MaterialTheme.shapes.small),
                    contentAlignment = Alignment.Center,
                ) {
                    val artUrl = track.albumArtPath ?: track.albumArtUrl
                    if (artUrl != null) {
                        AsyncImage(
                            model = artUrl,
                            contentDescription = "${track.title} album art",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(track.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        FlacBadge(fileFormat = track.fileFormat)
                    }
                    Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onPlayPauseClick) { Icon(if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (playerState.isPlaying) "Pause" else "Play", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(28.dp)) }
                IconButton(onClick = onSkipNextClick) { Icon(Icons.Default.SkipNext, contentDescription = "Skip", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp)) }
            }
        }
    }
}
