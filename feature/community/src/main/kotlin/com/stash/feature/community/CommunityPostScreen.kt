package com.stash.feature.community

import android.widget.Toast
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.model.share.SharedTrack
import com.stash.feature.community.CommunityPostViewModel.UiState

/** One post (spec 2026-09-26 §3): Play, then Save a copy or Like, your vote, and the songs as posted. */
@Composable
fun CommunityPostScreen(
    onBack: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    viewModel: CommunityPostViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmTakeDown by remember { mutableStateOf(false) }
    MessageToast((state as? UiState.Loaded)?.message, viewModel::messageShown)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Spacer(Modifier.weight(1f))
            if ((state as? UiState.Loaded)?.post?.mine == true) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Take down my post") }, onClick = { menu = false; confirmTakeDown = true })
                    }
                }
            }
        }
        when (val s = state) {
            UiState.Loading -> Centered { CircularProgressIndicator() }
            UiState.Gone -> Centered { Text("This post is no longer available", style = MaterialTheme.typography.titleMedium) }
            UiState.Failed -> Centered {
                Text("Couldn't load this post.", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = viewModel::load) { Text("Retry") }
            }
            is UiState.Loaded -> PostBody(s, viewModel, onOpenPlaylist)
        }
    }
    if (confirmTakeDown) {
        AlertDialog(
            onDismissRequest = { confirmTakeDown = false },
            title = { Text("Take down this post?") },
            text = { Text("It leaves Community for everyone. It still counts toward today's two posts.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmTakeDown = false
                    viewModel.takeDown {
                        Toast.makeText(context, "Post taken down", Toast.LENGTH_SHORT).show()
                        onBack()
                    }
                }) { Text("Take down") }
            },
            dismissButton = { TextButton(onClick = { confirmTakeDown = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun PostBody(s: UiState.Loaded, viewModel: CommunityPostViewModel, onOpenPlaylist: (Long) -> Unit) {
    val post = s.post
    val song = post.kind == "song"
    // Refreshed each time the screen resumes, so "Posted … ago" never freezes.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LifecycleResumeEffect(Unit) { now = System.currentTimeMillis(); onPauseOrDispose {} }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp)) {
        item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CoverArt(post.coverUrls(), Modifier.size(if (song) 220.dp else 180.dp), corner = 12.dp)
            }
            Spacer(Modifier.height(16.dp))
            Text(post.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(post.headline(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    // A whole emoji, not half of one.
                    val initial = post.name.take(if (post.name.firstOrNull()?.isHighSurrogate() == true) 2 else 1)
                    Text(initial.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "Posted by ${post.name} · ${ago(post.createdAt, now, long = true)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::play, enabled = !s.busy) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play")
                }
                if (song) {
                    OutlinedButton(onClick = viewModel::like, enabled = !s.busy && !s.liked) {
                        Icon(if (s.liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (s.liked) "Liked" else "Like")
                    }
                } else {
                    OutlinedButton(onClick = { viewModel.saveCopy(onOpenPlaylist) }, enabled = !s.busy) { Text("Save a copy") }
                }
                Spacer(Modifier.weight(1f))
                VoteControl(post, onVote = viewModel::vote, vertical = false)
            }
            if (!song) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "As posted. ${post.name}'s later changes don't show here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        if (!song) items(post.tracks) { SongLine(it) }
    }
}

@Composable
private fun SongLine(t: SharedTrack) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverArt(listOfNotNull(t.artUrl), Modifier.size(40.dp), corner = 4.dp)
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                t.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
