package com.stash.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * "See all" browse of one Home mix rail — a full-screen 2-column grid of the
 * rail's mixes. Reached from a rail header's "See all"; tapping a card opens
 * the mix via the shared playlist-detail screen.
 *
 * Uses a fresh [HomeViewModel] scoped to this nav entry; its flows re-derive
 * the rails from the DB, so the rail's list is selected here by [rail].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixBrowseScreen(
    rail: MixRail,
    onBack: () -> Unit,
    onOpenMix: (Long) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val mixes = when (rail) {
        MixRail.MADE_FOR_YOU -> uiState.madeForYou
        MixRail.RADIOS -> uiState.radios
        MixRail.MOOD_DECADES -> uiState.moodDecades
        MixRail.YOUR_MIXES -> uiState.yourMixes
    }
    val title = when (rail) {
        MixRail.MADE_FOR_YOU -> "Made for you"
        MixRail.RADIOS -> "Radios"
        MixRail.MOOD_DECADES -> "Mood & decades"
        MixRail.YOUR_MIXES -> "Your mixes"
    }

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
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            items(mixes, key = { it.id }) { m ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    MixRailCard(
                        title = m.title,
                        artUrl = m.artUrl,
                        source = m.source,
                        buildState = m.buildState,
                        onClick = { onOpenMix(m.id) },
                    )
                }
            }
        }
    }
}
