package com.stash.feature.library.share

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.common.extensions.pluralize
import com.stash.core.model.share.ShareConfig
import com.stash.core.model.share.SharedTrack
import com.stash.core.ui.components.bottomChromePadding
import com.stash.core.ui.theme.StashTheme
import com.stash.core.ui.util.formatDuration
import com.stash.core.ui.util.formatTotalDuration

/**
 * A playlist someone shared (a Stash mix link), with a Library-style header: its covers full width (a
 * mosaic when there are four) fading into the page, the name bold, who shared it and how long it is,
 * Play and Follow side by side with Save a copy, then the songs with their art.
 */
@Composable
fun SharedMixScreen(
    onBack: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    /** Follow / Save a copy: opens the new playlist in place of this screen. */
    onJoinedPlaylist: (Long) -> Unit = onOpenPlaylist,
    viewModel: SharedMixViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val s = when (val st = state) {
        SharedMixUiState.Loading -> {
            BareScreen(onBack) { CircularProgressIndicator() }
            return
        }
        is SharedMixUiState.Error -> {
            BareScreen(onBack) {
                Text(st.message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                if (st.retryable) { Spacer(Modifier.height(16.dp)); Button(onClick = viewModel::load) { Text("Retry") } }
            }
            return
        }
        is SharedMixUiState.Loaded -> st
    }
    val glass = StashTheme.extendedColors.glassBackground
    // Docs stored before the Worker allowlist may carry any host: never load an off-list one.
    val covers = remember(s.doc) { s.doc.covers.filter(ShareConfig::isAllowedCover) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomChromePadding(24.dp))) {
        item {
            SharedHeroCover(covers, onBack)
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text(
                    text = s.doc.sharedBy?.let { "Shared by $it" } ?: "Shared playlist",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    s.doc.name,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                val total = s.doc.tracks.sumOf { it.durationMs ?: 0L }
                val updated = s.doc.updatedAt.takeIf { it > 0 }?.let {
                    val now = System.currentTimeMillis()
                    if (now - it * 1000 < DateUtils.MINUTE_IN_MILLIS) "updated just now"
                    else "updated " + DateUtils.getRelativeTimeSpanString(it * 1000, now, DateUtils.MINUTE_IN_MILLIS)
                }
                Text(
                    listOfNotNull(pluralize(s.doc.tracks.size, "track"), total.takeIf { it > 0 }?.let(::formatTotalDuration), updated)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = viewModel::play,
                        enabled = !s.busy,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Play")
                    }
                    when {
                        s.isOwnMix -> Text(
                            "This is your mix",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                        s.followedPlaylistId != null -> OutlinedButton(
                            onClick = { onOpenPlaylist(s.followedPlaylistId) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 12.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Following")
                        }
                        else -> {
                            OutlinedButton(
                                onClick = { viewModel.follow(onJoinedPlaylist) },
                                enabled = !s.busy,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 12.dp),
                                shape = RoundedCornerShape(12.dp),
                            ) { Text("Follow") }
                            IconButton(
                                onClick = { viewModel.saveCopy(onJoinedPlaylist) },
                                enabled = !s.busy,
                                modifier = Modifier.size(48.dp).background(glass, RoundedCornerShape(12.dp)),
                            ) {
                                Icon(Icons.Default.LibraryAdd, contentDescription = "Save a copy", tint = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
                if (s.followedPlaylistId != null && !s.isOwnMix) {
                    var confirmUnfollow by remember { mutableStateOf(false) }
                    TextButton(
                        onClick = { if (confirmUnfollow) viewModel.unfollow() else confirmUnfollow = true },
                        enabled = !s.busy,
                    ) { Text(if (confirmUnfollow) "Tap again to unfollow" else "Unfollow") }
                }
                s.message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        itemsIndexed(s.doc.tracks) { i, t -> SharedSongRow(i + 1, t) }
    }
}

/** A song in a shared playlist: its number, cover, title and artist, and length. */
@Composable
private fun SharedSongRow(number: Int, t: SharedTrack) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "$number",
            style = MaterialTheme.typography.bodyMedium,
            color = StashTheme.extendedColors.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(24.dp),
        )
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp))) {
            SharedCover(listOfNotNull(t.coverUrl()), Modifier.fillMaxSize(), quiet = true)
        }
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                t.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        t.durationMs?.takeIf { it > 0 }?.let {
            Text(formatDuration(it), style = MaterialTheme.typography.bodySmall, color = StashTheme.extendedColors.textTertiary)
        }
    }
}
