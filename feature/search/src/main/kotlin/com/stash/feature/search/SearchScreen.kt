package com.stash.feature.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.merge
import com.stash.core.media.preview.PreviewState
import com.stash.core.ui.components.AlbumSquareCard
import com.stash.core.ui.components.ArtistAvatarCard
import com.stash.core.ui.components.SectionHeader
import com.stash.core.ui.components.ShimmerPlaceholder
import com.stash.core.ui.theme.StashTheme
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.ArtistSummary
import com.stash.data.ytmusic.model.SearchResultSection
import com.stash.data.ytmusic.model.TopResultItem
import com.stash.data.ytmusic.model.TrackSummary

/**
 * Top-level search screen composable.
 *
 * Task 9 rewired the body: results now render as four ordered sections
 * (Top / Songs / Artists / Albums) driven off [SearchStatus]. The
 * snackbar host listens for [SearchViewModel.userMessages] so search
 * failures surface as toasts without flipping the entire screen into an
 * error state.
 */
@Composable
fun SearchScreen(
    onNavigateToArtist: (artistId: String, name: String, avatarUrl: String?) -> Unit,
    onNavigateToAlbum: (album: AlbumSummary) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val previewState by viewModel.delegate.previewState.collectAsStateWithLifecycle()
    val downloadingIds by viewModel.delegate.downloadingIds.collectAsStateWithLifecycle()
    val downloadedIds by viewModel.delegate.downloadedIds.collectAsStateWithLifecycle()
    val previewLoadingId by viewModel.delegate.previewLoadingId.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        merge(
            viewModel.userMessages,
            viewModel.delegate.userMessages,
        ).collect { msg -> snackbarHostState.showSnackbar(msg) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding(),
        ) {
            SearchBar(
                query = state.query,
                onQueryChanged = viewModel::onQueryChanged,
                onClear = { viewModel.onQueryChanged("") },
            )

            when (val status = state.status) {
                SearchStatus.Idle -> EmptySearchPrompt()
                SearchStatus.Loading -> LoadingSkeletons()
                is SearchStatus.Results -> SectionedResultsList(
                    sections = status.sections,
                    downloadingIds = downloadingIds,
                    downloadedIds = downloadedIds,
                    previewLoadingId = previewLoadingId,
                    previewState = previewState,
                    onArtistClick = { a -> onNavigateToArtist(a.id, a.name, a.avatarUrl) },
                    onAlbumClick = onNavigateToAlbum,
                    onTopTrackClick = { t -> viewModel.delegate.previewTrack(t.videoId) },
                    onPreview = { viewModel.delegate.previewTrack(it) },
                    onStopPreview = viewModel.delegate::stopPreview,
                    onDownload = { t -> viewModel.delegate.downloadTrack(t.toTrackItem()) },
                )
                SearchStatus.Empty -> NoResultsMessage()
                is SearchStatus.Error -> ErrorMessage(status.message)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Search bar
// ---------------------------------------------------------------------------

@Composable
private fun SearchBar(
    query: String,
    onQueryChanged: (String) -> Unit,
    onClear: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .focusRequester(focusRequester),
        placeholder = {
            Text(
                text = "Search songs, artists...",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = StashTheme.extendedColors.glassBackground,
            unfocusedContainerColor = StashTheme.extendedColors.glassBackground,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = StashTheme.extendedColors.glassBorder,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = { keyboardController?.hide() },
        ),
    )

    // Auto-focus the search field when the screen opens
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

// ---------------------------------------------------------------------------
// Sectioned results list
// ---------------------------------------------------------------------------

/**
 * Renders the four-section Search result body — Top / Songs / Artists /
 * Albums, in that fixed order. Empty sections are simply skipped by the
 * backing [YTMusicApiClient.searchAll] parser, so this composable only
 * needs to pattern-match the kinds it actually receives.
 */
@Composable
private fun SectionedResultsList(
    sections: List<SearchResultSection>,
    downloadingIds: Set<String>,
    downloadedIds: Set<String>,
    previewLoadingId: String?,
    previewState: PreviewState,
    onArtistClick: (ArtistSummary) -> Unit,
    onAlbumClick: (AlbumSummary) -> Unit,
    onTopTrackClick: (TrackSummary) -> Unit,
    onPreview: (String) -> Unit,
    onStopPreview: () -> Unit,
    onDownload: (TrackSummary) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        sections.forEach { section ->
            when (section) {
                is SearchResultSection.Top -> item(key = "top") {
                    val top = section.item
                    if (top is TopResultItem.TrackTop) {
                        val videoId = top.track.videoId
                        TopResultCard(
                            item = top,
                            onArtistClick = onArtistClick,
                            onTrackPlay = onTopTrackClick,
                            isDownloading = videoId in downloadingIds,
                            isDownloaded = videoId in downloadedIds,
                            isPreviewLoading = previewLoadingId == videoId,
                            isPreviewPlaying = previewState is PreviewState.Playing &&
                                previewState.videoId == videoId,
                            onPreview = { onPreview(videoId) },
                            onStopPreview = onStopPreview,
                            onDownload = { onDownload(top.track) },
                        )
                    } else {
                        TopResultCard(
                            item = top,
                            onArtistClick = onArtistClick,
                            onTrackPlay = onTopTrackClick,
                        )
                    }
                }
                is SearchResultSection.Songs -> {
                    item(key = "songs_header") { SectionHeader("Songs") }
                    items(section.tracks, key = { "song_" + it.videoId }) { t ->
                        val item = t.toSearchResultItem()
                        PreviewDownloadRow(
                            item = item,
                            isDownloading = t.videoId in downloadingIds,
                            isDownloaded = t.videoId in downloadedIds,
                            isPreviewLoading = previewLoadingId == t.videoId,
                            isPreviewPlaying = previewState is PreviewState.Playing &&
                                previewState.videoId == t.videoId,
                            onPreview = { onPreview(t.videoId) },
                            onStopPreview = onStopPreview,
                            onDownload = { onDownload(t) },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                is SearchResultSection.Artists -> {
                    item(key = "artists_header") { SectionHeader("Artists") }
                    item(key = "artists_row") {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                        ) {
                            items(section.artists, key = { it.id }) { a ->
                                ArtistAvatarCard(
                                    name = a.name,
                                    avatarUrl = a.avatarUrl,
                                    onClick = { onArtistClick(a) },
                                )
                            }
                        }
                    }
                }
                is SearchResultSection.Albums -> {
                    item(key = "albums_header") { SectionHeader("Albums") }
                    item(key = "albums_row") {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                        ) {
                            items(section.albums, key = { it.id }) { a ->
                                AlbumSquareCard(
                                    title = a.title,
                                    artist = a.artist,
                                    thumbnailUrl = a.thumbnailUrl,
                                    year = a.year,
                                    onClick = { onAlbumClick(a) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Top-result card
// ---------------------------------------------------------------------------

/**
 * Tall "Top result" card — mirrors the InnerTube musicCardShelfRenderer.
 *
 * The card's kind is discriminated by [TopResultItem]: artist tops show
 * an avatar + name + "Artist" chip and navigate to the artist profile,
 * track tops show the thumbnail + title + artist + "Song" chip and start
 * a preview when tapped. Polish + proper animations ship in Task 11.
 */
@Composable
private fun TopResultCard(
    item: TopResultItem,
    onArtistClick: (ArtistSummary) -> Unit,
    onTrackPlay: (TrackSummary) -> Unit,
    // new — only consulted when item is TrackTop
    isDownloading: Boolean = false,
    isDownloaded: Boolean = false,
    isPreviewLoading: Boolean = false,
    isPreviewPlaying: Boolean = false,
    onPreview: () -> Unit = {},
    onStopPreview: () -> Unit = {},
    onDownload: () -> Unit = {},
) {
    val extendedColors = StashTheme.extendedColors
    val clickMod = when (item) {
        is TopResultItem.ArtistTop -> Modifier.clickable { onArtistClick(item.artist) }
        is TopResultItem.TrackTop -> Modifier.clickable { onTrackPlay(item.track) }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("TopResultCard")
            .clip(RoundedCornerShape(16.dp))
            .background(extendedColors.glassBackground)
            .then(clickMod)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(extendedColors.elevatedSurface),
            contentAlignment = Alignment.Center,
        ) {
            val thumb = when (item) {
                is TopResultItem.ArtistTop -> item.artist.avatarUrl
                is TopResultItem.TrackTop -> item.track.thumbnailUrl
            }
            if (thumb != null) {
                AsyncImage(
                    model = thumb,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                val icon = if (item is TopResultItem.ArtistTop) {
                    Icons.Default.Person
                } else {
                    Icons.Default.MusicNote
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            val (primary, secondary, kind) = when (item) {
                is TopResultItem.ArtistTop ->
                    Triple(item.artist.name, null, "Artist")
                is TopResultItem.TrackTop ->
                    Triple(item.track.title, item.track.artist, "Song")
            }
            Text(
                text = kind,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = primary,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (secondary != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (item is TopResultItem.TrackTop) {
            Spacer(Modifier.width(8.dp))

            // Preview button — mirrors PreviewDownloadRow's control
            IconButton(
                onClick = if (isPreviewPlaying) onStopPreview else onPreview,
                modifier = Modifier.size(40.dp),
            ) {
                when {
                    isPreviewLoading -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    isPreviewPlaying -> Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop preview",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    else -> Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Preview",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Download action — mirrors PreviewDownloadRow's control
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isDownloaded -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Downloaded",
                            modifier = Modifier.size(24.dp),
                            tint = extendedColors.success,
                        )
                    }
                    isDownloading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    else -> {
                        IconButton(onClick = onDownload) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Download",
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Loading / empty / error views
// ---------------------------------------------------------------------------

/**
 * Six stacked shimmer placeholders standing in for song rows while a
 * `searchAll` call is in flight.
 */
@Composable
private fun LoadingSkeletons() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(6) {
            ShimmerPlaceholder(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(12.dp),
            )
        }
    }
}

@Composable
private fun ErrorMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.SearchOff,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Search failed",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoResultsMessage() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.SearchOff,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No results found",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Try a different search term",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptySearchPrompt() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Search YouTube Music",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Find any song or artist and download it to your library",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
