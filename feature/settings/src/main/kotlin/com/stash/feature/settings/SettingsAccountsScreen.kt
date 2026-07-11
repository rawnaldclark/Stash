package com.stash.feature.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.ui.R
import com.stash.core.ui.components.GlassCard
import com.stash.feature.settings.components.AccountConnectionCard
import com.stash.feature.settings.components.SettingsScaffold
import com.stash.feature.settings.components.SettingsSectionLabel

/**
 * The Accounts & Sync spoke of the hub-and-spoke Settings redesign.
 *
 * Behavior-preserving relocation of the original "Accounts" section from the
 * monolithic [SettingsScreen]: the Spotify and YouTube Music connection cards
 * (each with their inline auto-save / history-sync extras), the Last.fm card,
 * and the like-mirroring opt-ins (plus the mirror-warning ack dialog). Every
 * control calls the SAME [SettingsViewModel] method the old screen used; no
 * logic is changed. The `bringIntoViewRequester` on the Last.fm card (a
 * deep-link affordance) is intentionally dropped here.
 */
@Composable
fun SettingsAccountsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val extendedColors = com.stash.core.ui.theme.StashTheme.extendedColors

    // Spotify WebView login (full-screen overlay). Replaces the screen content
    // while active — the connect flow on the Spotify card flips this flag.
    if (uiState.showSpotifyWebLogin) {
        com.stash.feature.settings.components.SpotifyLoginWebView(
            onCookieExtracted = viewModel::onSpotifyWebLoginCookieExtracted,
            onDismiss = viewModel::onDismissSpotifyWebLogin,
            onManualFallback = viewModel::onConnectSpotifyManual,
        )
        return
    }

    // YouTube Music WebView login (full-screen overlay).
    if (uiState.showYouTubeWebLogin) {
        com.stash.feature.settings.components.YouTubeLoginWebView(
            onCookieExtracted = viewModel::onYouTubeWebLoginCookieExtracted,
            onDismiss = viewModel::onDismissYouTubeWebLogin,
            onManualFallback = viewModel::onConnectYouTubeManual,
        )
        return
    }

    // Spotify sp_dc cookie input dialog (manual fallback).
    if (uiState.showSpotifyCookieDialog) {
        com.stash.feature.settings.components.SpotifyCookieDialog(
            isValidating = uiState.isSpotifyCookieValidating,
            errorMessage = uiState.spotifyCookieError,
            onConnect = { cookie, username -> viewModel.onConnectSpotifyWithCookie(cookie, username) },
            onDismiss = viewModel::onDismissSpotifyCookieDialog,
        )
    }

    // YouTube Music cookie input dialog.
    if (uiState.showYouTubeCookieDialog) {
        com.stash.feature.settings.components.YouTubeCookieDialog(
            isValidating = uiState.isYouTubeCookieValidating,
            errorMessage = uiState.youTubeCookieError,
            onConnect = viewModel::onConnectYouTubeWithCookie,
            onDismiss = viewModel::onDismissYouTubeCookieDialog,
        )
    }

    // YouTube error dialog (missing credentials, network failure, etc.).
    if (uiState.youTubeError != null) {
        AlertDialog(
            onDismissRequest = viewModel::onDismissYouTubeError,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            title = {
                Text(
                    text = stringResource(R.string.label_youtube_music),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    text = uiState.youTubeError!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::onDismissYouTubeError) {
                    Text("OK")
                }
            },
        )
    }

    SettingsScaffold(title = stringResource(R.string.title_accounts_sync), onBack = onBack, modifier = modifier) {
        // Like-mirroring opt-in ack. Rendered at the top of the scaffold so it
        // overlays the content. Confirming writes the pref; dismissing leaves
        // mirroring off (no writes without this ack).
        uiState.pendingMirrorWarning?.let { dest ->
            com.stash.feature.settings.components.LikeMirrorWarningDialog(
                serviceName = if (dest == com.stash.core.data.social.Destination.SPOTIFY) stringResource(R.string.label_spotify) else stringResource(R.string.label_youtube_music),
                onConfirm = viewModel::onMirrorWarningConfirmed,
                onDismiss = viewModel::onMirrorWarningDismissed,
            )
        }

        SettingsSectionLabel(stringResource(R.string.section_connections))

        AccountConnectionCard(
            serviceName = "Spotify",
            icon = Icons.Rounded.MusicNote,
            accentColor = extendedColors.spotifyGreen,
            authState = uiState.spotifyAuthState,
            onConnect = viewModel::onConnectSpotify,
            onDisconnect = viewModel::onDisconnectSpotify,
            extraContent = {
                com.stash.feature.settings.components.SpotifyAutoSaveSection(
                    enabled = uiState.autoSaveEnabled,
                    threshold = uiState.autoSaveThreshold,
                    autoSavedCountLast7Days = uiState.autoSavedCountLast7Days,
                    spotifyConnected = uiState.spotifyAuthState is com.stash.core.auth.model.AuthState.Connected,
                    onToggle = viewModel::onAutoSaveEnabledChanged,
                    onThresholdChanged = viewModel::onAutoSaveThresholdChanged,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            },
        )

        AccountConnectionCard(
            serviceName = "YouTube Music",
            icon = Icons.Rounded.PlayCircle,
            accentColor = extendedColors.youtubeRed,
            authState = uiState.youTubeAuthState,
            onConnect = viewModel::onConnectYouTube,
            onDisconnect = viewModel::onDisconnectYouTube,
            extraContent = {
                com.stash.feature.settings.components.YouTubeHistorySyncSection(
                    enabled = uiState.ytHistoryEnabled,
                    health = uiState.ytHistoryHealth,
                    pendingCount = uiState.ytPendingCount,
                    ytConnected = uiState.youTubeAuthState is com.stash.core.auth.model.AuthState.Connected,
                    onToggle = viewModel::onYouTubeHistoryEnabledChanged,
                    onRetry = viewModel::onRetryYouTubeHistory,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            },
        )

        // Last.fm renders via its own composable (web-auth / cookie / OAuth UX
        // differs enough from AccountConnectionCard). The deep-link
        // bringIntoViewRequester from the monolith is dropped here.
        val lastFmUriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        GlassCard {
            com.stash.feature.settings.components.LastFmSection(
                state = uiState.lastFmState,
                onConnect = {
                    viewModel.onConnectLastFm { url -> runCatching { lastFmUriHandler.openUri(url) } }
                },
                onFinish = viewModel::onFinishLastFmAuth,
                onDisconnect = viewModel::onDisconnectLastFm,
                onDismissError = viewModel::onDismissLastFmError,
                onSyncScrobblesNow = viewModel::onSyncScrobblesNow,
                isScrobbleDraining = uiState.isScrobbleDraining,
            )
            uiState.scrobbleDrainResult?.let { result ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when {
                        !result.sessionPresent -> "Connect Last.fm first."
                        result.submitted == 0 -> "No new scrobbles to send."
                        else -> "Sent ${result.submitted} scrobble${if (result.submitted == 1) "" else "s"} to Last.fm."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                androidx.compose.runtime.LaunchedEffect(result) {
                    kotlinx.coroutines.delay(3000)
                    viewModel.onClearScrobbleDrainResult()
                }
            }
        }

        SettingsSectionLabel("Sync your likes", beta = true)

        GlassCard {
            com.stash.feature.settings.components.LikeMirrorSection(
                spotifyEnabled = uiState.mirrorLikesSpotify,
                ytMusicEnabled = uiState.mirrorLikesYtMusic,
                spotifyConnected = uiState.spotifyAuthState is com.stash.core.auth.model.AuthState.Connected,
                ytConnected = uiState.youTubeAuthState is com.stash.core.auth.model.AuthState.Connected,
                onSpotifyToggle = { viewModel.onMirrorToggleRequested(com.stash.core.data.social.Destination.SPOTIFY, it) },
                onYtMusicToggle = { viewModel.onMirrorToggleRequested(com.stash.core.data.social.Destination.YT_MUSIC, it) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}
