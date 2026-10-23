package com.stash.feature.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    // The last sync's verdict on each stored sign-in: an expired card leads with
    // Reconnect (the Sync tab's "Re-authenticate" lands here).
    val authExpiry by viewModel.authExpiry.collectAsStateWithLifecycle()

    // The sign-in flags live in the Settings-wide ViewModel, which outlives this
    // screen: leaving Accounts with a sign-in open (any route Back doesn't
    // cover, e.g. switching tabs) would otherwise reopen it on the next visit.
    // Not on rotation, which disposes this too but should keep the sign-in up.
    val activity = androidx.activity.compose.LocalActivity.current
    androidx.compose.runtime.DisposableEffect(viewModel) {
        onDispose {
            if (activity?.isChangingConfigurations != true) viewModel.onWebLoginsClosed()
        }
    }

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

    // Discord token paste dialog (manual fallback).
    if (uiState.showDiscordTokenDialog) {
        com.stash.feature.settings.components.DiscordTokenDialog(
            isValidating = uiState.isDiscordTokenValidating,
            errorMessage = uiState.discordTokenError,
            onConnect = viewModel::onConnectDiscordWithToken,
            onDismiss = viewModel::onDismissDiscordTokenDialog,
        )
    }

    // Discord WebView login (full-screen overlay).
    if (uiState.showDiscordWebLogin) {
        com.stash.feature.settings.components.DiscordLoginWebView(
            onTokenExtracted = viewModel::onDiscordWebLoginTokenExtracted,
            onDismiss = viewModel::onDismissDiscordWebLogin,
            onManualFallback = viewModel::onConnectDiscordManual,
        )
        return
    }

    // YouTube error dialog (missing credentials, network failure, etc.).
    if (uiState.youTubeError != null) {
        AlertDialog(
            onDismissRequest = viewModel::onDismissYouTubeError,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            title = {
                Text(
                    text = stringResource(R.string.settings_yt_music_error_title),
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
                    Text(stringResource(R.string.settings_ok))
                }
            },
        )
    }

    SettingsScaffold(title = stringResource(R.string.settings_accounts_sync_title), onBack = onBack, modifier = modifier) {
        // Like-mirroring opt-in ack. Rendered at the top of the scaffold so it
        // overlays the content. Confirming writes the pref; dismissing leaves
        // mirroring off (no writes without this ack).
        uiState.pendingMirrorWarning?.let { dest ->
            com.stash.feature.settings.components.LikeMirrorWarningDialog(
                serviceName = if (dest == com.stash.core.data.social.Destination.SPOTIFY) "Spotify" else "YouTube Music",
                onConfirm = viewModel::onMirrorWarningConfirmed,
                onDismiss = viewModel::onMirrorWarningDismissed,
            )
        }

        SettingsSectionLabel(stringResource(R.string.settings_accounts_connections_section))

        // Disconnecting a source also drops its queued downloads, so each asks
        // first (as Discord's connect does). Saveable, like showDiscordRisk, so
        // a rotation keeps the question up.
        var confirmSpotifyDisconnect by rememberSaveable { mutableStateOf(false) }
        var confirmYouTubeDisconnect by rememberSaveable { mutableStateOf(false) }
        if (confirmSpotifyDisconnect) {
            com.stash.feature.settings.components.DisconnectAccountDialog(
                serviceName = "Spotify",
                onConfirm = {
                    confirmSpotifyDisconnect = false
                    // Also sign the WebView out, or the next Connect re-submits the old sp_dc.
                    com.stash.feature.settings.components.clearSpotifyWebSession()
                    viewModel.onDisconnectSpotify()
                },
                onDismiss = { confirmSpotifyDisconnect = false },
            )
        }
        if (confirmYouTubeDisconnect) {
            com.stash.feature.settings.components.DisconnectAccountDialog(
                serviceName = "YouTube Music",
                onConfirm = {
                    confirmYouTubeDisconnect = false
                    // Also sign the WebView out of Google, so the account isn't left behind in it.
                    com.stash.feature.settings.components.clearYouTubeWebSession()
                    viewModel.onDisconnectYouTube()
                },
                onDismiss = { confirmYouTubeDisconnect = false },
            )
        }

        AccountConnectionCard(
            serviceName = "Spotify",
            icon = Icons.Rounded.MusicNote,
            accentColor = extendedColors.spotifyGreen,
            authState = uiState.spotifyAuthState,
            expired = authExpiry.spotifyExpired,
            onConnect = viewModel::onConnectSpotify,
            onDisconnect = { confirmSpotifyDisconnect = true },
        )

        AccountConnectionCard(
            serviceName = "YouTube Music",
            icon = Icons.Rounded.PlayCircle,
            accentColor = extendedColors.youtubeRed,
            authState = uiState.youTubeAuthState,
            expired = authExpiry.youtubeExpired,
            onConnect = viewModel::onConnectYouTube,
            onDisconnect = { confirmYouTubeDisconnect = true },
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

        val discordUriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        val discordNeedsConsent by viewModel.discordNeedsPresenceConsent.collectAsStateWithLifecycle()

        // Discord is the one account where connecting carries a risk to the
        // USER's account rather than just their privacy, so it is the one
        // account that asks first. Discord has no sanctioned way for a phone
        // app to set Rich Presence, so this drives the account's own session —
        // what their Platform Manipulation Policy names as a self-bot, and
        // what their support article says can end in account termination.
        // Enforcement in practice targets spam, so the real-world risk is low;
        // it is still not ours to take on someone else's behalf silently.
        var showDiscordRisk by rememberSaveable { mutableStateOf(false) }
        if (showDiscordRisk) {
            AlertDialog(
                onDismissRequest = { showDiscordRisk = false },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large,
                title = { Text(stringResource(R.string.settings_discord_risk_title)) },
                text = {
                    Text(
                        stringResource(R.string.settings_discord_risk_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showDiscordRisk = false
                        viewModel.onConnectDiscord()
                    }) { Text(stringResource(R.string.settings_discord_risk_connect)) }
                },
                dismissButton = {
                    TextButton(onClick = { showDiscordRisk = false }) { Text(stringResource(R.string.settings_cancel)) }
                },
            )
        }
        AccountConnectionCard(
            serviceName = "Discord",
            icon = Icons.Rounded.Forum,
            accentColor = androidx.compose.ui.graphics.Color(0xFF5865F2), // Discord blurple
            authState = uiState.discordAuthState,
            onConnect = { showDiscordRisk = true },
            onDisconnect = {
                // Also sign the WebView out (the token otherwise stays in its storage).
                com.stash.feature.settings.components.clearDiscordWebSession()
                viewModel.onDisconnectDiscord()
            },
            needsAction = discordNeedsConsent,
            needsActionLabel = stringResource(R.string.settings_discord_finish_connecting),
            onNeedsAction = {
                viewModel.onDiscordFinishConnecting { url ->
                    runCatching { discordUriHandler.openUri(url) }.isSuccess
                }
            },
        )

        // Dropbox / OneDrive: the user's own music in their cloud storage. Hidden in a build
        // without provider keys.
        com.stash.feature.settings.cloud.CloudAccountsSection(
            viewModel = androidx.hilt.navigation.compose.hiltViewModel(),
        )

        // Last.fm renders via its own composable (web-auth / cookie / OAuth UX
        // differs enough from AccountConnectionCard). The deep-link
        // bringIntoViewRequester from the monolith is dropped here.
        val scrobbleFirstArtistOnly by viewModel.scrobbleFirstArtistOnly.collectAsStateWithLifecycle()
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
                firstArtistOnly = scrobbleFirstArtistOnly,
                onFirstArtistOnlyChange = viewModel::onScrobbleFirstArtistOnlyChanged,
            )
            uiState.scrobbleDrainResult?.let { result ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when {
                        !result.sessionPresent -> stringResource(R.string.settings_lastfm_connect_first)
                        result.held && result.submitted == 0 ->
                            stringResource(R.string.settings_lastfm_reach_error)
                        result.submitted == 0 -> stringResource(R.string.settings_lastfm_no_new_scrobbles)
                        else -> pluralStringResource(R.plurals.settings_lastfm_scrobbles_sent, result.submitted, result.submitted)
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

        // ListenBrainz sits beside Last.fm: same job, and users commonly run both
        // rather than choosing between them.
        val listenBrainzState by viewModel.listenBrainzState.collectAsStateWithLifecycle()
        val listenBrainzToken by viewModel.listenBrainzTokenInput.collectAsStateWithLifecycle()
        val listenBrainzDraining by viewModel.isListenBrainzDraining.collectAsStateWithLifecycle()
        GlassCard {
            com.stash.feature.settings.components.ListenBrainzSection(
                state = listenBrainzState,
                tokenInput = listenBrainzToken,
                onTokenInputChange = viewModel::onListenBrainzTokenChange,
                onConnect = viewModel::onConnectListenBrainz,
                onDisconnect = viewModel::onDisconnectListenBrainz,
                onNowPlayingToggle = viewModel::onListenBrainzNowPlayingToggle,
                onSyncNow = viewModel::onSyncListenBrainzNow,
                isDraining = listenBrainzDraining,
            )
        }

        SettingsSectionLabel(stringResource(R.string.settings_accounts_sync_likes_section), beta = true)

        GlassCard {
            val mirrorLastFm by viewModel.mirrorLikesLastFm.collectAsStateWithLifecycle()
            com.stash.feature.settings.components.LikeMirrorSection(
                spotifyEnabled = uiState.mirrorLikesSpotify,
                ytMusicEnabled = uiState.mirrorLikesYtMusic,
                lastFmEnabled = mirrorLastFm,
                spotifyConnected = uiState.spotifyAuthState is com.stash.core.auth.model.AuthState.Connected,
                ytConnected = uiState.youTubeAuthState is com.stash.core.auth.model.AuthState.Connected,
                lastFmConnected = uiState.lastFmState is LastFmAuthState.Connected,
                onSpotifyToggle = { viewModel.onMirrorToggleRequested(com.stash.core.data.social.Destination.SPOTIFY, it) },
                onYtMusicToggle = { viewModel.onMirrorToggleRequested(com.stash.core.data.social.Destination.YT_MUSIC, it) },
                onLastFmToggle = { viewModel.onMirrorToggleRequested(com.stash.core.data.social.Destination.LAST_FM, it) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}
