package com.stash.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import android.net.Uri
import android.widget.Toast
import com.stash.core.data.sync.workers.UpdateCheckWorker
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.model.DownloadNetworkMode
import com.stash.core.model.QualityTier
import com.stash.core.model.ThemeMode
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.theme.StashTheme
import androidx.compose.material3.AlertDialog
import com.stash.feature.settings.components.AccountConnectionCard
import com.stash.feature.settings.components.AudioQualityPicker
import com.stash.feature.settings.components.SpotifyCookieDialog
import com.stash.feature.settings.components.YouTubeCookieDialog
import com.stash.feature.settings.components.YouTubeHistorySyncSection

/**
 * Top-level Settings screen composable.
 *
 * Provides account connection management for Spotify and YouTube Music,
 * audio quality selection, storage statistics, and app information.
 * Spotify authentication uses the sp_dc cookie approach via [SpotifyCookieDialog].
 */
@Composable
fun SettingsScreen(
    onNavigateToEqualizer: () -> Unit = {},
    onNavigateToLibraryHealth: () -> Unit = {},
    onNavigateToSquidWtfCaptcha: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Spotify WebView login (full-screen overlay)
    if (uiState.showSpotifyWebLogin) {
        com.stash.feature.settings.components.SpotifyLoginWebView(
            onCookieExtracted = viewModel::onSpotifyWebLoginCookieExtracted,
            onDismiss = viewModel::onDismissSpotifyWebLogin,
            onManualFallback = viewModel::onConnectSpotifyManual,
        )
        return // Full-screen WebView replaces the Settings content
    }

    // YouTube Music WebView login (full-screen overlay) — Phase 1 spike
    if (uiState.showYouTubeWebLogin) {
        com.stash.feature.settings.components.YouTubeLoginWebView(
            onCookieExtracted = viewModel::onYouTubeWebLoginCookieExtracted,
            onDismiss = viewModel::onDismissYouTubeWebLogin,
            onManualFallback = viewModel::onConnectYouTubeManual,
        )
        return // Full-screen WebView replaces the Settings content
    }

    // Spotify sp_dc cookie input dialog (manual fallback)
    if (uiState.showSpotifyCookieDialog) {
        SpotifyCookieDialog(
            isValidating = uiState.isSpotifyCookieValidating,
            errorMessage = uiState.spotifyCookieError,
            onConnect = { cookie, username -> viewModel.onConnectSpotifyWithCookie(cookie, username) },
            onDismiss = viewModel::onDismissSpotifyCookieDialog,
        )
    }

    // YouTube Music cookie input dialog
    if (uiState.showYouTubeCookieDialog) {
        YouTubeCookieDialog(
            isValidating = uiState.isYouTubeCookieValidating,
            errorMessage = uiState.youTubeCookieError,
            onConnect = viewModel::onConnectYouTubeWithCookie,
            onDismiss = viewModel::onDismissYouTubeCookieDialog,
        )
    }

    // YouTube error dialog (missing credentials, network failure, etc.)
    if (uiState.youTubeError != null) {
        AlertDialog(
            onDismissRequest = viewModel::onDismissYouTubeError,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            title = {
                Text(
                    text = "YouTube Music",
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

    // v0.9.13: Single-shot consumption of any pending Settings deep-link
    // focus from Home banners. Read once per Settings entry; cleared
    // immediately so revisiting the screen doesn't re-scroll.
    val deepLinkFocus = remember { viewModel.consumeDeepLinkFocus() }

    val storageContext = LocalContext.current
    val contentResolver = storageContext.contentResolver
    // Tracks what action the user intended when they tapped the folder
    // picker. "SetOnly" = redirect new downloads; "SetAndMove" = also start the
    // library migration to the picked folder as soon as it's granted.
    var pendingPickerIntent by remember { mutableStateOf(PickerIntent.SetOnly) }
    val treePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            // Take a persistable permission BEFORE handing the URI to the
            // VM — without this, the permission is revoked when the app
            // is backgrounded and the persisted URI becomes useless.
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.setExternalStorageUri(uri)
            when (pendingPickerIntent) {
                PickerIntent.SetAndMove -> viewModel.startMoveLibrary(uri)
                PickerIntent.SetAndRestart -> {
                    // Success: permission re-granted for the restored folder.
                    // We can now safely restart the app.
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
                else -> Unit
            }
        }
        pendingPickerIntent = PickerIntent.SetOnly
    }

    SettingsContent(
        uiState = uiState,
        focusOnEntry = deepLinkFocus,
        onConnectSpotify = viewModel::onConnectSpotify,
        onDisconnectSpotify = viewModel::onDisconnectSpotify,
        onConnectYouTube = viewModel::onConnectYouTube,
        onDisconnectYouTube = viewModel::onDisconnectYouTube,
        onQualityChanged = viewModel::onQualityChanged,
        onDownloadNetworkModeChanged = viewModel::onDownloadNetworkModeChanged,
        onThemeChanged = viewModel::onThemeChanged,
        onSetExternalStorage = viewModel::setExternalStorageUri,
        onStartMoveLibrary = viewModel::startMoveLibrary,
        onCancelMoveLibrary = viewModel::cancelMoveLibrary,
        onDismissMoveLibrary = viewModel::dismissMoveLibrary,
        countMovableTracks = viewModel::countMovableTracks,
        onConnectLastFm = viewModel::onConnectLastFm,
        onFinishLastFmAuth = viewModel::onFinishLastFmAuth,
        onDisconnectLastFm = viewModel::onDisconnectLastFm,
        onDismissLastFmError = viewModel::onDismissLastFmError,
        onSyncScrobblesNow = viewModel::onSyncScrobblesNow,
        onClearScrobbleDrainResult = viewModel::onClearScrobbleDrainResult,
        onYouTubeHistoryEnabledChanged = viewModel::onYouTubeHistoryEnabledChanged,
        onStashMixesEnabledChanged = viewModel::onStashMixesEnabledChanged,
        onRetryYouTubeHistory = viewModel::onRetryYouTubeHistory,
        onLosslessEnabledChanged = viewModel::onLosslessEnabledChanged,
        onLosslessQualityTierChanged = viewModel::onLosslessQualityTierChanged,
        onYoutubeFallbackChanged = viewModel::onYoutubeFallbackChanged,
        onSquidWtfCaptchaCookieChanged = viewModel::onSquidWtfCaptchaCookieChanged,
        onResetLosslessRateLimiter = viewModel::onResetLosslessRateLimiter,
        onAutoSaveEnabledChanged = viewModel::onAutoSaveEnabledChanged,
        onAutoSaveThresholdChanged = viewModel::onAutoSaveThresholdChanged,
        onHeartDefaultStashChanged = viewModel::onHeartDefaultStashChanged,
        onHeartDefaultSpotifyChanged = viewModel::onHeartDefaultSpotifyChanged,
        onHeartDefaultYtMusicChanged = viewModel::onHeartDefaultYtMusicChanged,
        onExportDatabase = viewModel::onExportDatabase,
        onImportDatabase = viewModel::onImportDatabase,
        onConfirmImportDatabase = {
            viewModel.onConfirmImportDatabase { restoredUri ->
                pendingPickerIntent = PickerIntent.SetAndRestart
                treePicker.launch(restoredUri)
            }
        },
        onCancelImportDatabase = viewModel::onCancelImportDatabase,
        onDismissDatabaseBackupStatus = viewModel::onDismissDatabaseBackupStatus,
        onNavigateToEqualizer = onNavigateToEqualizer,
        onNavigateToLibraryHealth = onNavigateToLibraryHealth,
        onNavigateToSquidWtfCaptcha = onNavigateToSquidWtfCaptcha,
        onShareLatestCrashReport = viewModel::latestCrashShareTarget,
        onDiagnosticsRefresh = viewModel::refreshDiagnostics,
        streamingEnabled = viewModel.streamingEnabled.collectAsStateWithLifecycle().value,
        onStreamingToggle = viewModel::onStreamingToggle,
        treePicker = treePicker,
        onSetPickerIntent = { pendingPickerIntent = it },
        modifier = modifier,
    )
}

/**
 * Stateless inner content for [SettingsScreen], accepting all state and
 * callbacks as parameters for testability and preview support.
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun SettingsContent(
    uiState: SettingsUiState,
    focusOnEntry: com.stash.core.data.navigation.SettingsFocus?,
    onConnectSpotify: () -> Unit,
    onDisconnectSpotify: () -> Unit,
    onConnectYouTube: () -> Unit,
    onDisconnectYouTube: () -> Unit,
    onQualityChanged: (QualityTier) -> Unit,
    onDownloadNetworkModeChanged: (DownloadNetworkMode) -> Unit,
    onThemeChanged: (ThemeMode) -> Unit,
    onSetExternalStorage: (android.net.Uri?) -> Unit,
    onStartMoveLibrary: (android.net.Uri) -> Unit,
    onCancelMoveLibrary: () -> Unit,
    onDismissMoveLibrary: () -> Unit,
    countMovableTracks: suspend () -> Int,
    onConnectLastFm: ((String) -> Unit) -> Unit,
    onFinishLastFmAuth: () -> Unit,
    onDisconnectLastFm: () -> Unit,
    onDismissLastFmError: () -> Unit,
    onSyncScrobblesNow: () -> Unit,
    onClearScrobbleDrainResult: () -> Unit,
    onYouTubeHistoryEnabledChanged: (Boolean) -> Unit,
    onStashMixesEnabledChanged: (Boolean) -> Unit,
    onRetryYouTubeHistory: () -> Unit,
    onLosslessEnabledChanged: (Boolean) -> Unit,
    onLosslessQualityTierChanged: (LosslessQualityTier) -> Unit,
    onYoutubeFallbackChanged: (Boolean) -> Unit,
    onSquidWtfCaptchaCookieChanged: (String) -> Unit,
    onResetLosslessRateLimiter: () -> Unit,
    onAutoSaveEnabledChanged: (Boolean) -> Unit,
    onAutoSaveThresholdChanged: (Int) -> Unit,
    onHeartDefaultStashChanged: (Boolean) -> Unit,
    onHeartDefaultSpotifyChanged: (Boolean) -> Unit,
    onHeartDefaultYtMusicChanged: (Boolean) -> Unit,
    onExportDatabase: (Uri) -> Unit,
    onImportDatabase: (Uri) -> Unit,
    onConfirmImportDatabase: () -> Unit,
    onCancelImportDatabase: () -> Unit,
    onDismissDatabaseBackupStatus: () -> Unit,
    onNavigateToEqualizer: () -> Unit,
    onNavigateToLibraryHealth: () -> Unit,
    onNavigateToSquidWtfCaptcha: () -> Unit,
    /**
     * Returns the latest crash file + its FileProvider URI, or null if
     * none exists. Called only when the user taps the share button —
     * never on composition.
     */
    onShareLatestCrashReport: () -> SettingsViewModel.CrashShareTarget?,
    /**
     * Re-list `cacheDir/crashes/` to refresh [SettingsUiState.hasCrashReport].
     * Triggered on entry so the button reflects current disk state even
     * when the user navigates away and comes back.
     */
    onDiagnosticsRefresh: () -> Unit,
    treePicker: androidx.activity.result.ActivityResultLauncher<android.net.Uri?>?,
    onSetPickerIntent: (PickerIntent) -> Unit,
    /** Live streaming-mode (Online vs Offline) — see [SettingsViewModel.streamingEnabled]. */
    streamingEnabled: Boolean,
    /** Routed to [SettingsViewModel.onStreamingToggle] in the host. */
    onStreamingToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors

    // v0.9.13: deep-link scroll anchors. Each requester is attached to
    // the relevant section's container modifier; bringIntoView() runs in
    // a LaunchedEffect once on first composition with a non-null focus.
    val losslessRequester = remember { BringIntoViewRequester() }
    val lastFmRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(focusOnEntry) {
        when (focusOnEntry) {
            com.stash.core.data.navigation.SettingsFocus.LOSSLESS -> losslessRequester.bringIntoView()
            com.stash.core.data.navigation.SettingsFocus.LASTFM -> lastFmRequester.bringIntoView()
            null -> Unit
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // -- Header -----------------------------------------------------------
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )

        // -- Playback (Online / Offline mode) --------------------------------
        // Canonical home for the streaming-mode preference. Settings is where
        // users expect to find app-wide modes; the Home top-bar chip
        // (StreamingModeChip) is the quick-access surface that writes to the
        // same preference. Both render the OnlineOfflinePicker so the control
        // surface is identical regardless of entry point.
        if (com.stash.core.common.constants.StashConstants.STREAMING_ENGINE_ENABLED) {
            SectionHeader(title = "Playback")
            GlassCard {
                Column(modifier = Modifier.fillMaxWidth()) {
                    com.stash.core.ui.components.OnlineOfflinePicker(
                        streamingEnabled = streamingEnabled,
                        onSelect = onStreamingToggle,
                    )
                }
            }
        }

        // -- Support section --------------------------------------------------
        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

        SectionHeader(title = "Support Stash")

        GlassCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "If Stash replaced a subscription for you, consider supporting the project.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    androidx.compose.material3.OutlinedButton(
                        onClick = { uriHandler.openUri("https://ko-fi.com/rawnald") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FavoriteBorder,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Donate", style = MaterialTheme.typography.labelMedium)
                    }

                    androidx.compose.material3.OutlinedButton(
                        onClick = { uriHandler.openUri("https://github.com/rawnaldclark/Stash") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Star", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        // -- Accounts section -------------------------------------------------
        SectionHeader(title = "Accounts")

        AccountConnectionCard(
            serviceName = "Spotify",
            icon = Icons.Rounded.MusicNote,
            accentColor = extendedColors.spotifyGreen,
            authState = uiState.spotifyAuthState,
            onConnect = onConnectSpotify,
            onDisconnect = onDisconnectSpotify,
            // v0.9.13: auto-save toggle lives INSIDE the Spotify card, mirroring
            // the YT Music history pattern below. Always rendered so the
            // feature stays discoverable; SpotifyAutoSaveSection itself
            // handles the disconnected-greyed state internally.
            extraContent = {
                com.stash.feature.settings.components.SpotifyAutoSaveSection(
                    enabled = uiState.autoSaveEnabled,
                    threshold = uiState.autoSaveThreshold,
                    autoSavedCountLast7Days = uiState.autoSavedCountLast7Days,
                    spotifyConnected = uiState.spotifyAuthState is com.stash.core.auth.model.AuthState.Connected,
                    onToggle = onAutoSaveEnabledChanged,
                    onThresholdChanged = onAutoSaveThresholdChanged,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            },
        )

        AccountConnectionCard(
            serviceName = "YouTube Music",
            icon = Icons.Rounded.PlayCircle,
            accentColor = extendedColors.youtubeRed,
            authState = uiState.youTubeAuthState,
            onConnect = onConnectYouTube,
            onDisconnect = onDisconnectYouTube,
            // Sync toggle lives INSIDE the YT Music card, below the connect
            // row. Always rendered so the feature stays discoverable for
            // users who haven't connected yet — YouTubeHistorySyncSection
            // greys itself out and shows "Connect YouTube Music first" in
            // the disconnected state.
            extraContent = {
                YouTubeHistorySyncSection(
                    enabled = uiState.ytHistoryEnabled,
                    health = uiState.ytHistoryHealth,
                    pendingCount = uiState.ytPendingCount,
                    ytConnected = uiState.youTubeAuthState is com.stash.core.auth.model.AuthState.Connected,
                    onToggle = onYouTubeHistoryEnabledChanged,
                    onRetry = onRetryYouTubeHistory,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            },
        )

        // Last.fm lives in the Accounts group (v0.4.1 relocation) so
        // users who are scanning for "sign-in / connect" surfaces see
        // all three services together. The actual connect UX is still
        // different enough (web-auth vs cookie / OAuth) that we render
        // via its own composable instead of AccountConnectionCard.
        val lastFmUriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        GlassCard(
            modifier = Modifier.bringIntoViewRequester(lastFmRequester),
        ) {
            LastFmSection(
                state = uiState.lastFmState,
                onConnect = {
                    onConnectLastFm { url ->
                        runCatching { lastFmUriHandler.openUri(url) }
                    }
                },
                onFinish = onFinishLastFmAuth,
                onDisconnect = onDisconnectLastFm,
                onDismissError = onDismissLastFmError,
                onSyncScrobblesNow = onSyncScrobblesNow,
                isScrobbleDraining = uiState.isScrobbleDraining,
            )
            // Surface the result of a manual drain inline (keeps the
            // Settings screen Compose-local; no scaffold/snackbar host
            // dependency). The Text sticks around for ~3s and fades; the
            // VM clears the state so repeated taps render fresh.
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
                    onClearScrobbleDrainResult()
                }
            }
        }

        // -- Audio Quality section (top-level) --------------------------------
        // v0.9.17: when lossless is on, this top-level card hides — the YT
        // tier picker is reachable through the "YouTube fallback" expander
        // inside the lossless card below, so the user has a single mental
        // anchor for "audio quality." When lossless is off, today's layout
        // is preserved exactly: standalone picker governing yt-dlp tier.
        if (!uiState.losslessEnabled) {
            SectionHeader(title = "Audio Quality")

            GlassCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                ) {
                    // -- Download quality (radio group) -------------------------
                    Text(
                        text = "Download quality",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    AudioQualityPicker(
                        selected = uiState.audioQuality,
                        onSelected = onQualityChanged,
                    )
                }
            }
        }

        // -- Lossless audio card ---------------------------------------------
        GlassCard(modifier = Modifier.bringIntoViewRequester(losslessRequester)) {
            var advancedExpanded by remember(uiState.losslessEnabled) { mutableStateOf(false) }
            val chevronRotation by animateFloatAsState(
                targetValue = if (advancedExpanded) 90f else 0f,
                label = "advancedChevron",
            )
            // v0.9.17: YouTube-fallback expander state. Re-keyed on the
            // losslessEnabled flip so the card collapses cleanly when the
            // user toggles lossless off (returning the picker to its top-
            // level home) and on (revealing it nested behind the expander).
            var fallbackExpanded by remember(uiState.losslessEnabled) { mutableStateOf(false) }
            val fallbackChevronRotation by animateFloatAsState(
                targetValue = if (fallbackExpanded) 90f else 0f,
                label = "fallback-chevron",
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
            ) {
                // -- Lossless toggle row -------------------------------------
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Lossless downloads",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (uiState.losslessEnabled) {
                                "FLAC routing active. Files ~10\u00D7 larger than MP3."
                            } else {
                                "Studio-quality FLAC via Qobuz. Files ~10\u00D7 larger than MP3."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = uiState.losslessEnabled,
                        onCheckedChange = onLosslessEnabledChanged,
                        modifier = Modifier.semantics { role = Role.Switch },
                    )
                }

                // -- Routing status + advanced expander (only when lossless on) -
                AnimatedVisibility(
                    visible = uiState.losslessEnabled,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(14.dp))

                        // v0.9.13 ROUTING block — replaces the prominent
                        // "Connect to squid.wtf" CTA. The previous treatment
                        // implied lossless required a captcha to function;
                        // it doesn't — kenny carries lossless on its own,
                        // squid is an optional second source. Now we show
                        // both sources and let the user solve the captcha
                        // inline if they want the redundancy.
                        LosslessRoutingStatus(
                            squidStatus = uiState.squidCaptchaStatus,
                            onSolveCaptcha = onNavigateToSquidWtfCaptcha,
                        )

                        // -- Lossless quality picker --------------------------
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Lossless quality",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Column(modifier = Modifier.selectableGroup()) {
                            // Order top-down: MAX → HI_RES → CD (best-quality first).
                            listOf(
                                LosslessQualityTier.MAX,
                                LosslessQualityTier.HI_RES,
                                LosslessQualityTier.CD,
                            ).forEach { tier ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = uiState.losslessQualityTier == tier,
                                            onClick = { onLosslessQualityTierChanged(tier) },
                                            role = Role.RadioButton,
                                        )
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = uiState.losslessQualityTier == tier,
                                        onClick = null, // handled by Row's selectable
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = MaterialTheme.colorScheme.primary,
                                            unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        ),
                                    )
                                    Column(modifier = Modifier.padding(start = 8.dp)) {
                                        Text(
                                            text = tier.displayLabel,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = tier.sizeHint,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }

                        // -- YouTube fallback expander row (v0.9.17) -----------
                        // Hosts the relocated yt-dlp tier picker plus the
                        // master fallback toggle. Mirrors the Advanced
                        // expander's chevron / padding / label treatment.
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { fallbackExpanded = !fallbackExpanded }
                                .semantics {
                                    role = Role.Button
                                    stateDescription = if (fallbackExpanded) "expanded" else "collapsed"
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                modifier = Modifier.graphicsLayer(rotationZ = fallbackChevronRotation),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "YouTube fallback",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = if (uiState.youtubeFallbackEnabled) "on" else "off",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        AnimatedVisibility(
                            visible = fallbackExpanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(
                                        checked = uiState.youtubeFallbackEnabled,
                                        onCheckedChange = onYoutubeFallbackChanged,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Use YouTube when lossless fails",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                if (uiState.youtubeFallbackEnabled) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    AudioQualityPicker(
                                        selected = uiState.audioQuality,
                                        onSelected = onQualityChanged,
                                    )
                                }
                            }
                        }

                        // -- Advanced expander row (chevron + label) -----------
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { advancedExpanded = !advancedExpanded }
                                .semantics {
                                    role = Role.Button
                                    // Spec §Accessibility: announce collapsed/expanded
                                    // state to screen readers.
                                    stateDescription = if (advancedExpanded) "expanded" else "collapsed"
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null, // parent Row carries role + stateDescription + label
                                modifier = Modifier.graphicsLayer(rotationZ = chevronRotation),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Advanced",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        AnimatedVisibility(
                            visible = advancedExpanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Or paste the captcha_verified_at cookie value directly:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = uiState.squidWtfCaptchaCookie,
                                    onValueChange = onSquidWtfCaptchaCookieChanged,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("captcha_verified_at value") },
                                    singleLine = true,
                                    placeholder = { Text("e.g. 1777687404951") },
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(
                                    onClick = onResetLosslessRateLimiter,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = "Reset lossless attempts",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // v0.9.13: heart-defaults Library & Likes section removed — heart is
        // now Stash-only toggle (standard like-button UX), so there are no
        // per-destination defaults to configure. Spotify auto-save lives
        // inside the Spotify connect card above as `extraContent`.

        // -- Downloads section ------------------------------------------------
        // Governs when background workers (Stash Discover, tag enrichment)
        // are allowed to run. Default is WiFi + charging (safest); power
        // users can loosen this to WiFi-any-time or any-network.
        SectionHeader(title = "Downloads")

        GlassCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
            ) {
                Text(
                    text = "Run recommendations when",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))

                DownloadNetworkMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = uiState.downloadNetworkMode == mode,
                                onClick = { onDownloadNetworkModeChanged(mode) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = uiState.downloadNetworkMode == mode,
                            onClick = null, // handled by Row's selectable
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.primary,
                                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = mode.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

            }
        }

        // -- Stash Mixes (beta) toggle ----------------------------------------
        // Lets the user opt out of the auto-generated mix surfaces (Daily
        // Discover, Deep Cuts, First Listen) so the background discovery +
        // download workers stop running and the playlists hide from
        // Home/Library. See GitHub issues #56 and #57.
        GlassCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Stash Mixes (beta)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (uiState.stashMixesEnabled) {
                            "Daily Discover, Deep Cuts, and First Listen mixes auto-refresh in the background."
                        } else {
                            "Auto-generated mix playlists are hidden. Background discovery downloads are off."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = uiState.stashMixesEnabled,
                    onCheckedChange = onStashMixesEnabledChanged,
                    modifier = Modifier.semantics { role = Role.Switch },
                )
            }
        }

        // -- Appearance section -----------------------------------------------
        SectionHeader(title = "Appearance")

        GlassCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
            ) {
                Text(
                    text = "Theme",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))

                val themeOptions = listOf(
                    ThemeMode.LIGHT to "Light",
                    ThemeMode.DARK to "Dark",
                    ThemeMode.SYSTEM to "Follow system",
                )
                themeOptions.forEach { (mode, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = uiState.themeMode == mode,
                                onClick = { onThemeChanged(mode) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = uiState.themeMode == mode,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.primary,
                                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }

        // -- Audio Effects section --------------------------------------------
        // The full EQ UI lives on a dedicated EqualizerScreen. This row
        // navigates to it.
        SectionHeader(title = "Audio Effects")

        GlassCard(
            modifier = Modifier.clickable(onClick = onNavigateToEqualizer),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Equalizer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Equalizer",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Open Equalizer",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // -- Library Health row -----------------------------------------------
        // Drilldown into the format/bitrate breakdown of every downloaded
        // track. Used to verify what the sync is actually pulling and to
        // measure MAX-tier (format 141) yield empirically.
        GlassCard(
            modifier = Modifier.clickable(onClick = onNavigateToLibraryHealth),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Library Health",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Open Library Health",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // -- Storage section --------------------------------------------------
        SectionHeader(title = "Storage")

        val storageContext = LocalContext.current
        val contentResolver = storageContext.contentResolver
        // Tracks what action the user intended when they tapped the folder
        // picker. "SetOnly" = just pick a destination for new downloads.
        // "SetAndMove" = pick destination AND auto-start the library move
        // once the URI is saved (single tap flow for users with existing
        // libraries who want to migrate).
        var pendingPickerIntent by remember { mutableStateOf(PickerIntent.SetOnly) }
        val treePicker = androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                // Take a persistable permission BEFORE handing the URI to the
                // VM — without this, the permission is revoked when the app
                // is backgrounded and the persisted URI becomes useless.
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                onSetExternalStorage(uri)
                if (pendingPickerIntent == PickerIntent.SetAndMove) {
                    onStartMoveLibrary(uri)
                }
            }
            pendingPickerIntent = PickerIntent.SetOnly
        }

        val externalTree = uiState.externalTreeUri
        // Derive a human-readable folder name from the tree URI without
        // pulling the documentfile dep into this module. Tree URIs look like
        // `content://com.android.externalstorage.documents/tree/primary%3AMusic%2FStash`
        // — after decoding, the last path segment after the colon is the
        // visible folder.
        val externalFolderName = remember(externalTree) {
            externalTree?.lastPathSegment
                ?.substringAfterLast(':', "")
                ?.substringAfterLast('/', "")
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                ?.takeIf { it.isNotBlank() }
                ?: externalTree?.let { "External folder" }
                ?: ""
        }
        val internalPath = remember(storageContext) {
            java.io.File(storageContext.filesDir, "music").absolutePath
        }

        // Database backup -----------------------------------------------------
        val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
        ) { uri ->
            if (uri != null) onExportDatabase(uri)
        }
        val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) onImportDatabase(uri)
        }

        if (uiState.databaseBackupState !is DatabaseBackupState.Idle) {
            AlertDialog(
                onDismissRequest = {
                    if (uiState.databaseBackupState !is DatabaseBackupState.Exporting &&
                        uiState.databaseBackupState !is DatabaseBackupState.Importing) {
                        onDismissDatabaseBackupStatus()
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large,
                title = {
                    Text(
                        text = when (uiState.databaseBackupState) {
                            DatabaseBackupState.Exporting -> "Exporting Database"
                            DatabaseBackupState.Importing -> "Importing Database"
                            is DatabaseBackupState.Success -> "Success"
                            is DatabaseBackupState.Error -> "Error"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (val state = uiState.databaseBackupState) {
                            DatabaseBackupState.Exporting, DatabaseBackupState.Importing -> {
                                androidx.compose.material3.LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    text = "Processing...",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            is DatabaseBackupState.Success -> {
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            is DatabaseBackupState.Error -> {
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    if (uiState.databaseBackupState is DatabaseBackupState.Success ||
                        uiState.databaseBackupState is DatabaseBackupState.Error) {
                        TextButton(onClick = onDismissDatabaseBackupStatus) {
                            Text("OK")
                        }
                    }
                },
            )
        }

        if (uiState.showImportConfirmation) {
            AlertDialog(
                onDismissRequest = onCancelImportDatabase,
                containerColor = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large,
                title = {
                    Text(
                        text = "Overwrite Library & Settings?",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                text = {
                    Text(
                        text = "This will completely replace your existing library metadata, playlists, settings, and account connections. This action cannot be undone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = onConfirmImportDatabase,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text("Overwrite")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onCancelImportDatabase) {
                        Text("Cancel")
                    }
                },
            )
        }

        GlassCard {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Database Backup -----------------------------------------
                Text(
                    text = "Backup",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Export settings and database or import from a previous backup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { exportLauncher.launch("stash-backup.zip") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Text("Export Backup")
                    }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Text("Import Backup")
                    }
                }
            }
        }

        GlassCard {
            Column(modifier = Modifier.fillMaxWidth()) {
                StorageRow(label = "Total tracks", value = "${uiState.totalTracks}")
                Spacer(modifier = Modifier.height(8.dp))
                StorageRow(label = "Storage used", value = formatBytes(uiState.totalStorageBytes))
                Spacer(modifier = Modifier.height(12.dp))
                androidx.compose.material3.HorizontalDivider(
                    color = extendedColors.glassBorder,
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Current location ----------------------------------------
                Text(
                    text = "Download location",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when {
                        externalTree == null -> "Internal (app-private)"
                        externalFolderName.isBlank() -> "External folder (SD card / USB)"
                        else -> externalFolderName
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (externalTree != null) {
                        "Tracks are stored in this folder and survive uninstall. Visible to other apps and over USB."
                    } else {
                        internalPath
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Actions -------------------------------------------------
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            onSetPickerIntent(PickerIntent.SetOnly)
                            treePicker.launch(null)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Text(
                            text = if (externalTree != null) "Change folder" else "Pick SD / folder",
                        )
                    }
                    if (externalTree != null) {
                        OutlinedButton(
                            onClick = { onSetExternalStorage(null) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Text("Use internal")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "New downloads go to the selected location.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Move library — rendered only when there's work to do. We
                // refresh the count reactively after each move (state
                // transition to Idle) and when the user picks a new folder.
                // If everything is already in the external target the
                // section simply disappears so the button isn't a dead-end.
                var movableCount by remember { mutableStateOf<Int?>(null) }
                LaunchedEffect(uiState.moveLibraryState, externalTree) {
                    if (uiState.moveLibraryState !is com.stash.data.download.files.MoveLibraryState.Running) {
                        movableCount = runCatching { countMovableTracks() }.getOrNull()
                    }
                }

                val showMoveSection = when (uiState.moveLibraryState) {
                    com.stash.data.download.files.MoveLibraryState.Idle ->
                        (movableCount ?: 0) > 0
                    else -> true  // show progress/done/error regardless of count
                }

                if (showMoveSection) {
                    Spacer(modifier = Modifier.height(12.dp))
                    androidx.compose.material3.HorizontalDivider(
                        color = extendedColors.glassBorder,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    MoveLibrarySection(
                        state = uiState.moveLibraryState,
                        hasExternalFolder = externalTree != null,
                        movableCount = movableCount ?: 0,
                        onStart = {
                            if (externalTree != null) {
                                onStartMoveLibrary(externalTree)
                            } else {
                                onSetPickerIntent(PickerIntent.SetAndMove)
                                treePicker.launch(null)
                            }
                        },
                        onCancel = onCancelMoveLibrary,
                        onDismiss = onDismissMoveLibrary,
                    )
                }
            }
        }

        // Phase 8: Library maintenance (Blocked Songs + Fix wrong-version
        // downloads) relocated to the Sync tab. Settings no longer carries
        // a Library section.

        // -- Diagnostics section ----------------------------------------------
        // Crash-to-file: writes uncaught exceptions to cacheDir/crashes/ so
        // the user can attach the latest one to an email / Discord / GitHub
        // issue. Zero network, zero auto-upload. Disabled when no files
        // exist; LaunchedEffect refreshes liveness on every entry so a
        // share that resolves the issue immediately flips the button off
        // on the next screen visit.
        val diagnosticsContext = LocalContext.current
        LaunchedEffect(Unit) { onDiagnosticsRefresh() }

        SectionHeader(title = "Diagnostics")

        GlassCard {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Share latest crash report",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (uiState.hasCrashReport) {
                        "Attach the most recent crash log to email or chat. Stays on device until you share."
                    } else {
                        "No recent crashes."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    enabled = uiState.hasCrashReport,
                    onClick = {
                        val target = onShareLatestCrashReport()
                        if (target == null) {
                            Toast.makeText(
                                diagnosticsContext,
                                "No crash report to share.",
                                Toast.LENGTH_SHORT,
                            ).show()
                            return@OutlinedButton
                        }
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_STREAM, target.contentUri)
                            putExtra(
                                android.content.Intent.EXTRA_SUBJECT,
                                "Stash crash report",
                            )
                            // FLAG_GRANT_READ is required so the recipient
                            // app can actually read the file:// URI behind
                            // the content:// shim.
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        val chooser = android.content.Intent.createChooser(send, "Share crash report")
                            .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                        runCatching { diagnosticsContext.startActivity(chooser) }
                            .onFailure {
                                Toast.makeText(
                                    diagnosticsContext,
                                    "No app available to share the report.",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text("Share latest crash report")
                }
            }
        }

        // -- About section ----------------------------------------------------
        SectionHeader(title = "About")

        val aboutContext = LocalContext.current
        val installedVersion = remember(aboutContext) {
            runCatching {
                aboutContext.packageManager
                    .getPackageInfo(aboutContext.packageName, 0)
                    .versionName
            }.getOrNull() ?: "0.3.5-beta.1"
        }

        GlassCard {
            Column(modifier = Modifier.fillMaxWidth()) {
                StorageRow(label = "Version", value = installedVersion)
                Spacer(modifier = Modifier.height(8.dp))
                StorageRow(label = "License", value = "GPL-3.0")
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        UpdateCheckWorker.enqueueOneTimeCheck(aboutContext)
                        Toast.makeText(
                            aboutContext,
                            "Checking for updates\u2026",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text("Check for updates")
                }
            }
        }

        // Bottom padding for navigation bar clearance
        Spacer(modifier = Modifier.height(80.dp))
    }
}

/**
 * Renders the Last.fm connection card. Four states from the VM drive
 * the layout: NotConfigured (disabled explanation), Disconnected
 * (Connect button), AwaitingAuth (Finish connecting after browser),
 * Connected (username + pending scrobbles + Disconnect), Error
 * (message + Dismiss).
 */
@Composable
private fun LastFmSection(
    state: LastFmAuthState,
    onConnect: () -> Unit,
    onFinish: () -> Unit,
    onDisconnect: () -> Unit,
    onDismissError: () -> Unit,
    onSyncScrobblesNow: () -> Unit,
    isScrobbleDraining: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when (state) {
            LastFmAuthState.NotConfigured -> {
                Text(
                    text = "Not configured",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "This build of Stash doesn't include a Last.fm API key. " +
                        "A developer rebuilding with a key in local.properties unlocks this feature.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LastFmAuthState.Disconnected -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Scrobble your plays",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    androidx.compose.material3.OutlinedButton(
                        onClick = onConnect,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Text("Connect Last.fm")
                    }
                }
            }
            is LastFmAuthState.AwaitingAuth -> {
                Text(
                    text = "Waiting for approval",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Your browser should be open on Last.fm. Tap \"Yes, allow access\" on their page, then come back and tap Finish below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                androidx.compose.material3.Button(
                    onClick = onFinish,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Finish connecting")
                }
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.material3.TextButton(
                    onClick = onDismissError,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cancel")
                }
            }
            is LastFmAuthState.Connected -> {
                Text(
                    text = "Connected as ${state.username}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (state.pendingScrobbles > 0) {
                        "Scrobbling your plays. ${state.pendingScrobbles} queued to submit."
                    } else {
                        "Scrobbling your plays. Everything up to date."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                // "Sync scrobbles now" — manual drain. Useful right after
                // the Last.fm connect handshake when cold-start import +
                // accumulated local plays can leave hundreds queued up.
                androidx.compose.material3.Button(
                    onClick = onSyncScrobblesNow,
                    enabled = !isScrobbleDraining && state.pendingScrobbles > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = when {
                            isScrobbleDraining -> "Syncing…"
                            state.pendingScrobbles == 0 -> "Nothing to sync"
                            else -> "Sync scrobbles now"
                        },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text("Disconnect")
                }
            }
            is LastFmAuthState.Error -> {
                Text(
                    text = "Couldn't connect",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                androidx.compose.material3.TextButton(
                    onClick = onDismissError,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Dismiss")
                }
            }
        }
    }
}

/**
 * Tracks what the user meant when they tapped the SAF folder-picker.
 * [SetOnly] = redirect new downloads; [SetAndMove] = also start the
 * library migration to the picked folder as soon as it's granted.
 */
private enum class PickerIntent { SetOnly, SetAndMove, SetAndRestart }

/**
 * Renders the "Move existing library" action inside the Storage card.
 *
 * Shows four visual states driven by the underlying
 * [com.stash.data.download.files.MoveLibraryState]:
 * - **Idle** — prompt + "Move library to this folder" button.
 * - **Running(c, t)** — live progress ("Moving c of t...") + linear bar + Cancel.
 * - **Done(moved, failed)** — result summary + Dismiss.
 * - **Error(msg)** — error text + Dismiss.
 */
@Composable
private fun MoveLibrarySection(
    state: com.stash.data.download.files.MoveLibraryState,
    hasExternalFolder: Boolean,
    movableCount: Int,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        com.stash.data.download.files.MoveLibraryState.Idle -> {
            Text(
                text = "Existing library",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (hasExternalFolder) {
                    "Move $movableCount track${if (movableCount == 1) "" else "s"} still on your device into the folder above so they're accessible over USB too."
                } else {
                    "Move $movableCount track${if (movableCount == 1) "" else "s"} on your device to an external folder (SD / USB) so you can access them over USB too. You'll pick the destination next."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.OutlinedButton(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(
                    text = if (hasExternalFolder) {
                        "Move $movableCount track${if (movableCount == 1) "" else "s"} to this folder"
                    } else {
                        "Pick destination and move library"
                    },
                )
            }
        }
        is com.stash.data.download.files.MoveLibraryState.Running -> {
            Text(
                text = "Moving ${state.current} of ${state.total}...",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            androidx.compose.material3.LinearProgressIndicator(
                progress = {
                    if (state.total == 0) 0f
                    else state.current.toFloat() / state.total.toFloat()
                },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.TextButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Cancel")
            }
        }
        is com.stash.data.download.files.MoveLibraryState.Done -> {
            Text(
                text = buildString {
                    append("Moved ${state.moved} track")
                    if (state.moved != 1) append("s")
                    if (state.failed > 0) {
                        append(" • ${state.failed} failed")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (state.failed > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Failed tracks stay in internal storage. Try again later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Dismiss")
            }
        }
        is com.stash.data.download.files.MoveLibraryState.Error -> {
            Text(
                text = "Couldn't move library",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Dismiss")
            }
        }
    }
}

/**
 * A styled section header label.
 */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * A horizontal row displaying a label on the left and a value on the right.
 */
@Composable
private fun StorageRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * v0.9.13 — ROUTING status block for the lossless source chain.
 *
 * Replaces the legacy "Connect to squid.wtf" CTA which falsely implied
 * lossless required a captcha to function. Reality: kenny carries
 * lossless without auth or captcha; squid is an optional second source
 * that the user can unlock via captcha. When squid is down, kenny
 * silently fills in.
 *
 * Visual is dublab-influenced: mono caps header, indented `↳` rows,
 * small status dots (filled = configured, outlined = optional). Solve
 * link inline on the squid row when no cookie is set.
 *
 * Honesty caveat: we don't have ping/health telemetry yet, so we never
 * claim "live" — we use "active" (= configured and reachable in the
 * resolver chain). v0.9.14 can add real-time health based on the
 * AggregatorRateLimiter / source-success cache.
 */
@Composable
private fun LosslessRoutingStatus(
    squidStatus: com.stash.feature.settings.components.SquidCaptchaStatus,
    onSolveCaptcha: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mono = androidx.compose.ui.text.font.FontFamily.Monospace
    val (squidConfigured, squidLabel, showSolveLink) = when (squidStatus) {
        com.stash.feature.settings.components.SquidCaptchaStatus.NotConfigured ->
            Triple(false, "optional", true)
        com.stash.feature.settings.components.SquidCaptchaStatus.Active ->
            Triple(true, "active", false)
        com.stash.feature.settings.components.SquidCaptchaStatus.Expired ->
            // Cookie present but server-rejected — keep the dot filled
            // (user did set it up) but surface "expired" + the solver
            // entry-point so they can re-verify in one tap.
            Triple(true, "expired", true)
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "ROUTING",
            fontFamily = mono,
            style = MaterialTheme.typography.labelSmall.copy(
                letterSpacing = 1.2.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        RoutingRow(
            host = "kennyy.com.br",
            configured = true,
            statusLabel = "active",
        )
        RoutingRow(
            host = "squid.wtf",
            configured = squidConfigured,
            statusLabel = squidLabel,
            actionLabel = if (showSolveLink) "solve captcha \u2192" else null,
            onAction = if (showSolveLink) onSolveCaptcha else null,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Lossless works on any active source. Adding squid gives you a backup host.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Single row inside [LosslessRoutingStatus]: indent arrow, host name,
 * status dot + label. Optional action link (e.g. "solve captcha →") on
 * the right when the source needs user setup.
 */
@Composable
private fun RoutingRow(
    host: String,
    configured: Boolean,
    statusLabel: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val mono = androidx.compose.ui.text.font.FontFamily.Monospace
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "\u21B3",
            fontFamily = mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            text = host,
            fontFamily = mono,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        // Status dot — filled-primary when configured, outlined-muted when not.
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (configured) MaterialTheme.colorScheme.primary
                    else androidx.compose.ui.graphics.Color.Transparent,
                )
                .border(
                    width = if (configured) 0.dp else 1.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = CircleShape,
                ),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = statusLabel,
            fontFamily = mono,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = actionLabel,
                fontFamily = mono,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onAction),
            )
        }
    }
}

/**
 * Formats a byte count into a human-readable string (B, KB, MB, GB).
 */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val safeIndex = digitGroups.coerceIn(0, units.lastIndex)
    return "%.1f %s".format(bytes / Math.pow(1024.0, safeIndex.toDouble()), units[safeIndex])
}
