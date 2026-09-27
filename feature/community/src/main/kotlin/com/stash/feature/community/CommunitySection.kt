package com.stash.feature.community

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Home's Community section (spec 2026-09-26 §3): the top 5 as ranked rows with votes, `+` and See all.
 * Composed only while Community is on, so nothing here runs, or asks the Worker anything, while it's off.
 */
@Composable
fun CommunitySection(
    onOpenPost: (String) -> Unit,
    onSeeAll: () -> Unit,
    onPost: () -> Unit,
    viewModel: CommunityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Each time Home resumes (first open, back from another screen or from the background) the ages catch up and
    // the list reloads; the ViewModel throttles the reload.
    LifecycleResumeEffect(Unit) { now = System.currentTimeMillis(); viewModel.onShown(); onPauseOrDispose {} }
    MessageToast(state.message, viewModel::messageShown)
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Community", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    "What Stash listeners are digging this week",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onPost) {
                Icon(Icons.Default.Add, contentDescription = "Post to Community", tint = MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = onSeeAll) { Text("See all") }
        }
        val posts = state.posts
        if (posts.isNullOrEmpty()) {
            ListStatus(state, "Nothing here yet. Be the first: tap +.", onRetry = { viewModel.show(CommunityViewModel.Tab.HOME) })
        } else {
            val rankWidth = rankWidthFor(posts.size)
            posts.forEachIndexed { i, p ->
                PostRow(
                    rank = i + 1,
                    rankWidth = rankWidth,
                    post = p,
                    withAge = false,
                    now = now,
                    onOpen = { onOpenPost(p.id) },
                    onVote = { viewModel.vote(p, it) },
                )
            }
        }
    }
}
