package com.stash.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.social.Destination
import com.stash.core.ui.R as UiR
import com.stash.feature.settings.components.AccountConnectionCard
import com.stash.feature.settings.components.LastFmAccountCard
import com.stash.feature.settings.components.LikeMirrorToggleRow
import com.stash.feature.settings.components.ListenBrainzAccountCard
import com.stash.feature.settings.components.SettingsGroupGap
import com.stash.feature.settings.components.SettingsScaffold
import com.stash.feature.settings.components.SettingsSectionLabel
import com.stash.feature.settings.spotify.DisconnectAllSpotifyDialog
import com.stash.feature.settings.spotify.OtherAccountSignIn
import com.stash.feature.settings.spotify.SpotifyAccountsViewModel
import com.stash.feature.settings.spotify.SpotifyNoticeDialog
import com.stash.feature.settings.spotify.SpotifyOtherAccountCards
import com.stash.feature.settings.spotify.SpotifyOtherAccountsOverlays
import com.stash.feature.settings.spotify.otherAccountErrorText

/**
 * Settings > Accounts & Sync. One card per account, each carrying its own
 * switches once connected: Spotify (likes), YouTube Music (likes, play
 * history), Discord, Last.fm (love, first artist, scrobble queue) and
 * ListenBrainz (now playing, queue), then cloud storage. The like-mirroring
 * opt-in ack dialog is owned here.
 */
