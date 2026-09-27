package com.stash.feature.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.feature.community.CommunityViewModel.Tab

/** See all (spec 2026-09-26 §3): All / Mine, up to 100 posts, `+ Post` in the top bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(
    mine: Boolean,
    onBack: () -> Unit,
    onOpenPost: (String) -> Unit,
    onPost: () -> Unit,
    viewModel: CommunityViewModel = hiltViewModel(),
) {
    var tab by rememberSaveable { mutableStateOf(if (mine) Tab.MINE else Tab.ALL) }
    LaunchedEffect(tab) { viewModel.show(tab) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    MessageToast(state.message, viewModel::messageShown)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Community", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onPost) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Post")
            }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == Tab.ALL, onClick = { tab = Tab.ALL }, label = { Text("All") })
            FilterChip(selected = tab == Tab.MINE, onClick = { tab = Tab.MINE }, label = { Text("Mine") })
        }
        val posts = state.posts
        if (posts.isNullOrEmpty()) {
            ListStatus(
                state,
                if (tab == Tab.MINE) "You have no posts up." else "Nothing here yet. Be the first: tap + Post.",
                onRetry = { viewModel.show(tab) },
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
                itemsIndexed(posts, key = { _, p -> p.id }) { i, p ->
                    PostRow(
                        rank = if (tab == Tab.MINE) null else i + 1,
                        post = p,
                        withAge = true,
                        onOpen = { onOpenPost(p.id) },
                        onVote = { viewModel.vote(p, it) },
                    )
                }
            }
        }
    }
}
