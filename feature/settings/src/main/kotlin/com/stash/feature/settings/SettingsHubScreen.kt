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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.feature.settings.components.SettingsGroupCard
import com.stash.feature.settings.components.SettingsNavRow
import com.stash.feature.settings.components.SettingsSearchField
import com.stash.feature.settings.components.SupportBanner
import com.stash.core.ui.components.bottomChromePadding

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
 * [onOpenUrl] and [onStar]. It shares the existing [SettingsViewModel] so the
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
    onOpenUrl: (String) -> Unit,
    onOpenLosslessSources: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenHomeLayout: () -> Unit,
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

    val listenBrainz by viewModel.listenBrainzState.collectAsStateWithLifecycle()
    val cloud: com.stash.feature.settings.cloud.CloudStorageViewModel = hiltViewModel()
    val cloudAccounts by cloud.accounts.collectAsStateWithLifecycle()
    val otherAccounts = buildList {
        if (listenBrainz is com.stash.feature.settings.components.ListenBrainzState.Connected) add("ListenBrainz")
        cloudAccounts.map { it.account.provider.displayName }.distinct().forEach(::add)
    }
    val summaries = settingsHubSummaries(uiState, versionName, streamingEnabled, streamOnCellular, otherAccounts)

    var searchQuery by remember { mutableStateOf("") }
    val searchIndex = remember(context) { settingsSearchIndex(context) }
    val pageTitles = remember(context) {
        mapOf(
            SettingsPage.PLAYBACK to context.getString(R.string.settings_playback_title),
            SettingsPage.AUDIO to context.getString(R.string.settings_audio_quality_title),
            SettingsPage.LOSSLESS_SOURCES to context.getString(R.string.settings_audio_quality_title) +
                " › " + context.getString(R.string.settings_lossless_sources_title),
            SettingsPage.EQUALIZER to context.getString(R.string.settings_audio_quality_title) +
                " › " + context.getString(R.string.settings_equalizer_title),
            SettingsPage.ACCOUNTS to context.getString(R.string.settings_accounts_sync_title),
            SettingsPage.LIBRARY to context.getString(R.string.settings_library_storage_title),
            SettingsPage.APPEARANCE to context.getString(R.string.settings_appearance_title),
            SettingsPage.HOME_LAYOUT to context.getString(R.string.settings_appearance_title) +
                " › " + context.getString(R.string.settings_home_layout_section),
            SettingsPage.ABOUT to context.getString(R.string.settings_about_title),
        )
    }
    val searchResults = remember(searchQuery, searchIndex) {
        searchSettings(searchQuery, searchIndex) { pageTitles.getValue(it) }
    }
    fun open(page: SettingsPage) = when (page) {
        SettingsPage.PLAYBACK -> onOpenPlayback()
        SettingsPage.AUDIO -> onOpenAudioQuality()
        SettingsPage.LOSSLESS_SOURCES -> onOpenLosslessSources()
        SettingsPage.EQUALIZER -> onOpenEqualizer()
        SettingsPage.ACCOUNTS -> onOpenAccounts()
        SettingsPage.LIBRARY -> onOpenLibraryStorage()
        SettingsPage.APPEARANCE -> onOpenAppearance()
        SettingsPage.HOME_LAYOUT -> onOpenHomeLayout()
        SettingsPage.ABOUT -> onOpenAbout()
    }
    fun iconOf(page: SettingsPage) = when (page) {
        SettingsPage.PLAYBACK -> Icons.Rounded.PlayArrow
        SettingsPage.AUDIO, SettingsPage.LOSSLESS_SOURCES, SettingsPage.EQUALIZER -> Icons.Rounded.GraphicEq
        SettingsPage.ACCOUNTS -> Icons.Rounded.Person
        SettingsPage.LIBRARY -> Icons.Rounded.FolderOpen
        SettingsPage.APPEARANCE, SettingsPage.HOME_LAYOUT -> Icons.Rounded.Palette
        SettingsPage.ABOUT -> Icons.Rounded.Info
    }

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
            .padding(horizontal = 16.dp, vertical = 16.dp)
            // Edge to edge under the glass mini player + tab bar.
            .padding(bottom = bottomChromePadding()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // No "Settings" title: the tab bar already says where you are.
        SupportBanner(onOpenUrl = onOpenUrl, onStar = onStar)
        SettingsSearchField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
        )
        if (searchQuery.isNotBlank()) {
            if (searchResults.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_search_no_results, searchQuery),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            } else {
                SettingsGroupCard(
                    rows = searchResults.map { result ->
                        {
                            SettingsNavRow(
                                title = result.title,
                                subtitle = pageTitles.getValue(result.page),
                                leadingIcon = iconOf(result.page),
                                onClick = {
                                    searchQuery = ""
                                    open(result.page)
                                },
                            )
                        }
                    },
                )
            }
            return@Column
        }
        SettingsGroupCard(
            rows = listOf(
                {
                    SettingsNavRow(
                        title = stringResource(R.string.settings_playback_title),
                        subtitle = summaries.playback,
                        leadingIcon = Icons.Rounded.PlayArrow,
                        onClick = onOpenPlayback,
                    )
                },
                {
                    SettingsNavRow(
                        title = stringResource(R.string.settings_audio_quality_title),
                        subtitle = summaries.audioQuality,
                        leadingIcon = Icons.Rounded.GraphicEq,
                        onClick = onOpenAudioQuality,
                    )
                },
                {
                    SettingsNavRow(
                        title = stringResource(R.string.settings_accounts_sync_title),
                        subtitle = summaries.accounts,
                        leadingIcon = Icons.Rounded.Person,
                        onClick = onOpenAccounts,
                    )
                },
                {
                    SettingsNavRow(
                        title = stringResource(R.string.settings_library_storage_title),
                        subtitle = summaries.libraryStorage,
                        leadingIcon = Icons.Rounded.FolderOpen,
                        onClick = onOpenLibraryStorage,
                    )
                },
                {
                    SettingsNavRow(
                        title = stringResource(R.string.settings_appearance_title),
                        subtitle = summaries.appearance,
                        leadingIcon = Icons.Rounded.Palette,
                        onClick = onOpenAppearance,
                    )
                },
                {
                    SettingsNavRow(
                        title = stringResource(R.string.settings_about_title),
                        subtitle = summaries.about,
                        leadingIcon = Icons.Rounded.Info,
                        onClick = onOpenAbout,
                    )
                },
            ),
        )
    }
}
