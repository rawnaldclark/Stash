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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.outlined.Schedule
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.merge
import com.stash.core.media.preview.LosslessUrlPrefetcher
import com.stash.core.media.preview.PreviewState
import com.stash.core.model.TrackItem
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
    val waitingForLosslessIds by viewModel.delegate.waitingForLosslessIds.collectAsStateWithLifecycle()
    val previewLoadingId by viewModel.delegate.previewLoadingId.collectAsStateWithLifecycle()
    val tappedTrackId by viewModel.tappedTrackId.collectAsStateWithLifecycle()
    val playlistSheetItem by viewModel.playlistSheetItem.collectAsStateWithLifecycle()
    val userPlaylists by viewModel.userPlaylists.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    val currentPlayingYoutubeId by viewModel.currentPlayingYoutubeId.collectAsStateWithLifecycle()
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
            // Command hero: the page owns its name like Library/Sync do.
            Text(
                text = "Search",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 8.dp),
            )
            SearchBar(
                query = state.query,
                onQueryChanged = viewModel::onQueryChanged,
                onClear = { viewModel.onQueryChanged("") },
                onSearch = viewModel::onSearchCommitted,
            )

            when (val status = state.status) {
                SearchStatus.Idle -> RecentSearches(
                    entries = recentSearches,
                    onTap = { entry ->
                        viewModel.onRecentSearchTapped(entry)
                        // ARTIST recents navigate straight back to the profile.
                        if (entry.type == RecentSearch.Type.ARTIST && entry.artistId != null) {
                            onNavigateToArtist(entry.artistId, entry.text, entry.thumbnailUrl)
                        }
                    },
                    onRemove = viewModel::removeRecentSearch,
                    onClearAll = viewModel::clearRecentSearches,
                )
                SearchStatus.Loading -> LoadingSkeletons()
                is SearchStatus.Results -> SectionedResultsList(
                    sections = status.sections,
                    downloadingIds = downloadingIds,
                    downloadedIds = downloadedIds,
                    waitingForLosslessIds = waitingForLosslessIds,
                    previewLoadingId = previewLoadingId,
                    previewState = previewState,
                    tappedTrackId = tappedTrackId,
                    currentPlayingYoutubeId = currentPlayingYoutubeId,
                    losslessPrefetcher = viewModel.losslessPrefetcher,
                    onArtistClick = { a ->
                        // Opening a profile IS the committed search — record the
                        // artist (with avatar) so backing out still saved it.
                        viewModel.onArtistOpened(a.id, a.name, a.avatarUrl)
                        onNavigateToArtist(a.id, a.name, a.avatarUrl)
                    },
                    onAlbumClick = onNavigateToAlbum,
                    onTopTrackClick = { t -> viewModel.onResultTap(t.toTrackItem()) },
                    onPreview = { track -> viewModel.onResultTap(track) },
                    onStopPreview = viewModel.delegate::stopPreview,
                    onDownload = { t -> viewModel.onDownload(t.toTrackItem()) },
                    onPlayNext = viewModel::onPlayNext,
                    onAddToQueue = viewModel::onAddToQueue,
                    onStartRadio = viewModel::onStartRadio,
                    onRequestAddToPlaylist = viewModel::onRequestAddToPlaylist,
                    onVisibleSongIdsChanged = viewModel::prefetchVisible,
                )
                SearchStatus.Empty -> NoResultsMessage()
                is SearchStatus.Error -> ErrorMessage(status.message)
            }
        }

        if (playlistSheetItem != null) {
            com.stash.core.ui.components.SaveToPlaylistSheet(
                playlists = userPlaylists.map {
                    com.stash.core.ui.components.PlaylistInfo(it.id, it.name, it.trackCount)
                },
                onSaveToPlaylist = viewModel::onSaveToPlaylist,
                onCreatePlaylist = viewModel::onCreatePlaylistAndAdd,
                onDismiss = viewModel::onDismissPlaylistSheet,
            )
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
    onSearch: () -> Unit = {},
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Command-hero field: a soft plum tonal pill — the loud violet outline is
    // retired; focus is signaled by the cursor + keyboard, not a border.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val plum = if (dark) Color(0xFF7E6A90) else Color(0xFF6E5A7E)
    val tonal = plum.copy(alpha = if (dark) 0.22f else 0.10f)
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .focusRequester(focusRequester),
        placeholder = {
            Text(
                text = "Songs, artists, albums…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = plum,
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
        shape = RoundedCornerShape(28.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = tonal,
            unfocusedContainerColor = tonal,
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = { keyboardController?.hide(); onSearch() },
        ),
    )

    // Auto-focus the search field when the screen opens
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

// ---------------------------------------------------------------------------
// Recent searches (empty-state)
// ---------------------------------------------------------------------------

/**
 * Shown in the idle/empty state: the user's recent searches, most-recent-
 * first. Plain queries show a clock glyph; ARTIST entries show the circular
 * avatar and navigate straight back to the profile; TRACK entries show the
 * square art and re-run "artist title". The ✕ removes one; "Clear all"
 * empties the list. Falls back to [EmptySearchPrompt] when there are none.
 */
@Composable
private fun RecentSearches(
    entries: List<RecentSearch>,
    onTap: (RecentSearch) -> Unit,
    onRemove: (RecentSearch) -> Unit,
    onClearAll: () -> Unit,
) {
    if (entries.isEmpty()) {
        EmptySearchPrompt()
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "RECENT",
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClearAll) { Text("Clear") }
            }
        }
        items(entries, key = { "${it.type}:${it.text.lowercase()}" }) { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTap(entry) }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RecentSearchThumb(entry)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // TRACK keeps its artist line; ARTIST/QUERY need none —
                    // the thumb shape already carries the kind (circle=artist,
                    // square=song, clock=plain query).
                    if (entry.type == RecentSearch.Type.TRACK && entry.subtitle != null) {
                        Text(
                            text = entry.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = { onRemove(entry) }) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}


/**
 * Leading visual for a recents row: circular avatar for artists, rounded-
 * square art for tracks, and the clock glyph for plain queries (also the
 * fallback when an entry has no thumbnail URL).
 */
@Composable
private fun RecentSearchThumb(entry: RecentSearch) {
    val size = 44.dp
    if (entry.thumbnailUrl != null) {
        AsyncImage(
            model = entry.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(
                    if (entry.type == RecentSearch.Type.ARTIST) CircleShape
                    else RoundedCornerShape(8.dp),
                ),
        )
    } else {
        Box(
            modifier = Modifier.size(size),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@Composable
private fun SectionedResultsList(
    sections: List<SearchResultSection>,
    downloadingIds: Set<String>,
    downloadedIds: Set<String>,
    waitingForLosslessIds: Set<String>,
    previewLoadingId: String?,
    previewState: PreviewState,
    tappedTrackId: Long?,
    currentPlayingYoutubeId: String?,
    losslessPrefetcher: LosslessUrlPrefetcher,
    onArtistClick: (ArtistSummary) -> Unit,
    onAlbumClick: (AlbumSummary) -> Unit,
    onTopTrackClick: (TrackSummary) -> Unit,
    onPreview: (TrackItem) -> Unit,
    onStopPreview: () -> Unit,
    onDownload: (TrackSummary) -> Unit,
    onPlayNext: (TrackItem) -> Unit = {},
    onAddToQueue: (TrackItem) -> Unit = {},
    onStartRadio: (TrackItem) -> Unit = {},
    onRequestAddToPlaylist: (TrackItem) -> Unit = {},
    onVisibleSongIdsChanged: (List<String>) -> Unit = {},
) {
    val listState = rememberLazyListState()

    // Scroll-driven preview prefetch. Keys are set as "song_<videoId>" on
    // each Songs row below, so we can derive visible video ids directly
    // from layoutInfo without ferrying a flat tracks list into this
    // composable. 200ms debounce absorbs fast scroll without spamming
    // the extractor; distinctUntilChanged filters no-op emissions when
    // the visible set hasn't changed since the last tick.
    LaunchedEffect(listState, sections) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                (info.key as? String)?.takeIf { it.startsWith("song_") }
                    ?.removePrefix("song_")
            }
        }
            .debounce(200)
            .distinctUntilChanged()
            .collect { ids ->
                if (ids.isNotEmpty()) onVisibleSongIdsChanged(ids)
            }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        sections.forEach { section ->
            when (section) {
                is SearchResultSection.Top -> item(key = "top") {
                    val top = section.item
                    if (top is TopResultItem.TrackTop) {
                        val videoId = top.track.videoId
                        // Warm the lossless URL cache for the top-result track as
                        // soon as the card enters composition.
                        LaunchedEffect(videoId) {
                            losslessPrefetcher.warmUp(top.track.toTrackItem())
                        }
                        TopResultCard(
                            item = top,
                            onArtistClick = onArtistClick,
                            onTrackPlay = onTopTrackClick,
                            isDownloading = videoId in downloadingIds,
                            isDownloaded = videoId in downloadedIds,
                            isPreviewLoading = previewLoadingId == videoId,
                            isPreviewPlaying = previewState is PreviewState.Playing &&
                                previewState.videoId == videoId,
                            isResolving = (videoId.hashCode().toLong() == tappedTrackId),
                            onPreview = { onPreview(top.track.toTrackItem()) },
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
                        // Warm the lossless URL cache for each song row as it
                        // scrolls into view — idempotent, safe to call on every
                        // recomposition (LosslessUrlPrefetcher dedupes by videoId).
                        LaunchedEffect(t.videoId) {
                            losslessPrefetcher.warmUp(t.toTrackItem())
                        }
                        SongRow(
                            item = item,
                            isDownloading = t.videoId in downloadingIds,
                            isDownloaded = t.videoId in downloadedIds,
                            isWaitingForLossless = t.videoId in waitingForLosslessIds,
                            isPreviewLoading = previewLoadingId == t.videoId,
                            isPreviewPlaying = previewState is PreviewState.Playing &&
                                previewState.videoId == t.videoId,
                            isResolving = (t.videoId.hashCode().toLong() == tappedTrackId),
                            isPlaying = isRowPlaying(t.videoId, currentPlayingYoutubeId),
                            onPlay = { onPreview(t.toTrackItem()) },
                            onStopPreview = onStopPreview,
                            onDownload = { onDownload(t) },
                            onPlayNext = { onPlayNext(t.toTrackItem()) },
                            onAddToQueue = { onAddToQueue(t.toTrackItem()) },
                            onStartRadio = { onStartRadio(t.toTrackItem()) },
                            onAddToPlaylist = { onRequestAddToPlaylist(t.toTrackItem()) },
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
    isResolving: Boolean = false,
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
                    isPreviewLoading || isResolving -> CircularProgressIndicator(
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
