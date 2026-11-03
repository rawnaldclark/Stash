package com.stash.feature.community

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.ui.ambient.LocalAlbumChrome
import com.stash.core.ui.components.bottomChromePadding
import kotlinx.coroutines.launch

/**
 * See all (spec 2026-09-26 §3, redrawn 2026-11-01): Top this week / New / Your posts, `+ Post` in the top bar.
 * Top: this week's posts by votes (every live post when the week has fewer than five: then it says "Top"), Play all
 * / Shuffle (a taste of that chart), the #1 as the hero, then #2 on as ranked rows. New: the same posts newest
 * first. Your posts: yours, with how long each has left. Pull to refresh; skeletons while the first
 * list loads; a card with Retry, or one that asks you to post, instead of an empty page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(
    mine: Boolean,
    onBack: () -> Unit,
    onOpenPost: (String) -> Unit,
    onPost: () -> Unit,
    viewModel: CommunityViewModel = hiltViewModel(),
) {
    var segment by rememberSaveable { mutableStateOf(if (mine) Segment.MINE else Segment.TOP) }
    // Top and New are one list, so switching between them loads nothing.
    LaunchedEffect(segment.tab) { viewModel.show(segment.tab) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Back from the background, the ages catch up.
    LifecycleResumeEffect(Unit) { now = System.currentTimeMillis(); onPauseOrDispose {} }
    CommunitySnackbar(state.message, viewModel::messageShown)
    CommunitySnackbar(listenFirstNote(state.listenFirst), viewModel::listenFirstShown)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val ui = listUi(state, segment, now)
    // Top's label ("Top this week" or "Top") follows the chart, and keeps the last one it knew while Your posts shows.
    var topThisWeek by remember { mutableStateOf(true) }
    (listUi(state, Segment.TOP, now) as? ListUi.Ready)?.let { topThisWeek = it.thisWeek }
    // One width for every rank, up to the last one shown.
    val rankWidth = rankWidthFor((ui as? ListUi.Ready)?.rows?.lastOrNull()?.rank ?: 1)

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.community_back))
            }
            Text(
                stringResource(R.string.community_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            PostPill(onClick = onPost)
        }
        SegmentedTabs(
            selected = segment,
            topThisWeek = topThisWeek,
            onSelect = { picked ->
                if (picked != segment) {
                    segment = picked
                    // A new list starts at its top (Back from a post keeps the place: this runs on a tap only).
                    scope.launch { listState.scrollToItem(0) }
                }
            },
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
        )
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(top = 4.dp, bottom = bottomChromePadding(120.dp)),
            ) {
                val gutter = Modifier.padding(horizontal = 20.dp)
                when (ui) {
                    ListUi.Loading -> {
                        if (segment == Segment.TOP) item(key = "hero_skeleton") { HeroSkeleton(gutter.padding(bottom = 12.dp)) }
                        items(SKELETON_ROWS) { RowSkeleton() }
                    }
                    ListUi.Failed -> item(key = "error") { ErrorCard(onRetry = { viewModel.show(segment.tab) }, modifier = gutter) }
                    ListUi.Empty -> item(key = "empty") { EmptyCard(segment, onPost = onPost, modifier = gutter) }
                    is ListUi.Ready -> {
                        if (showsChartPlay(segment, ui)) {
                            item(key = "chart_play") {
                                // In the order shown: the hero, then #2 on.
                                ChartPlay(starting = state.startingChart, onPlay = { viewModel.playChart(shuffle = it, posts = ui.chartPosts()) })
                            }
                        }
                        ui.hero?.let { hero ->
                            item(key = "hero") {
                                CommunityHero(
                                    post = hero,
                                    now = now,
                                    thisWeek = ui.thisWeek,
                                    onOpen = { onOpenPost(hero.id) },
                                    onVote = { viewModel.vote(hero, it) },
                                    modifier = gutter.padding(bottom = 8.dp),
                                )
                            }
                        }
                        items(ui.rows, key = { it.post.id }) { r ->
                            PostListRow(
                                rank = r.rank,
                                rankWidth = rankWidth,
                                post = r.post,
                                now = now,
                                onOpen = { onOpenPost(r.post.id) },
                                onVote = { viewModel.vote(r.post, it) },
                                ownList = segment == Segment.MINE,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Play all / Shuffle: a taste of the chart (see CommunityChartPlayer), shaped as a playlist page's buttons, Play all
 * in the album colour like the page's other accents (the segment, a lit vote). Opening the first posts takes a
 * moment; the strip under them keeps its space so the list doesn't jump.
 */
@Composable
private fun ChartPlay(starting: Boolean, onPlay: (shuffle: Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val chrome = LocalAlbumChrome.current
            Button(
                onClick = { onPlay(false) },
                enabled = !starting,
                colors = ButtonDefaults.buttonColors(containerColor = chrome.accent, contentColor = chrome.onAccent),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 12.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.community_play_all))
            }
            OutlinedButton(
                onClick = { onPlay(true) },
                enabled = !starting,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 12.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.community_shuffle))
            }
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(3.dp)) {
            if (starting) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

/** Skeleton rows while a list loads: about a screenful. */
private const val SKELETON_ROWS = 6
