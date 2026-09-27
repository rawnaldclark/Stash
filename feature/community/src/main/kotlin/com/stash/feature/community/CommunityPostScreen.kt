package com.stash.feature.community

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
    val loaded = state as? UiState.Loaded
    val context = LocalContext.current
    // Saveable, so the dialog survives rotation.
    var confirmTakeDown by rememberSaveable { mutableStateOf(false) }
    MessageToast(loaded?.message, viewModel::messageShown)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Spacer(Modifier.weight(1f))
            if (loaded != null && loaded.post.mine) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    // Off while an action runs: it would drop a take-down confirmed meanwhile.
                    IconButton(onClick = { menu = true }, enabled = !loaded.busy) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Take down my post") }, onClick = { menu = false; confirmTakeDown = true })
                    }
                }
            }
        }
        when (val s = state) {
            UiState.Loading -> Centered { CircularProgressIndicator() }
            UiState.Gone -> Centered {
                Text("This post is no longer available", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
            is UiState.Failed -> Centered {
                Text(s.message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = viewModel::load) { Text("Retry") }
            }
            is UiState.Loaded -> PostBody(s, viewModel, onOpenPlaylist)
        }
    }
    // Waits for the post: after the app is restored, the saved dialog can come back before it loads.
    if (confirmTakeDown && loaded != null) {
        AlertDialog(
            onDismissRequest = { confirmTakeDown = false },
            title = { Text("Take down this post?") },
            text = {
                // The Worker counts a post toward the daily two for 24 hours.
                val countsToday = System.currentTimeMillis() - loaded.post.createdAt < 86_400_000L
                Text("It leaves Community for everyone." + if (countsToday) " It still counts toward today's two posts." else "")
            },
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PostBody(s: UiState.Loaded, viewModel: CommunityPostViewModel, onOpenPlaylist: (Long) -> Unit) {
    val post = s.post
    val song = post.kind == "song"
    // Refreshed each time the screen resumes, so "Posted … ago" never freezes.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LifecycleResumeEffect(Unit) { now = System.currentTimeMillis(); onPauseOrDispose {} }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp)) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                CoverArt(post.coverUrls(), Modifier.size(if (song) 220.dp else 180.dp), corner = 12.dp)
                Spacer(Modifier.height(16.dp))
                Text(
                    post.title,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    post.headline(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Silent to TalkBack: "Posted by" already names the person.
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                            .clearAndSetSemantics {},
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
            }
            Spacer(Modifier.height(16.dp))
            // Wraps instead of clipping ▼ at large font sizes.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                VoteControl(post, onVote = viewModel::vote, modifier = Modifier.align(Alignment.CenterVertically), vertical = false)
            }
            // A 500-song Play or Save a copy can take seconds. Its space is kept, so the songs don't jump when it shows.
            Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp)) { if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) }
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
