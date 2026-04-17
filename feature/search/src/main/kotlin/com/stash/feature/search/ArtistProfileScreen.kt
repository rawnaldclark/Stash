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
import com.stash.core.ui.components.PopularListSkeleton
import com.stash.core.ui.components.SectionHeader

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
 *
 * `userMessages` from the VM are surfaced through a local [Scaffold]'s
 * Snackbar host — refresh failures show a one-liner but the cached data
 * keeps rendering underneath, matching §3.4's stale-while-revalidate UX.
 */
@Composable
fun ArtistProfileScreen(
    @Suppress("UNUSED_PARAMETER") onBack: () -> Unit,
    onNavigateToAlbum: (albumName: String, artistName: String) -> Unit,
    onNavigateToArtist: (artistId: String, name: String, avatarUrl: String?) -> Unit,
    vm: ArtistProfileViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm) {
        vm.userMessages.collect { message -> snackbar.showSnackbar(message) }
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
            item { ArtistHero(hero = state.hero, status = state.status) }

            if (state.status is ArtistProfileStatus.Loading && state.popular.isEmpty()) {
                item { PopularListSkeleton() }
                item { AlbumsRowSkeleton() }
            } else {
                if (state.popular.isNotEmpty()) {
                    item { SectionHeader(title = "Popular") }
                    item { PopularTracksSection(tracks = state.popular) }
                }
                if (state.albums.isNotEmpty()) {
                    item { SectionHeader(title = "Albums") }
                    item {
                        AlbumsRow(
                            albums = state.albums,
                            onClick = { onNavigateToAlbum(it.title, state.hero.name) },
                        )
                    }
                }
                if (state.singles.isNotEmpty()) {
                    item { SectionHeader(title = "Singles & EPs") }
                    item {
                        SinglesRow(
                            singles = state.singles,
                            onClick = { onNavigateToAlbum(it.title, state.hero.name) },
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
        }
    }
}
