package com.stash.feature.home.shelves

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.ui.components.AlbumSquareCard
import com.stash.core.ui.components.SectionHeader
import com.stash.core.ui.components.bottomChromePadding
import com.stash.data.ytmusic.model.AlbumSource
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.MoodPlaylist
import com.stash.feature.home.R

/**
 * A mood's page, opened from a tile of Home's "Moods & moments": YouTube Music's own rows of playlists for it
 * ("Coffee shop blends", "Unwind + explore"...), in YouTube's order. A playlist opens on the album page
 * ([onOpenPlaylist], as a [AlbumSource.YOUTUBE_PLAYLIST]), which plays and downloads it like an album.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodScreen(
    onBack: () -> Unit,
    onOpenPlaylist: (AlbumSummary) -> Unit,
    viewModel: MoodViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.mood_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.failed -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.mood_load_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = viewModel::retry) { Text(stringResource(R.string.mood_retry)) }
                }
                state.shelves.isEmpty() -> Text(
                    text = stringResource(R.string.mood_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
                else -> LazyColumn(contentPadding = PaddingValues(bottom = bottomChromePadding(16.dp))) {
                    // YouTube's rows as they came: a static list, so no keys (two rows may share a title)
                    items(state.shelves) { shelf ->
                        Column {
                            SectionHeader(title = shelf.title)
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(shelf.playlists, key = { it.id }) { playlist ->
                                    AlbumSquareCard(
                                        title = playlist.title,
                                        artist = playlist.subtitle,
                                        thumbnailUrl = playlist.thumbnailUrl,
                                        year = null,
                                        onClick = { onOpenPlaylist(playlist.toAlbumNav()) },
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

/** A mood's playlist as the album page opens it: the playlist's browse id, with YouTube's maker as its artist. */
internal fun MoodPlaylist.toAlbumNav(): AlbumSummary = AlbumSummary(
    id = id,
    title = title,
    artist = subtitle,
    thumbnailUrl = thumbnailUrl,
    year = null,
    source = AlbumSource.YOUTUBE_PLAYLIST,
)
