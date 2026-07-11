package com.stash.feature.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.sync.components.FailedDownloadsGroupCard
import com.stash.core.ui.R

/**
 * Top-level screen for the Failed Downloads viewer.
 *
 * Consumes [FailedDownloadsViewModel.uiState] and renders one of three modes:
 *
 * - **Loading** — initial [stateIn] seed; a centered progress spinner with the
 *   back chip pinned top-left so the user can bail out before data arrives.
 * - **Empty** — no failed downloads in any category; the "All caught up!"
 *   empty state mirrors [FailedMatchesScreen]'s celebration UI.
 * - **Content** — a [LazyColumn] of [FailedDownloadsGroupCard]s, prefixed by a
 *   header (title, count subtitle, "Retry all" button) and the back chip.
 *
 * @param onBack    Invoked when the back chip is tapped.
 * @param viewModel Injected via Hilt; supplies grouped failure data + actions.
 */
@Composable
fun FailedDownloadsScreen(
    onBack: () -> Unit,
    viewModel: FailedDownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            state.isLoading -> {
                BackChip(onBack = onBack)
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            state.groups.isEmpty() -> {
                BackChip(onBack = onBack)
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.label_all_caught_up),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.label_no_failed_downloads),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp),
                ) {
                    item(key = "header") {
                        Header(
                            totalCount = state.totalCount,
                            onBack = onBack,
                            onRetryAll = viewModel::retryAll,
                        )
                    }
                    items(items = state.groups, key = { it.type.name }) { group ->
                        FailedDownloadsGroupCard(
                            group = group,
                            onRetryRow = viewModel::retry,
                            onBlockRow = viewModel::block,
                            onRetryGroup = viewModel::retryGroup,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Glass back chip pinned to the top-leading edge — matches the same primitive
 * used inside [Header] and [FailedMatchesScreen] so loading / empty / content
 * states share an identical back-button position.
 */
@Composable
private fun BackChip(onBack: () -> Unit) {
    val extendedColors = StashTheme.extendedColors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 8.dp, top = 8.dp),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(48.dp)
                .background(
                    color = extendedColors.glassBackground,
                    shape = CircleShape,
                ),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.cd_back),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Header card displayed above the group list: back chip, screen title, a
 * pluralized count subtitle, and a "Retry all" outlined button.
 *
 * @param totalCount The sum of [FailedDownloadsGroup.rows.size] across every
 *                   visible group — surfaced to the user as "N tracks couldn't
 *                   download."
 * @param onBack     Invoked when the embedded [BackChip] is tapped.
 * @param onRetryAll Invoked when the "Retry all" button is tapped; should call
 *                   [FailedDownloadsViewModel.retryAll].
 */
@Composable
private fun Header(
    totalCount: Int,
    onBack: () -> Unit,
    onRetryAll: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        BackChip(onBack = onBack)
        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = stringResource(R.string.title_failed_downloads),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.label_failed_downloads_header, totalCount, if (totalCount != 1) "s" else ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onRetryAll,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.Refresh, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_retry_all))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
