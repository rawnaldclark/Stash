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
import androidx.compose.ui.graphics.vector.ImageVector
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

    var searchQuery by remember { mutableStateOf("") }
    val searchIndex = remember(
        context,
        onOpenPlayback, onOpenAudioQuality, onOpenAccounts,
        onOpenLibraryStorage, onOpenAppearance, onOpenAbout,
    ) {
        listOf(
            SettingsSearchItem(
                title = context.getString(R.string.settings_playback_title),
                subtitle = context.getString(R.string.settings_playback_search_subtitle),
                keywords = listOf("sleep timer", "autoplay", "radio", "crossfade", "gapless", "playback"),
                icon = Icons.Rounded.PlayArrow,
                onSelect = onOpenPlayback,
            ),
            SettingsSearchItem(
                title = context.getString(R.string.settings_audio_quality_title),
                subtitle = context.getString(R.string.settings_audio_quality_search_subtitle),
                keywords = listOf("bitrate", "flac", "lossless", "quality", "streaming", "wifi", "cellular"),
                icon = Icons.Rounded.GraphicEq,
                onSelect = onOpenAudioQuality,
            ),
            SettingsSearchItem(
                title = context.getString(R.string.settings_accounts_sync_title),
                subtitle = context.getString(R.string.settings_accounts_sync_search_subtitle),
                keywords = listOf("spotify", "youtube", "lastfm", "last.fm", "login", "sync", "scrobble"),
                icon = Icons.Rounded.Person,
                onSelect = onOpenAccounts,
            ),
            SettingsSearchItem(
                title = context.getString(R.string.settings_library_storage_title),
                subtitle = context.getString(R.string.settings_library_storage_search_subtitle),
                keywords = listOf("storage", "backup", "export", "import", "download", "move library", "web", "browser", "link", "pair"),
                icon = Icons.Rounded.FolderOpen,
                onSelect = onOpenLibraryStorage,
            ),
            SettingsSearchItem(
                title = context.getString(R.string.settings_appearance_title),
                subtitle = context.getString(R.string.settings_appearance_search_subtitle),
                keywords = listOf("theme", "dark mode", "amoled", "appearance", "home layout", "community", "open stash on", "start tab", "launch"),
                icon = Icons.Rounded.Palette,
                onSelect = onOpenAppearance,
            ),
            SettingsSearchItem(
                title = context.getString(R.string.settings_about_title),
                subtitle = context.getString(R.string.settings_about_search_subtitle),
                keywords = listOf("about", "version", "diagnostics", "crash", "help"),
                icon = Icons.Rounded.Info,
                onSelect = onOpenAbout,
            ),
        )
    }
    val searchResults = remember(searchQuery, searchIndex) {
        if (searchQuery.isBlank()) {
            emptyList()
        } else {
            searchIndex.filter { item ->
                item.title.contains(searchQuery, ignoreCase = true) ||
                    item.keywords.any { it.contains(searchQuery, ignoreCase = true) }
            }
        }
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
                                subtitle = result.subtitle,
                                leadingIcon = result.icon,
                                onClick = {
                                    searchQuery = ""
                                    result.onSelect()
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

/** One entry in the Settings search index — a category the query can match. */
private data class SettingsSearchItem(
    val title: String,
    val subtitle: String,
    val keywords: List<String>,
    val icon: ImageVector,
    val onSelect: () -> Unit,
)
