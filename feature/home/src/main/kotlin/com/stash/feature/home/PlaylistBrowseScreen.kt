package com.stash.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.ui.components.AlbumSquareCard
import com.stash.data.ytmusic.model.AlbumSource
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.PlaylistSummary

/**
 * "See all" browse of the Qobuz editorial playlist catalog — a paginated
 * 2-column grid that loads more as the user scrolls. Reached from the Home
 * "Qobuz Playlists" row header; tapping a playlist opens it via the shared
 * album-detail screen (QOBUZ_PLAYLIST source).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistBrowseScreen(
    onBack: () -> Unit,
    onNavigateToAlbum: (AlbumSummary) -> Unit,
    viewModel: PlaylistBrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()

    // Infinite scroll: fetch the next page once the tail of the loaded set
    // scrolls into view.
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.playlists.size - 6
        }
    }
    LaunchedEffect(shouldLoadMore, state.playlists.size) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    val title = if (viewModel.genreLabel == "All") "Qobuz Playlists"
    else "Qobuz Playlists · ${viewModel.genreLabel}"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            items(state.playlists) { playlist ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AlbumSquareCard(
                        title = playlist.title,
                        artist = "${playlist.trackCount} tracks",   // curator is always "Qobuz" — show count instead
                        thumbnailUrl = playlist.thumbnailUrl,
                        year = null,
                        isLossless = true,
                        onClick = { onNavigateToAlbum(playlist.toAlbumNav()) },
                    )
                }
            }
            if (state.isLoading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

/** A Qobuz playlist opens through the shared album screen via QOBUZ_PLAYLIST. */
internal fun PlaylistSummary.toAlbumNav() = AlbumSummary(
    id = id,
    title = title,
    artist = curator,
    thumbnailUrl = thumbnailUrl,
    year = null,
    source = AlbumSource.QOBUZ_PLAYLIST,
)
