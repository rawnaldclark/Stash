package com.stash.feature.search

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.ui.components.AlbumsRowSkeleton
import com.stash.core.ui.components.DiscoveryErrorCard
import com.stash.core.ui.components.PopularListSkeleton
import com.stash.core.ui.components.SectionHeader
import com.stash.data.ytmusic.model.AlbumSummary
import kotlinx.coroutines.flow.merge

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
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm) {
        merge(
            vm.userMessages,
            vm.delegate.userMessages,
        ).collect { message -> snackbar.showSnackbar(message) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = 96.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            item {
                ArtistHero(
                    hero = state.hero,
                    status = state.status,
                    onBack = onBack,
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
                        onPreview = vm.delegate::previewTrack,
                        onStopPreview = vm.delegate::stopPreview,
                        onDownload = { vm.delegate.downloadTrack(it.toTrackItem()) },
                        onNavigateToAlbum = onNavigateToAlbum,
                        onNavigateToArtist = onNavigateToArtist,
                    )
                }
                ArtistProfileStatus.Fresh,
                ArtistProfileStatus.Stale -> contentSections(
                    state = state,
                    previewState = previewState,
                    downloadingIds = downloadingIds,
                    downloadedIds = downloadedIds,
                    previewLoadingId = previewLoadingId,
                    onPreview = vm.delegate::previewTrack,
                    onStopPreview = vm.delegate::stopPreview,
                    onDownload = { vm.delegate.downloadTrack(it.toTrackItem()) },
                    onNavigateToAlbum = onNavigateToAlbum,
                    onNavigateToArtist = onNavigateToArtist,
                )
            }
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
    onPreview: (String) -> Unit,
    onStopPreview: () -> Unit,
    onDownload: (SearchResultItem) -> Unit,
    onNavigateToAlbum: (album: AlbumSummary) -> Unit,
    onNavigateToArtist: (artistId: String, name: String, avatarUrl: String?) -> Unit,
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
                onPreview = onPreview,
                onStopPreview = onStopPreview,
                onDownload = onDownload,
            )
        }
    }
    if (state.albums.isNotEmpty()) {
        item { SectionHeader(title = "Albums") }
        item {
            AlbumsRow(
                albums = state.albums,
                onClick = onNavigateToAlbum,
            )
        }
    }
    if (state.singles.isNotEmpty()) {
        item { SectionHeader(title = "Singles & EPs") }
        item {
            SinglesRow(
                singles = state.singles,
                onClick = onNavigateToAlbum,
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
}
