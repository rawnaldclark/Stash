package com.stash.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.ui.R
import com.stash.feature.settings.components.SettingsGroupCard
import com.stash.feature.settings.components.SettingsNavRow
import com.stash.feature.settings.components.SettingsSearchField
import com.stash.feature.settings.components.SupportBanner

/**
 * The Settings hub — the host for `composable<SettingsRoute>`.
 *
 * This is the hub of the hub-and-spoke Settings redesign: a pinned Support
 * banner, a cross-settings search pill, and six category rows. Each row shows a
 * current-state subtitle (derived purely by [settingsHubSummaries]) plus a thin
 * leading icon and a trailing chevron, and navigates to one of the six category
 * "spoke" screens (built in later tasks) via the `onOpen*` callbacks.
 *
 * Pure host: it owns no navigation itself — the caller wires every `onOpen*`,
 * [onDonate], and [onStar]. It shares the existing [SettingsViewModel] so the
 * subtitles reflect live playback/quality/account/library/appearance/version
 * state.
 */
@Composable
fun SettingsHubScreen(
    onOpenPlayback: () -> Unit,
    onOpenAudioQuality: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenLibraryStorage: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenAbout: () -> Unit,
    onDonate: () -> Unit,
    onStar: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val streamingEnabled by viewModel.streamingEnabled.collectAsStateWithLifecycle()
    val streamOnCellular by viewModel.streamOnCellular.collectAsStateWithLifecycle()

    // This module has no BuildConfig; read the version name from the package
    // manager exactly as the legacy SettingsScreen does, with the same fallback.
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0.3.5-beta.1"
    }

    val summaries = settingsHubSummaries(uiState, versionName, streamingEnabled, streamOnCellular)

    // Consume any pending deep-link focus from Home banners ("fix lossless" /
    // Last.fm nudge) once on entry and drill into the relevant spoke. The
    // monolith satisfied this by scrolling a single screen; in hub-and-spoke we
    // navigate to the category that owns the control instead.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        when (viewModel.consumeDeepLinkFocus()) {
            com.stash.core.data.navigation.SettingsFocus.LOSSLESS -> onOpenAudioQuality()
            com.stash.core.data.navigation.SettingsFocus.LASTFM -> onOpenAccounts()
            null -> Unit
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.title_settings),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        SupportBanner(onDonate = onDonate, onStar = onStar)
        SettingsSearchField()
        SettingsGroupCard(
            rows = listOf(
                {
                    SettingsNavRow(
                        title = stringResource(R.string.label_playback),
                        subtitle = summaries.playback,
                        leadingIcon = Icons.Rounded.PlayArrow,
                        onClick = onOpenPlayback,
                    )
                },
                {
                    SettingsNavRow(
                        title = stringResource(R.string.label_audio_quality),
                        subtitle = summaries.audioQuality,
                        leadingIcon = Icons.Rounded.GraphicEq,
                        onClick = onOpenAudioQuality,
                    )
                },
                {
                    SettingsNavRow(
                        title = stringResource(R.string.label_accounts_sync),
                        subtitle = summaries.accounts,
                        leadingIcon = Icons.Rounded.Person,
                        onClick = onOpenAccounts,
                    )
                },
                {
                    SettingsNavRow(
                        title = stringResource(R.string.label_library_storage),
                        subtitle = summaries.libraryStorage,
                        leadingIcon = Icons.Rounded.FolderOpen,
                        onClick = onOpenLibraryStorage,
                    )
                },
                {
                    SettingsNavRow(
                        title = stringResource(R.string.label_appearance),
                        subtitle = summaries.appearance,
                        leadingIcon = Icons.Rounded.Palette,
                        onClick = onOpenAppearance,
                    )
                },
                {
                    SettingsNavRow(
                        title = stringResource(R.string.label_about_help),
                        subtitle = summaries.about,
                        leadingIcon = Icons.Rounded.Info,
                        onClick = onOpenAbout,
                    )
                },
            ),
        )
    }
}