@Composable
fun SettingsAccountsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opened from Your Stash's Cloud rows: Cloud storage, last on the page, is scrolled into view once on arrival. */
    focusCloud: Boolean = false,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val extendedColors = com.stash.core.ui.theme.StashTheme.extendedColors
    // Your Stash's scroll to Cloud storage happens once. Kept up here, before the sign-in screens
    // below return early: inside the page it was dropped while one showed, and fired again after.
    var cloudFocused by rememberSaveable { mutableStateOf(false) }
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

    // The other Spotify accounts (plan 2026-11-04 §3): their own ViewModel, this page's. Read up
    // here, before the sign-in screens below return early, as the cloud focus flag is.
    val spotifyAccounts: SpotifyAccountsViewModel = hiltViewModel()
    val otherSpotify by spotifyAccounts.state.collectAsStateWithLifecycle()
    val otherSpotifyAccounts by spotifyAccounts.accounts.collectAsStateWithLifecycle()
    val spotifyMainName by spotifyAccounts.mainName.collectAsStateWithLifecycle()
    val canAddSpotify by spotifyAccounts.canAdd.collectAsStateWithLifecycle()
    // Its sign-in outlives the page on a tab switch too (the entry is kept): closed the same way.
    androidx.compose.runtime.DisposableEffect(spotifyAccounts) {
        onDispose {
            if (activity?.isChangingConfigurations != true) spotifyAccounts.onScreenClosed()
        }
    }

    // Adding another Spotify account, or signing one in again: Spotify's own sign-in, full screen,
    // with a line on how ("Continue with Google" would land on the connected account).
    if (otherSpotify.showWebLogin) {
        val again = (otherSpotify.signIn as? OtherAccountSignIn.Again)
            ?.let { purpose -> otherSpotifyAccounts.firstOrNull { it.id == purpose.accountId } }
        com.stash.feature.settings.components.SpotifyLoginWebView(
            onCookieExtracted = spotifyAccounts::onCookieCaptured,
            onDismiss = spotifyAccounts::cancelSignIn,
            onManualFallback = spotifyAccounts::pasteInstead,
            title = if (again == null) {
                stringResource(R.string.settings_spotify_others_add_signin_title)
            } else {
                stringResource(R.string.settings_spotify_signin_title)
            },
            hint = if (again == null) {
                stringResource(R.string.settings_spotify_others_google_hint)
            } else {
                stringResource(R.string.settings_spotify_others_again_hint, again.name)
            },
        )
        return
    }

    // Its "Paste cookie" fallback, which also shows the check of a cookie the sign-in captured.
    if (otherSpotify.showPasteDialog) {
        com.stash.feature.settings.components.SpotifyCookieDialog(
            isValidating = otherSpotify.validating,
            errorMessage = otherSpotify.error?.let { otherAccountErrorText(it) },
            onConnect = { cookie, _ -> spotifyAccounts.submit(cookie) },
            onDismiss = spotifyAccounts::cancelSignIn,
            title = if (otherSpotify.signIn is OtherAccountSignIn.Again) {
                stringResource(R.string.settings_spotify_signin_title)
            } else {
                stringResource(R.string.settings_spotify_others_add_signin_title)
            },
            askUsername = false,
        )
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

    // A sign-in as a different Spotify account than the connected one was refused (nothing was
    // replaced). Usually a Reconnect that landed on the wrong account, so it says how to get the
    // connected account back first, then how to switch on purpose.
    val spotifyMismatch by viewModel.spotifyAccountMismatch.collectAsStateWithLifecycle()
    spotifyMismatch?.let { mismatch ->
        AlertDialog(
            onDismissRequest = viewModel::onDismissSpotifyAccountMismatch,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            title = {
                Text(
                    text = stringResource(R.string.settings_spotify_other_account_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    text = mismatch.attemptedName?.let {
                        stringResource(R.string.settings_spotify_other_account_body, mismatch.connectedName, it)
                    } ?: stringResource(R.string.settings_spotify_other_account_body_unnamed, mismatch.connectedName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::onDismissSpotifyAccountMismatch) {
                    Text(stringResource(R.string.settings_ok))
                }
            },
        )
    }

    // A Connect on the Spotify card signed in as one of the other accounts: refused, nothing changed.
    val spotifyAlreadyOther by viewModel.spotifyAlreadyOtherAccount.collectAsStateWithLifecycle()
    spotifyAlreadyOther?.let { name ->
        SpotifyNoticeDialog(
            title = stringResource(R.string.settings_spotify_already_other_title),
            body = stringResource(R.string.settings_spotify_already_other_body, name),
            onDismiss = viewModel::onDismissSpotifyAlreadyOtherAccount,
        )
    }

    // Disconnect stopped: one of the other accounts wouldn't sign out on this phone.
    val spotifyDisconnectStopped by viewModel.spotifyDisconnectStopped.collectAsStateWithLifecycle()
    spotifyDisconnectStopped?.let { stopped ->
        SpotifyNoticeDialog(
            title = stringResource(R.string.settings_spotify_disconnect_stopped_title),
            body = stopped.name?.let { stringResource(R.string.settings_spotify_disconnect_stopped_body, it) }
                ?: stringResource(R.string.settings_spotify_disconnect_stopped_body_unnamed),
            onDismiss = viewModel::onDismissSpotifyDisconnectStopped,
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
                serviceName = if (dest == Destination.SPOTIFY) "Spotify" else "YouTube Music",
                onConfirm = viewModel::onMirrorWarningConfirmed,
                onDismiss = viewModel::onMirrorWarningDismissed,
            )
        }

        SettingsSectionLabel(stringResource(R.string.settings_accounts_connections_section))

        // Each account's own switches (likes, history) show only while it is
        // connected: a disconnected card is one line and a Connect button.
        com.stash.feature.settings.components.SoundCloudAccountCard()

        val spotifyConnected = uiState.spotifyAuthState is com.stash.core.auth.model.AuthState.Connected
        val ytConnected = uiState.youTubeAuthState is com.stash.core.auth.model.AuthState.Connected

        // Disconnecting a source also drops its queued downloads, so each asks
        // first (as Discord's connect does). Saveable, like showDiscordRisk, so
        // a rotation keeps the question up.
        var confirmSpotifyDisconnect by rememberSaveable { mutableStateOf(false) }
        var confirmYouTubeDisconnect by rememberSaveable { mutableStateOf(false) }

        // YouTube Music profile (#664): a Google account's Brand Account profiles each have their
        // own library. Right after connecting, ask which one when there's more than one.
        val profileViewModel: com.stash.feature.settings.components.YouTubeProfileViewModel = hiltViewModel()
        val profileState by profileViewModel.state.collectAsStateWithLifecycle()
        var showYouTubeProfiles by rememberSaveable { mutableStateOf(false) }
        var ytWasConnected by rememberSaveable { mutableStateOf(ytConnected) }
        androidx.compose.runtime.LaunchedEffect(ytConnected) {
            if (ytConnected && !ytWasConnected) profileViewModel.onConnected()
            ytWasConnected = ytConnected
        }
        androidx.compose.runtime.LaunchedEffect(profileState.askAfterConnect) {
            if (profileState.askAfterConnect) {
                showYouTubeProfiles = true
                profileViewModel.askHandled()
            }
        }
        if (showYouTubeProfiles) {
            com.stash.feature.settings.components.YouTubeProfileSheet(
                state = profileState,
                onLoad = profileViewModel::load,
                onChoose = profileViewModel::choose,
                onChooseById = profileViewModel::chooseById,
                onDismiss = { showYouTubeProfiles = false },
            )
        }
        if (confirmSpotifyDisconnect) {
            val spotifyRefreshMode by viewModel.spotifyRefreshMode.collectAsStateWithLifecycle()
            val note = if (spotifyRefreshMode) stringResource(R.string.settings_disconnect_spotify_refresh_note) else null
            val onConfirm = {
                confirmSpotifyDisconnect = false
                // Also sign the WebView out, or the next Connect re-submits the old sp_dc.
                com.stash.feature.settings.components.clearSpotifyWebSession()
                viewModel.onDisconnectSpotify()
            }
            if (otherSpotifyAccounts.isEmpty()) {
                com.stash.feature.settings.components.DisconnectAccountDialog(
                    serviceName = "Spotify",
                    note = note,
                    onConfirm = onConfirm,
                    onDismiss = { confirmSpotifyDisconnect = false },
                )
            } else {
                // The other accounts can't stay without this one (v1): it asks to disconnect them all.
                DisconnectAllSpotifyDialog(
                    names = listOfNotNull(spotifyMainName) + otherSpotifyAccounts.map { it.name },
                    note = note,
                    onConfirm = onConfirm,
                    onDismiss = { confirmSpotifyDisconnect = false },
                )
            }
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
            icon = ImageVector.vectorResource(UiR.drawable.ic_brand_spotify),
            accentColor = extendedColors.spotifyGreen,
            authState = uiState.spotifyAuthState,
            expired = authExpiry.spotifyExpired,
            onConnect = viewModel::onConnectSpotify,
            onDisconnect = { confirmSpotifyDisconnect = true },
            extraContent = if (spotifyConnected) {
                {
                    val likeStatus by viewModel.likeStatusSpotify.collectAsStateWithLifecycle()
                    LikeMirrorToggleRow(
                        title = stringResource(R.string.settings_like_mirror_row_title, "Spotify"),
                        checked = uiState.mirrorLikesSpotify,
                        onToggle = { viewModel.onMirrorToggleRequested(Destination.SPOTIFY, it) },
                        status = likeStatus,
                        onRetry = { viewModel.onRetryLikeMirror(Destination.SPOTIFY) },
                    )
                }
            } else {
                null
            },
        )

        // The other Spotify accounts under it, each a card, then "Add another Spotify account".
        // Listed whether or not the connected account is (a restored backup brings them back
        // signed out, and they must stay reachable to sign in again or remove); adding needs it.
        SpotifyOtherAccountCards(
            accounts = otherSpotifyAccounts,
            mainName = spotifyMainName,
            canAdd = canAddSpotify,
            onOpen = spotifyAccounts::openAccount,
            onSignInAgain = spotifyAccounts::startSignInAgain,
            onAdd = spotifyAccounts::startAdd,
        )
        SpotifyOtherAccountsOverlays(
            viewModel = spotifyAccounts,
            state = otherSpotify,
            accounts = otherSpotifyAccounts,
            mainName = spotifyMainName,
        )

        AccountConnectionCard(
            serviceName = "YouTube Music",
            icon = ImageVector.vectorResource(UiR.drawable.ic_brand_youtube_music),
            accentColor = extendedColors.youtubeRed,
            authState = uiState.youTubeAuthState,
            expired = authExpiry.youtubeExpired,
            onConnect = viewModel::onConnectYouTube,
            onDisconnect = { confirmYouTubeDisconnect = true },
            extraContent = if (ytConnected) {
                {
                    val ytUser = (uiState.youTubeAuthState as? com.stash.core.auth.model.AuthState.Connected)?.user
                    com.stash.feature.settings.components.SettingsValueRow(
                        title = stringResource(R.string.settings_youtube_profile_title),
                        value = ytUser?.displayName?.takeIf { ytUser.id != "youtube_user" } ?: stringResource(R.string.settings_youtube_profile_default),
                        subtitle = stringResource(R.string.settings_youtube_profile_subtitle),
                        onClick = { showYouTubeProfiles = true },
                    )
                    val likeStatus by viewModel.likeStatusYtMusic.collectAsStateWithLifecycle()
                    LikeMirrorToggleRow(
                        title = stringResource(R.string.settings_like_mirror_row_title, "YouTube Music"),
                        checked = uiState.mirrorLikesYtMusic,
                        onToggle = { viewModel.onMirrorToggleRequested(Destination.YT_MUSIC, it) },
                        status = likeStatus,
                        onRetry = { viewModel.onRetryLikeMirror(Destination.YT_MUSIC) },
                    )
                    com.stash.feature.settings.components.YouTubeHistorySyncSection(
                        enabled = uiState.ytHistoryEnabled,
                        health = uiState.ytHistoryHealth,
                        pendingCount = uiState.ytPendingCount,
                        onToggle = viewModel::onYouTubeHistoryEnabledChanged,
                        onRetry = viewModel::onRetryYouTubeHistory,
                    )
                }
            } else {
                null
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
            icon = ImageVector.vectorResource(UiR.drawable.ic_brand_discord),
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

        // Last.fm and ListenBrainz sit with the other accounts, above cloud
        // storage: same job, and users commonly run both.
        val scrobbleFirstArtistOnly by viewModel.scrobbleFirstArtistOnly.collectAsStateWithLifecycle()
        val mirrorLastFm by viewModel.mirrorLikesLastFm.collectAsStateWithLifecycle()
        val lastFmLikeStatus by viewModel.likeStatusLastFm.collectAsStateWithLifecycle()
        val lastFmUriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        val drainMessage = uiState.scrobbleDrainResult?.let { result ->
            androidx.compose.runtime.LaunchedEffect(result) {
                kotlinx.coroutines.delay(3000)
                viewModel.onClearScrobbleDrainResult()
            }
            when {
                !result.sessionPresent -> stringResource(R.string.settings_lastfm_connect_first)
                result.held && result.submitted == 0 -> stringResource(R.string.settings_lastfm_reach_error)
                result.submitted == 0 -> stringResource(R.string.settings_lastfm_no_new_scrobbles)
                else -> pluralStringResource(R.plurals.settings_lastfm_scrobbles_sent, result.submitted, result.submitted)
            }
        }
        LastFmAccountCard(
            state = uiState.lastFmState,
            onConnect = {
                viewModel.onConnectLastFm { url -> runCatching { lastFmUriHandler.openUri(url) } }
            },
            onFinish = viewModel::onFinishLastFmAuth,
            onDisconnect = viewModel::onDisconnectLastFm,
            onCancel = viewModel::onDismissLastFmError,
            onSyncScrobblesNow = viewModel::onSyncScrobblesNow,
            isScrobbleDraining = uiState.isScrobbleDraining,
            drainMessage = drainMessage,
            firstArtistOnly = scrobbleFirstArtistOnly,
            onFirstArtistOnlyChange = viewModel::onScrobbleFirstArtistOnlyChanged,
            loveEnabled = mirrorLastFm,
            onLoveToggle = { viewModel.onMirrorToggleRequested(Destination.LAST_FM, it) },
            loveStatus = lastFmLikeStatus,
            onLoveRetry = { viewModel.onRetryLikeMirror(Destination.LAST_FM) },
        )

        val listenBrainzState by viewModel.listenBrainzState.collectAsStateWithLifecycle()
        val listenBrainzToken by viewModel.listenBrainzTokenInput.collectAsStateWithLifecycle()
        val listenBrainzDraining by viewModel.isListenBrainzDraining.collectAsStateWithLifecycle()
        ListenBrainzAccountCard(
            state = listenBrainzState,
            tokenInput = listenBrainzToken,
            onTokenInputChange = viewModel::onListenBrainzTokenChange,
            onConnect = viewModel::onConnectListenBrainz,
            onDisconnect = viewModel::onDisconnectListenBrainz,
            onNowPlayingToggle = viewModel::onListenBrainzNowPlayingToggle,
            onSyncNow = viewModel::onSyncListenBrainzNow,
            isDraining = listenBrainzDraining,
        )

        // Dropbox / OneDrive / Google Drive: the user's own music in their cloud
        // storage. Hidden in a build without provider keys. One column, spaced as
        // the page is, so Your Stash can bring the whole section into view.
        val cloudSection = remember { BringIntoViewRequester() }
        Column(
            modifier = Modifier.bringIntoViewRequester(cloudSection),
            verticalArrangement = Arrangement.spacedBy(SettingsGroupGap),
        ) {
            com.stash.feature.settings.cloud.CloudAccountsSection(
                viewModel = androidx.hilt.navigation.compose.hiltViewModel(),
            )
        }
        if (focusCloud) {
            // Once: not again after a rotation, a sign-in screen, or when Back returns here.
            LaunchedEffect(Unit) {
                if (cloudFocused) return@LaunchedEffect
                cloudFocused = true
                // A frame first, so the cards above are laid out and the scroll lands on the section.
                withFrameNanos { }
                cloudSection.bringIntoView()
            }
        }
    }
}
