package com.stash.feature.community

import android.widget.Toast
import com.stash.core.ui.theme.StashTheme
import com.stash.core.model.community.CommunityPost
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.clickable
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
import com.stash.core.ui.components.bottomChromePadding

/**
 * One post (spec 2026-09-26 §3), with a header like a Library playlist's: the cover full width, fading into
 * the page, under it the title, what it is and who posted it, with the votes.
 *
 *  - A playlist or mix: Play and Shuffle, Save a copy, then its songs as posted.
 *  - A song: Play (the song, then a radio grows from it) and Like; the artist's name opens their artist
 *    page; then "More from Community".
 */
@Composable
fun CommunityPostScreen(
    onBack: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenArtist: (id: String, name: String, avatarUrl: String?) -> Unit,
    viewModel: CommunityPostViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val related by viewModel.related.collectAsStateWithLifecycle()
    val openingArtist by viewModel.openingArtist.collectAsStateWithLifecycle()
    val loaded = state as? UiState.Loaded
    val context = LocalContext.current
    // Saveable, so the dialog survives rotation.
    var confirmTakeDown by rememberSaveable { mutableStateOf(false) }
    MessageToast(loaded?.message, viewModel::messageShown)
    when (val s = state) {
        is UiState.Loaded -> PostBody(
            s = s,
            related = related,
            openingArtist = openingArtist,
            viewModel = viewModel,
            onBack = onBack,
            onOpenPlaylist = onOpenPlaylist,
            onOpenPost = onOpenPost,
            onOpenArtist = onOpenArtist,
            onTakeDown = { confirmTakeDown = true },
        )
        else -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            when (s) {
                UiState.Loading -> Centered { CircularProgressIndicator() }
                UiState.Gone -> Centered {
                    Text("This post is no longer available", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                }
                is UiState.Failed -> Centered {
                    Text(s.message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = viewModel::load) { Text("Retry") }
                }
                is UiState.Loaded -> Unit
            }
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

@Composable
private fun PostBody(
    s: UiState.Loaded,
    related: List<CommunityPost>,
    openingArtist: Boolean,
    viewModel: CommunityPostViewModel,
    onBack: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenArtist: (id: String, name: String, avatarUrl: String?) -> Unit,
    onTakeDown: () -> Unit,
) {
    val post = s.post
    val song = post.kind == "song"
    val glass = StashTheme.extendedColors.glassBackground
    // Refreshed each time the screen resumes, so "Posted … ago" never freezes.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LifecycleResumeEffect(Unit) { now = System.currentTimeMillis(); onPauseOrDispose {} }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomChromePadding(120.dp))) {
        item {
            // -- The cover, full width, fading into the page ------------------------------
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                CoverArt(post.coverUrls(), Modifier.fillMaxSize(), corner = 0.dp)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))),
                )
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(start = 8.dp, top = 8.dp)
                        .align(Alignment.TopStart)
                        .size(48.dp)
                        .background(glass, CircleShape),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
                }
                if (post.mine) {
                    var menu by remember { mutableStateOf(false) }
                    Box(
                        Modifier
                            .statusBarsPadding()
                            .padding(end = 8.dp, top = 8.dp)
                            .align(Alignment.TopEnd),
                    ) {
                        // Off while an action runs: it would drop a take-down confirmed meanwhile.
                        IconButton(
                            onClick = { menu = true },
                            enabled = !s.busy,
                            modifier = Modifier.size(48.dp).background(glass, CircleShape),
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Take down my post") }, onClick = { menu = false; onTakeDown() })
                        }
                    }
                }
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text(
                    post.title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(4.dp))
                if (song) {
                    // The artist opens their artist page (looked up by name; a spinner while it is).
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !openingArtist) { viewModel.openArtist(onOpenArtist) }
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            post.headline(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (openingArtist) {
                            Spacer(Modifier.width(8.dp))
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else {
                    Text(
                        post.headline(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                // Who posted it, and the votes.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PosterInitial(post.name)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Posted by ${post.name} · ${ago(post.createdAt, now, long = true)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        modifier = Modifier.weight(1f),
                    )
                    VoteControl(post, onVote = viewModel::vote, vertical = false)
                }
                Spacer(Modifier.height(14.dp))

                // -- Actions -------------------------------------------------------------
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    if (song) {
                        OutlinedButton(
                            onClick = viewModel::like,
                            enabled = !s.busy && !s.liked,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 12.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(if (s.liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (s.liked) "Liked" else "Like")
                        }
                    } else {
                        OutlinedButton(
                            onClick = viewModel::shuffle,
                            enabled = !s.busy,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 12.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Shuffle")
                        }
                        IconButton(
                            onClick = { viewModel.saveCopy(onOpenPlaylist) },
                            enabled = !s.busy,
                            modifier = Modifier.size(48.dp).background(glass, RoundedCornerShape(12.dp)),
                        ) {
                            Icon(Icons.Default.LibraryAdd, contentDescription = "Save a copy", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                // A 500-song Play or Save a copy can take seconds. Its space is kept, so the songs don't jump when it shows.
                Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp)) { if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (!song) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "As posted. ${post.name}'s later changes don't show here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        if (!song) items(post.tracks) { Box(Modifier.padding(horizontal = 20.dp)) { SongLine(it) } }
        if (song && related.isNotEmpty()) {
            item {
                Text(
                    "More from Community",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                )
            }
            items(related, key = { it.id }) { p ->
                PostRow(
                    rank = null,
                    rankWidth = 0.dp,
                    post = p,
                    withAge = false,
                    now = now,
                    onOpen = { onOpenPost(p.id) },
                    onVote = {},
                    // Voted on from its own page: no arrows here that would do nothing.
                    showVotes = false,
                )
            }
        }
    }
}

/** The poster's initial in a small tinted circle. Silent to TalkBack: "Posted by" already names the person. */
@Composable
private fun PosterInitial(name: String) {
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        // A whole emoji, not half of one.
        val initial = name.take(if (name.firstOrNull()?.isHighSurrogate() == true) 2 else 1)
        Text(initial.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
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
