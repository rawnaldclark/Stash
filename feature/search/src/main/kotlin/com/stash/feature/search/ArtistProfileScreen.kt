package com.stash.feature.search

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.core.view.WindowCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.media.preview.LosslessUrlPrefetcher
import com.stash.core.ui.components.AlbumsRowSkeleton
import com.stash.core.ui.components.DiscoveryErrorCard
import com.stash.core.ui.components.PopularListSkeleton
import com.stash.core.ui.components.SectionHeader
import com.stash.core.model.TrackItem
import com.stash.data.ytmusic.model.AlbumSummary
import kotlinx.coroutines.flow.merge
import com.stash.core.ui.components.bottomChromePadding

/**
 * Artist Profile screen.
 *
 * Layout (top → bottom):
 *  1. [ArtistHero] — paints from nav args on first frame.
 *  2. "Popular"   — up to 5 [PreviewDownloadRow]s from `popular`.
 *  3. "Albums"    — [AlbumsRow] horizontal rail.
 *  4. "Singles & EPs" — [SinglesRow] horizontal rail.
 *  5. "Fans also like" — [RelatedArtistsRow].
 *
 * While the first cache emission is in flight the sections render
 * shimmer skeletons rather than jumping layout when the data arrives.
 * If the cold cache miss throws, the hero keeps painting from nav args
 * and the shelves are replaced by [DiscoveryErrorCard] with a Retry
 * button (spec §6.2).
 *
 * `userMessages` from the VM are surfaced through a local [Scaffold]'s
 * Snackbar host — refresh failures show a one-liner but the cached data
 * keeps rendering underneath, matching §3.4's stale-while-revalidate UX.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ArtistProfileScreen(
    onBack: () -> Unit,
    onNavigateToAlbum: (album: AlbumSummary) -> Unit,
    onNavigateToArtist: (artistId: String, name: String, avatarUrl: String?) -> Unit,
    vm: ArtistProfileViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val previewState by vm.delegate.previewState.collectAsStateWithLifecycle()
    val downloadingIds by vm.delegate.downloadingIds.collectAsStateWithLifecycle()
    val downloadedIds by vm.delegate.downloadedIds.collectAsStateWithLifecycle()
    val previewLoadingId by vm.delegate.previewLoadingId.collectAsStateWithLifecycle()
    val currentPlayingYoutubeId by vm.currentPlayingYoutubeId.collectAsStateWithLifecycle()
    val streamingEnabled by vm.streamingEnabled.collectAsStateWithLifecycle()
    var showStreamingSheet by rememberSaveable { mutableStateOf(false) }
    val streamingSheetState = androidx.compose.material3.rememberModalBottomSheetState()
    val playlistSheetItem by vm.playlistSheetItem.collectAsStateWithLifecycle()
    val userPlaylists by vm.userPlaylists.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm) {
        merge(
            vm.userMessages,
            vm.delegate.userMessages,
        ).collect { message -> snackbar.showSnackbar(message) }
    }

    // Tap-to-album focus: when the Now Playing screen navigated here with a
    // focusAlbum, locate it in the Albums/Singles shelves and bring it into
    // view. Two-axis: the outer LazyColumn scrolls to the section (below the
    // hero + Popular, i.e. off-screen on landing) so the inner rail's own
    // scroll + highlight (see AlbumsRow) is actually visible.
    val listState = rememberLazyListState()
    val focus = remember(state.focusAlbum, state.albums, state.singles) {
        findAlbumFocus(state.focusAlbum, state.albums, state.singles)
    }
    // Outer LazyColumn item index of the section header to reveal. Section
    // order is fixed: hero(0); Popular header+row (if popular); Albums
    // header+row; Singles header+row.
    val sectionOuterIndex = remember(focus, state.popular, state.albums) {
        if (focus == null) return@remember null
        var idx = 1 // hero occupies index 0
        if (state.popular.isNotEmpty()) idx += 2 // Popular header + row
        when (focus.shelf) {
            AlbumShelf.ALBUMS -> idx
            AlbumShelf.SINGLES -> idx + if (state.albums.isNotEmpty()) 2 else 0
        }
    }
    var focusHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(sectionOuterIndex, state.status) {
        val target = sectionOuterIndex ?: return@LaunchedEffect
        if (focusHandled) return@LaunchedEffect
        if (state.status is ArtistProfileStatus.Fresh || state.status is ArtistProfileStatus.Stale) {
            focusHandled = true
            listState.animateScrollToItem(target)
        }
    }

    // The hero's photo runs under the status bar, so this screen takes no top inset; the
    // hero and the collapsed bar below pad for it themselves.
    val heroScrollPx = remember(listState) {
        { if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset else Int.MAX_VALUE }
    }
    val density = LocalDensity.current
    // The bar takes over before the name (set low on the photo, under the controls) can
    // scroll up into the pinned Back: about 240 dp of the hero sits below the name's top.
    val collapseAtPx = with(density) { (ArtistHeroPhotoHeight - 240.dp).roundToPx() }
    val collapsed by remember(listState, collapseAtPx) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > collapseAtPx }
    }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // Light status-bar icons while the photo is behind them; the theme's own once the bar is.
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    StatusBarIcons(light = !collapsed || darkTheme)

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = bottomChromePadding())) },
    ) { inner ->
      Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = bottomChromePadding(96.dp)),
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            item {
                ArtistHero(
                    hero = state.hero,
                    status = state.status,
                    socials = state.about?.socials.orEmpty(),
                    onPlayArtist = vm::playArtist,
                    onStartRadio = vm::startRadio,
                    streamingEnabled = streamingEnabled,
                    onStreamingClick = { showStreamingSheet = true },
                    scrollOffsetPx = heroScrollPx,
                )
            }

            when (val status = state.status) {
                is ArtistProfileStatus.Error -> item {
                    DiscoveryErrorCard(
                        title = "Couldn't load artist",
                        message = status.message,
                        onRetry = vm::retry,
                    )
                }
                ArtistProfileStatus.Loading -> if (state.popular.isEmpty()) {
                    item { PopularListSkeleton() }
                    item { AlbumsRowSkeleton() }
                } else {
                    contentSections(
                        state = state,
                        previewState = previewState,
                        downloadingIds = downloadingIds,
                        downloadedIds = downloadedIds,
                        previewLoadingId = previewLoadingId,
                    currentPlayingYoutubeId = currentPlayingYoutubeId,
                        losslessPrefetcher = vm.losslessPrefetcher,
                        onPreview = { track -> vm.delegate.previewTrack(track) },
                        onStopPreview = vm.delegate::stopPreview,
                        onDownload = { vm.delegate.downloadTrack(it.toTrackItem()) },
                        onPlayNext = vm::onPlayNext,
                        onAddToQueue = vm::onAddToQueue,
                        onStartRadio = vm::onStartRadio,
                        onRequestAddToPlaylist = vm::onRequestAddToPlaylist,
                        onNavigateToAlbum = onNavigateToAlbum,
                        onNavigateToArtist = onNavigateToArtist,
                        focus = focus,
                    )
                }
                ArtistProfileStatus.Fresh,
                ArtistProfileStatus.Stale -> contentSections(
                    state = state,
                    previewState = previewState,
                    downloadingIds = downloadingIds,
                    downloadedIds = downloadedIds,
                    previewLoadingId = previewLoadingId,
                    currentPlayingYoutubeId = currentPlayingYoutubeId,
                    losslessPrefetcher = vm.losslessPrefetcher,
                    onPreview = { track -> vm.delegate.previewTrack(track) },
                    onStopPreview = vm.delegate::stopPreview,
                    onDownload = { vm.delegate.downloadTrack(it.toTrackItem()) },
                    onPlayNext = vm::onPlayNext,
                    onAddToQueue = vm::onAddToQueue,
                    onStartRadio = vm::onStartRadio,
                    onRequestAddToPlaylist = vm::onRequestAddToPlaylist,
                    onNavigateToAlbum = onNavigateToAlbum,
                    onNavigateToArtist = onNavigateToArtist,
                    focus = focus,
                )
            }
        }

        // Once the photo has scrolled away: a compact bar with Back and the name.
        AnimatedVisibility(
            visible = collapsed,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ArtistCollapsedBar(name = state.hero.name, statusBarTop = statusBarTop)
        }
        // Back stays put the whole way down (glass coin, legible on the photo and the bar).
        Surface(
            onClick = onBack,
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.35f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = statusBarTop + 8.dp)
                .size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                )
            }
        }
      }

        if (showStreamingSheet) {
            com.stash.core.ui.components.streaming.StreamingModeSheet(
                streamingEnabled = streamingEnabled,
                onSelect = { requested ->
                    vm.applyStreamingMode(requested)
                    showStreamingSheet = false
                },
                onDismiss = { showStreamingSheet = false },
                sheetState = streamingSheetState,
            )
        }

        if (playlistSheetItem != null) {
            com.stash.core.ui.components.SaveToPlaylistSheet(
                playlists = userPlaylists.map {
                    com.stash.core.ui.components.PlaylistInfo(it.id, it.name, it.trackCount)
                },
                onSaveToPlaylist = vm::onSaveToPlaylist,
                onCreatePlaylist = vm::onCreatePlaylistAndAdd,
                onDismiss = vm::onDismissPlaylistSheet,
            )
        }
    }
}

/**
 * Shared helper for the Fresh / Stale / (populated) Loading branches so
 * the `when` in [ArtistProfileScreen] doesn't duplicate four `item { }`
 * blocks. Uses [LazyListScope]-style extension form so call sites read
 * naturally inside `LazyColumn { ... }`.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.contentSections(
    state: ArtistProfileUiState,
    previewState: com.stash.core.media.preview.PreviewState,
    downloadingIds: Set<String>,
    downloadedIds: Set<String>,
    previewLoadingId: String?,
    currentPlayingYoutubeId: String?,
    losslessPrefetcher: LosslessUrlPrefetcher,
    onPreview: (TrackItem) -> Unit,
    onStopPreview: () -> Unit,
    onDownload: (SearchResultItem) -> Unit,
    onPlayNext: (TrackItem) -> Unit,
    onAddToQueue: (TrackItem) -> Unit,
    onStartRadio: (TrackItem) -> Unit,
    onRequestAddToPlaylist: (TrackItem) -> Unit,
    onNavigateToAlbum: (album: AlbumSummary) -> Unit,
    onNavigateToArtist: (artistId: String, name: String, avatarUrl: String?) -> Unit,
    focus: AlbumFocusTarget?,
) {
    if (state.popular.isNotEmpty()) {
        item { SectionHeader(title = "Popular") }
        item {
            PopularTracksSection(
                tracks = state.popular,
                previewState = previewState,
                downloadingIds = downloadingIds,
                downloadedIds = downloadedIds,
                previewLoadingId = previewLoadingId,
                currentPlayingYoutubeId = currentPlayingYoutubeId,
                losslessPrefetcher = losslessPrefetcher,
                onPreview = onPreview,
                onStopPreview = onStopPreview,
                onDownload = onDownload,
                onPlayNext = onPlayNext,
                onAddToQueue = onAddToQueue,
                onStartRadio = onStartRadio,
                onRequestAddToPlaylist = onRequestAddToPlaylist,
            )
        }
    }
    if (state.albums.isNotEmpty()) {
        item { SectionHeader(title = "Albums") }
        item {
            AlbumsRow(
                albums = state.albums,
                onClick = onNavigateToAlbum,
                focusIndex = focus?.takeIf { it.shelf == AlbumShelf.ALBUMS }?.index,
            )
        }
    }
    if (state.singles.isNotEmpty()) {
        item { SectionHeader(title = "Singles & EPs") }
        item {
            SinglesRow(
                singles = state.singles,
                onClick = onNavigateToAlbum,
                focusIndex = focus?.takeIf { it.shelf == AlbumShelf.SINGLES }?.index,
            )
        }
    }
    if (state.related.isNotEmpty()) {
        item { SectionHeader(title = "Fans also like") }
        item {
            RelatedArtistsRow(
                artists = state.related,
                onClick = { onNavigateToArtist(it.id, it.name, it.avatarUrl) },
            )
        }
    }
    val about = state.about
    if (about != null) {
        item { SectionHeader(title = "About") }
        item { AboutSection(about = about, avatarUrl = state.hero.avatarUrl) }
    }
}

/** The bar that replaces the hero once it has scrolled off: the artist's name beside the pinned Back. */
@Composable
private fun ArtistCollapsedBar(name: String, statusBarTop: Dp) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                // Clears the pinned 40 dp Back coin at 12 dp, and matches its height.
                .padding(start = 64.dp, end = 16.dp, top = statusBarTop + 8.dp, bottom = 8.dp)
                .height(40.dp)
                .wrapContentHeight(Alignment.CenterVertically),
        )
    }
}

/**
 * Sets the status-bar icons light ([light]) or dark while this page shows, and
 * puts back what was there when it leaves.
 */
@Composable
private fun StatusBarIcons(light: Boolean) {
    val view = LocalView.current
    val controller = remember(view) {
        view.context.findActivity()?.window?.let { WindowCompat.getInsetsController(it, view) }
    }
    DisposableEffect(controller) {
        val before = controller?.isAppearanceLightStatusBars
        onDispose { if (before != null) controller.isAppearanceLightStatusBars = before }
    }
    SideEffect { controller?.isAppearanceLightStatusBars = !light }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
