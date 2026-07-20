package com.stash.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthService
import com.stash.core.auth.model.AuthState
import com.stash.core.auth.youtube.YouTubeCookieHelper
import android.content.Context
import android.net.Uri
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.diagnostics.CrashFileStore
import com.stash.core.data.prefs.DownloadNetworkPreference
import com.stash.core.data.prefs.LikePreferences
import com.stash.core.data.social.Destination
import com.stash.core.data.prefs.QualityPreference
import com.stash.core.data.prefs.StoragePreference
import com.stash.core.data.prefs.ThemePreference
import com.stash.core.data.prefs.YouTubeHistoryPreference
import com.stash.core.data.youtube.YouTubeHistoryScrobbler
import com.stash.core.data.youtube.YouTubeScrobblerHealth
import com.stash.core.data.youtube.YouTubeScrobblerState
import com.stash.core.data.sync.workers.StashDiscoveryWorker
import com.stash.core.data.sync.workers.TagEnrichmentWorker
import com.stash.core.model.DownloadNetworkMode
import dagger.hilt.android.qualifiers.ApplicationContext
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.lastfm.LastFmScrobbler
import com.stash.core.data.lastfm.LastFmSession
import com.stash.core.data.lastfm.LastFmSessionPreference
import com.stash.core.data.db.DatabaseBackupManager
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.data.download.files.LibrarySizeBreakdown
import com.stash.data.download.files.LibrarySizeHolder
import com.stash.data.download.files.MoveLibraryCoordinator
import com.stash.data.download.files.MoveLibraryState
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.arcod.ArcodCredentialStore
import com.stash.data.download.lossless.qbdlx.QbdlxCredentialStore
import com.stash.data.download.lossless.qbdlx.QbdlxTokenChoice
import com.stash.data.download.lossless.qobuz.QobuzSource
import com.stash.data.download.prefs.StreamingQualityPreferences
import com.stash.feature.settings.components.squidCaptchaStatus
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.QualityTier
import com.stash.core.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Settings screen.
 *
 * Orchestrates Spotify sp_dc cookie auth, YouTube Music cookie auth, quality selection,
 * and library storage stats. Both Spotify and YouTube use a cookie-paste flow where
 * the user copies cookies from their browser.
 *
 * Audio quality changes are persisted to DataStore via [QualityPreference] so they
 * survive app restarts and are picked up by the download pipeline.
 *
 * Equalizer state is managed by [EqualizerViewModel] on the dedicated EQ screen;
 * this ViewModel no longer owns any EQ methods.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val tokenManager: TokenManager,
    private val musicRepository: MusicRepository,
    private val librarySizeHolder: LibrarySizeHolder,
    private val qualityPreference: QualityPreference,
    private val themePreference: ThemePreference,
    private val sleepTimerController: com.stash.core.media.SleepTimerController,
    private val storagePreference: StoragePreference,
    private val downloadNetworkPreference: DownloadNetworkPreference,
    private val moveLibraryCoordinator: MoveLibraryCoordinator,
    private val youTubeCookieHelper: YouTubeCookieHelper,
    private val lastFmApiClient: LastFmApiClient,
    private val lastFmSessionPreference: LastFmSessionPreference,
    private val lastFmCredentials: LastFmCredentials,
    private val listeningEventDao: ListeningEventDao,
    private val lastFmScrobbler: LastFmScrobbler,
    private val youTubeHistoryPreference: YouTubeHistoryPreference,
    private val stashMixPreference: com.stash.core.data.prefs.StashMixPreference,
    private val homeDiscoveryPreference: com.stash.core.data.prefs.HomeDiscoveryPreference,
    private val nowPlayingPreference: com.stash.core.data.prefs.NowPlayingPreference,
    private val youTubeHistoryScrobbler: YouTubeHistoryScrobbler,
    private val youTubeScrobblerState: YouTubeScrobblerState,
    private val losslessPrefs: LosslessSourcePreferences,
    private val streamingQualityPrefs: StreamingQualityPreferences,
    private val losslessRateLimiter: AggregatorRateLimiter,
    private val qobuzSource: QobuzSource,
    private val arcodCredentialStore: ArcodCredentialStore,
    private val qbdlxCredentialStore: QbdlxCredentialStore,
    private val likePreferences: LikePreferences,
    private val trackDao: TrackDao,
    private val settingsDeepLinkController: com.stash.core.data.navigation.SettingsDeepLinkController,
    private val crashFileStore: CrashFileStore,
    private val streamingPreference: com.stash.core.data.prefs.StreamingPreference,
    private val crossfadePreference: com.stash.core.data.prefs.CrossfadePreference,
    private val databaseBackupManager: DatabaseBackupManager,
) : ViewModel() {

    /**
     * Live streaming-mode flag (Online vs Offline). Mirrors what the
     * Home top-bar chip reads from the same [streamingPreference] —
     * Settings is the canonical home for this preference; the chip is
     * the quick-access surface. Both end up writing through
     * [MusicRepository.applyStreamingMode].
     */
    val streamingEnabled: kotlinx.coroutines.flow.StateFlow<Boolean> =
        streamingPreference.enabled.stateIn(
            scope = viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    /**
     * Flip streaming mode from the Settings tab. Routes through the
     * repository so workers + prefs stay in sync — same path as
     * [HomeViewModel.onStreamingToggle], minus the first-time
     * disclosure dialog (a user opening Settings is already deliberate;
     * we don't need the educational popup here).
     */
    fun onStreamingToggle(enabled: Boolean) {
        if (enabled == streamingEnabled.value) return
        viewModelScope.launch {
            musicRepository.applyStreamingMode(enabled = enabled)
        }
    }

    /**
     * Live "stream on cellular" preference. Surfaced as a switch in the
     * Settings Playback section; PlayerRepositoryImpl.buildMediaItemForTrack
     * reads it via streamingPreference.streamOnCellular to refuse streams
     * on metered networks when this is false (the default).
     */
    val streamOnCellular: kotlinx.coroutines.flow.StateFlow<Boolean> =
        streamingPreference.streamOnCellular.stateIn(
            scope = viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    /** Persist the cellular-streaming preference flip. */
    fun onStreamOnCellularToggle(value: Boolean) {
        if (value == streamOnCellular.value) return
        viewModelScope.launch {
            streamingPreference.setStreamOnCellular(value)
        }
    }

    /**
     * Test-only "Force YouTube fallback" toggle. When on,
     * [StreamSourceRegistry] skips Kennyy/Squid and streams every track
     * via YouTube — surfaced as a switch in the Diagnostics card so the
     * lossless-down fallback path can be reproduced on demand.
     */
    val forceYouTubeFallback: kotlinx.coroutines.flow.StateFlow<Boolean> =
        streamingPreference.forceYouTubeFallback.stateIn(
            scope = viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    /** Persist the force-YouTube-fallback test toggle flip. */
    fun setForceYouTubeFallback(v: Boolean) = viewModelScope.launch {
        streamingPreference.setForceYouTubeFallback(v)
    }

    /** Test toggle: route streaming + downloads through ARCOD only. */
    val forceArcodOnly: kotlinx.coroutines.flow.StateFlow<Boolean> =
        streamingPreference.forceArcodOnly.stateIn(
            scope = viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    /**
     * Test-only "Stream via amz" toggle. When on, BOTH the streaming and
     * lossless-download registries route through the amz (Amazon Music)
     * source only — used to exercise the amz source on demand.
     */
    val forceAmzOnly: kotlinx.coroutines.flow.StateFlow<Boolean> =
        streamingPreference.forceAmzOnly.stateIn(
            scope = viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    val forceQbdlxOnly: kotlinx.coroutines.flow.StateFlow<Boolean> =
        streamingPreference.forceQbdlxOnly.stateIn(
            scope = viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    /** Persist the force-qbdlx-only test toggle flip. */
    fun setForceQbdlxOnly(v: Boolean) = viewModelScope.launch {
        streamingPreference.setForceQbdlxOnly(v)
    }

    /** Persist the force-arcod-only test toggle flip. */
    fun setForceArcodOnly(v: Boolean) = viewModelScope.launch {
        streamingPreference.setForceArcodOnly(v)
    }

    /** Crossfade on/off — drives the Playback section toggle. Off by default. */
    val crossfadeEnabled: kotlinx.coroutines.flow.StateFlow<Boolean> =
        crossfadePreference.enabled.stateIn(
            scope = viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    /** Crossfade fade duration in ms (clamped 1000–12000); drives the slider. */
    val crossfadeDurationMs: kotlinx.coroutines.flow.StateFlow<Long> =
        crossfadePreference.durationMs.stateIn(
            scope = viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
            initialValue = 6000L,
        )

    /** Persist the crossfade on/off flip. */
    fun onCrossfadeToggle(enabled: Boolean) {
        if (enabled == crossfadeEnabled.value) return
        viewModelScope.launch { crossfadePreference.setEnabled(enabled) }
    }

    /** Persist the crossfade duration (clamped on write). */
    fun onCrossfadeDurationChange(ms: Long) = viewModelScope.launch {
        crossfadePreference.setDurationMs(ms)
    }

    /** Persist the force-amz-only test toggle flip. */
    fun setForceAmzOnly(v: Boolean) = viewModelScope.launch {
        streamingPreference.setForceAmzOnly(v)
    }

    /** Internal mutable UI state that is combined with token-manager flows. */
    private val _localState = MutableStateFlow(LocalState())

    /**
     * True when every qbdlx token (pasted + pool) is dead — drives the
     * Settings "expired, paste a fresh token" badge. The store exposes only a
     * suspend `allDead()`, so we poll it on construction and after each paste
     * (the only events that flip it). ponytail: poll-on-change, add a store
     * Flow if another surface needs live updates.
     *
     * MUST be declared above the [init] block: Kotlin initializes properties
     * top-to-bottom, and `init`'s refreshQbdlxExpired() writes to this field
     * (synchronously, when allDead()'s DataStore read hits its cache) — a
     * below-init declaration left it null → NPE opening Settings.
     */
    private val _qbdlxExpired = MutableStateFlow(false)
    val qbdlxExpired: StateFlow<Boolean> = _qbdlxExpired

    private val _qbdlxTokenChoices = MutableStateFlow<List<QbdlxTokenChoice>>(emptyList())
    val qbdlxTokenChoices: StateFlow<List<QbdlxTokenChoice>> = _qbdlxTokenChoices

    private val _qbdlxPinnedToken = MutableStateFlow<String?>(null)
    val qbdlxPinnedToken: StateFlow<String?> = _qbdlxPinnedToken

    init {
        // Refresh on construction so the Diagnostics card shows the
        // correct enabled/disabled state on first frame. Cheap (a
        // single directory listing) — no need to rerun on every flow tick.
        // Must follow _localState declaration: Kotlin initializes properties
        // top-to-bottom and refreshDiagnostics() writes to _localState.
        refreshDiagnostics()
        refreshQbdlxExpired()
        refreshQbdlxTokens()
    }

    /**
     * v0.9.13: One-shot deep-link focus from Home banners. Read once on
     * Settings entry; the screen scrolls the targeted card into view and
     * clears the value so re-entry doesn't re-scroll. Null means "no
     * focus requested — render Settings at the top as usual".
     */
    fun consumeDeepLinkFocus(): com.stash.core.data.navigation.SettingsFocus? =
        settingsDeepLinkController.consume()

    // Phase 8: `blockedCount` + `onRunYtLibraryBackfill` relocated to
    // SyncViewModel — the Blocked Songs + Fix-wrong-version rows moved
    // out of Settings into the Sync tab's Library section.

    /**
     * v0.9.13: count of tracks auto-saved to Spotify in the last 7 days.
     * Re-anchors on every `autoSaveEnabled` flip so the `sinceMs` boundary
     * is fresh whenever the user revisits Settings — no need to reschedule
     * a cron-style refresh just for a diagnostics line.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val autoSavedCountLast7Days: kotlinx.coroutines.flow.Flow<Int> =
        likePreferences.autoSaveEnabled.flatMapLatest {
            trackDao.autoSavedSinceCount(
                System.currentTimeMillis() - 7L * 24 * 3600 * 1000,
            )
        }

    /**
     * The main UI state, combining reactive auth states from [TokenManager],
     * the persisted quality tier, and local UI state.
     */
    val uiState: StateFlow<SettingsUiState> = combine(
        tokenManager.spotifyAuthState,
        tokenManager.youTubeAuthState,
        musicRepository.getTrackCount(),
        librarySizeHolder.size,
        qualityPreference.qualityTier,
        themePreference.themeMode,
        storagePreference.externalTreeUri,
        moveLibraryCoordinator.state,
        lastFmSessionPreference.session,
        listeningEventDao.pendingScrobbleCount(),
        downloadNetworkPreference.mode,
        _localState,
        youTubeHistoryPreference.enabled,
        youTubeHistoryScrobbler.health,
        listeningEventDao.pendingYtScrobbleCount(),
        losslessPrefs.enabled,
        losslessPrefs.captchaCookieValue,
        losslessPrefs.qualityTier,
        qobuzSource.lastKnownBadCookie,
        likePreferences.autoSaveEnabled,
        likePreferences.autoSaveThreshold,
        likePreferences.heartDefaultStash,
        likePreferences.heartDefaultSpotify,
        likePreferences.heartDefaultYtMusic,
        autoSavedCountLast7Days,
        losslessPrefs.youtubeFallbackEnabled,
        stashMixPreference.enabled,
        likePreferences.mirrorLikesSpotify,
        likePreferences.mirrorLikesYtMusic,
        arcodCredentialStore.accessToken,
        streamingQualityPrefs.wifiTier,
        streamingQualityPrefs.cellularTier,
        streamingQualityPrefs.saveData,
        themePreference.amoledDark,
        homeDiscoveryPreference.enabled,
        nowPlayingPreference.ambientAnimationEnabled,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val spotifyAuth = values[0] as AuthState
        val youTubeAuth = values[1] as AuthState
        val trackCount = values[2] as Int
        val storageBytes = (values[3] as LibrarySizeBreakdown).totalBytes
        val quality = values[4] as QualityTier
        val theme = values[5] as ThemeMode
        val externalTree = values[6] as Uri?
        val moveState = values[7] as MoveLibraryState
        val lastFmSession = values[8] as LastFmSession?
        val pendingScrobbles = values[9] as Int
        val downloadNetworkMode = values[10] as DownloadNetworkMode
        val local = values[11] as LocalState
        val ytHistoryEnabled = values[12] as Boolean
        val ytHistoryHealth = values[13] as YouTubeScrobblerHealth
        val ytPendingCount = values[14] as Int
        val losslessEnabled = values[15] as Boolean
        val squidWtfCaptchaCookie = (values[16] as String?).orEmpty()
        val losslessQualityTier = values[17] as LosslessQualityTier
        val lastKnownBadCookie = values[18] as String?
        val autoSaveEnabled = values[19] as Boolean
        val autoSaveThreshold = values[20] as Int
        val heartDefaultStash = values[21] as Boolean
        val heartDefaultSpotify = values[22] as Boolean
        val heartDefaultYtMusic = values[23] as Boolean
        val autoSavedCount7d = values[24] as Int
        val youtubeFallbackEnabled = values[25] as Boolean
        val stashMixesEnabled = values[26] as Boolean
        val mirrorLikesSpotify = values[27] as Boolean
        val mirrorLikesYtMusic = values[28] as Boolean
        val arcodConnected = !(values[29] as String?).isNullOrBlank()
        val streamingWifiTier = values[30] as LosslessQualityTier
        val streamingCellularTier = values[31] as LosslessQualityTier
        val streamingSaveData = values[32] as Boolean
        val amoledDark = values[33] as Boolean
        val qobuzDiscoveryEnabled = values[34] as Boolean
        val ambientAnimationEnabled = values[35] as Boolean

        val lastFmState: LastFmAuthState = local.lastFmAuthOverride
            ?: when {
                !lastFmCredentials.isConfigured -> LastFmAuthState.NotConfigured
                lastFmSession != null -> LastFmAuthState.Connected(
                    username = lastFmSession.username,
                    pendingScrobbles = pendingScrobbles,
                )
                else -> LastFmAuthState.Disconnected
            }

        SettingsUiState(
            spotifyAuthState = spotifyAuth,
            youTubeAuthState = youTubeAuth,
            audioQuality = quality,
            themeMode = theme,
            amoledDark = amoledDark,
            downloadNetworkMode = downloadNetworkMode,
            totalStorageBytes = storageBytes,
            totalTracks = trackCount,
            showSpotifyWebLogin = local.showSpotifyWebLogin,
            showYouTubeWebLogin = local.showYouTubeWebLogin,
            showYouTubeCookieDialog = local.showYouTubeCookieDialog,
            showSpotifyCookieDialog = local.showSpotifyCookieDialog,
            spotifyCookieError = local.spotifyCookieError,
            isSpotifyCookieValidating = local.isSpotifyCookieValidating,
            youTubeCookieError = local.youTubeCookieError,
            isYouTubeCookieValidating = local.isYouTubeCookieValidating,
            youTubeError = local.youTubeError,
            externalTreeUri = externalTree,
            moveLibraryState = moveState,
            lastFmState = lastFmState,
            isScrobbleDraining = local.isScrobbleDraining,
            scrobbleDrainResult = local.lastScrobbleDrainResult,
            ytHistoryEnabled = ytHistoryEnabled,
            stashMixesEnabled = stashMixesEnabled,
            qobuzDiscoveryEnabled = qobuzDiscoveryEnabled,
            ambientAnimationEnabled = ambientAnimationEnabled,
            ytHistoryHealth = ytHistoryHealth,
            ytPendingCount = ytPendingCount,
            losslessEnabled = losslessEnabled,
            squidWtfCaptchaCookie = squidWtfCaptchaCookie,
            squidCaptchaStatus = squidCaptchaStatus(squidWtfCaptchaCookie, lastKnownBadCookie),
            losslessQualityTier = losslessQualityTier,
            streamingWifiTier = streamingWifiTier,
            streamingCellularTier = streamingCellularTier,
            streamingSaveData = streamingSaveData,
            autoSaveEnabled = autoSaveEnabled,
            autoSaveThreshold = autoSaveThreshold,
            heartDefaultStash = heartDefaultStash,
            heartDefaultSpotify = heartDefaultSpotify,
            heartDefaultYtMusic = heartDefaultYtMusic,
            autoSavedCountLast7Days = autoSavedCount7d,
            mirrorLikesSpotify = mirrorLikesSpotify,
            mirrorLikesYtMusic = mirrorLikesYtMusic,
            arcodConnected = arcodConnected,
            pendingMirrorWarning = local.pendingMirrorWarning,
            youtubeFallbackEnabled = youtubeFallbackEnabled,
            hasCrashReport = local.hasCrashReport,
            databaseBackupState = local.databaseBackupState,
            showImportConfirmation = local.showImportConfirmation,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    // -- Storage actions ------------------------------------------------------

    /** Recomputes storage usage from the configured library filesystem. */
    fun refreshStorageUsage() {
        librarySizeHolder.refresh()
    }

    /**
     * Persists the user's chosen SAF tree URI (or null to revert to
     * internal). Callers MUST have already called
     * `ContentResolver.takePersistableUriPermission(uri, ...)` before
     * invoking this — the persisted URI is useless without the sticky
     * permission and would fail at write time.
     */
    fun setExternalStorageUri(uri: Uri?) {
        viewModelScope.launch {
            storagePreference.setExternalTreeUri(uri)
        }
    }

    /**
     * Counts how many downloaded tracks still live in internal storage —
     * used by the Settings UI to decide whether to surface the "Move
     * library" action.
     */
    suspend fun countMovableTracks(): Int = moveLibraryCoordinator.countMovableTracks()

    /** Starts the library-move job on the coordinator's app-scoped job. */
    fun startMoveLibrary(targetUri: Uri) {
        moveLibraryCoordinator.start(targetUri)
    }

    /** Cancels an in-progress move. State reverts to Idle. */
    fun cancelMoveLibrary() {
        moveLibraryCoordinator.cancel()
    }

    /** Dismisses a terminal Done/Error state, returning to Idle. */
    fun dismissMoveLibrary() {
        moveLibraryCoordinator.dismiss()
    }

    /**
     * Triggers a database export to the chosen URI.
     */
    fun onExportDatabase(uri: Uri) {
        viewModelScope.launch {
            _localState.update { it.copy(databaseBackupState = DatabaseBackupState.Exporting) }
            val result = databaseBackupManager.exportDatabase(uri)
            _localState.update {
                it.copy(
                    databaseBackupState = result.fold(
                        onSuccess = { DatabaseBackupState.Success("Database exported successfully") },
                        onFailure = { t -> DatabaseBackupState.Error(t.message ?: "Export failed") }
                    )
                )
            }
        }
    }

    /**
     * Triggers a database import from the chosen URI. Closes the current
     * database and overwrites it. Success triggers an app process kill
     * to force a clean re-init.
     */
    fun onImportDatabase(uri: Uri) {
        _localState.update { it.copy(showImportConfirmation = true, pendingImportUri = uri) }
    }

    /**
     * Confirms the pending database import.
     */
    fun onConfirmImportDatabase(onExternalPermissionNeeded: (Uri) -> Unit) {
        val uri = _localState.value.pendingImportUri ?: return
        _localState.update { it.copy(showImportConfirmation = false, pendingImportUri = null) }

        viewModelScope.launch {
            _localState.update { it.copy(databaseBackupState = DatabaseBackupState.Importing) }
            val result = databaseBackupManager.importDatabase(uri)

            if (result.isSuccess) {
                // v0.9.15: The import manager peeks at the restored preferences
                // to detect if an external storage folder was configured.
                val restoredTreeUri = result.getOrNull()

                if (restoredTreeUri != null) {
                    // Check if we ALREADY have the permission (maybe it's a restore over same install)
                    val hasPermission = appContext.contentResolver.persistedUriPermissions.any {
                        it.uri == restoredTreeUri && it.isReadPermission
                    }

                    if (hasPermission) {
                        finishImportAndRestart()
                    } else {
                        // We need the user to re-grant permission for the restored folder
                        // BEFORE we restart, so that the startup migrations can see the files.
                        _localState.update { it.copy(databaseBackupState = DatabaseBackupState.Idle) }
                        onExternalPermissionNeeded(restoredTreeUri)
                    }
                } else {
                    finishImportAndRestart()
                }
            } else {
                _localState.update {
                    it.copy(
                        databaseBackupState = DatabaseBackupState.Error(
                            result.exceptionOrNull()?.message ?: "Import failed"
                        )
                    )
                }
            }
        }
    }

    private suspend fun finishImportAndRestart() {
        _localState.update {
            it.copy(databaseBackupState = DatabaseBackupState.Success("Database imported. Restarting..."))
        }
        kotlinx.coroutines.delay(1500)

        // Re-launch the activity and kill the current process to ensure all
        // components (Room, DataStore, Tink) re-initialize with the restored data.
        val intent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            appContext.startActivity(intent)
        }
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /**
     * Cancels the pending database import.
     */
    fun onCancelImportDatabase() {
        _localState.update { it.copy(showImportConfirmation = false, pendingImportUri = null) }
    }

    /** Dismisses the backup Success/Error overlay. */
    fun onDismissDatabaseBackupStatus() {
        _localState.update { it.copy(databaseBackupState = DatabaseBackupState.Idle) }
    }

    // -- Last.fm actions ------------------------------------------------------

    /**
     * Step 1 of the Last.fm web-auth flow: request a one-shot auth token
     * from Last.fm and transition to [LastFmAuthState.AwaitingAuth]. The
     * screen is expected to open the user's browser to
     * `https://www.last.fm/api/auth/?api_key=X&token=Y` so they can
     * approve. Returns the URL to open so the screen can hand it to
     * UriHandler.
     */
    fun onConnectLastFm(onUrlReady: (String) -> Unit) {
        viewModelScope.launch {
            val tokenResult = lastFmApiClient.getAuthToken()
            tokenResult.fold(
                onSuccess = { token ->
                    _localState.update {
                        it.copy(lastFmAuthOverride = LastFmAuthState.AwaitingAuth(token))
                    }
                    val url = "https://www.last.fm/api/auth/?api_key=" +
                        "${lastFmCredentials.apiKey}&token=$token"
                    onUrlReady(url)
                },
                onFailure = { t ->
                    _localState.update {
                        it.copy(
                            lastFmAuthOverride = LastFmAuthState.Error(
                                t.message ?: "Couldn't request Last.fm auth token",
                            ),
                        )
                    }
                },
            )
        }
    }

    /**
     * Step 2: user has approved in their browser, back in Stash they tap
     * "Finish connecting." Exchange the stored token for a session key.
     */
    fun onFinishLastFmAuth() {
        val override = _localState.value.lastFmAuthOverride
        if (override !is LastFmAuthState.AwaitingAuth) return
        viewModelScope.launch {
            val result = lastFmApiClient.getSession(override.token)
            result.fold(
                onSuccess = { (username, sessionKey) ->
                    lastFmSessionPreference.save(LastFmSession(username, sessionKey))
                    // Clear the override — the session flow now drives Connected state.
                    _localState.update { it.copy(lastFmAuthOverride = null) }
                },
                onFailure = { t ->
                    _localState.update {
                        it.copy(
                            lastFmAuthOverride = LastFmAuthState.Error(
                                t.message ?: "Couldn't finish Last.fm connection. " +
                                    "Did you tap Allow on Last.fm's website?",
                            ),
                        )
                    }
                },
            )
        }
    }

    /** Forget the Last.fm session + stop scrobbling. */
    fun onDisconnectLastFm() {
        viewModelScope.launch {
            lastFmSessionPreference.clear()
            _localState.update { it.copy(lastFmAuthOverride = null) }
        }
    }

    /** Dismiss a Last.fm error banner and return to Disconnected. */
    fun onDismissLastFmError() {
        _localState.update { it.copy(lastFmAuthOverride = null) }
    }

    /**
     * Manually drain the pending Last.fm scrobble queue. Triggered from
     * the Settings "Sync scrobbles now" button. Result is surfaced via a
     * one-shot flag on LocalState so the UI can show a snackbar like
     * "Sent 312 scrobbles" — the reactive pending-count Flow keeps the
     * subtitle under the button accurate on its own.
     */
    fun onSyncScrobblesNow() {
        viewModelScope.launch {
            _localState.update { it.copy(isScrobbleDraining = true) }
            val result = runCatching { lastFmScrobbler.drainNow() }.getOrNull()
            _localState.update {
                it.copy(
                    isScrobbleDraining = false,
                    lastScrobbleDrainResult = result,
                )
            }
        }
    }

    /** UI acknowledgement of the drain snackbar. */
    fun onClearScrobbleDrainResult() {
        _localState.update { it.copy(lastScrobbleDrainResult = null) }
    }

    // -- Spotify actions ------------------------------------------------------

    /**
     * Opens the Spotify WebView login flow. The user signs in via Spotify's
     * own login page and the app extracts the sp_dc cookie automatically.
     */
    fun onConnectSpotify() {
        _localState.update {
            it.copy(showSpotifyWebLogin = true)
        }
    }

    /**
     * Fallback: opens the manual sp_dc cookie paste dialog for users who
     * prefer to extract the cookie themselves.
     */
    fun onConnectSpotifyManual() {
        _localState.update {
            it.copy(
                showSpotifyWebLogin = false,
                showSpotifyCookieDialog = true,
                spotifyCookieError = null,
                isSpotifyCookieValidating = false,
            )
        }
    }

    /** Dismisses the WebView login screen. */
    fun onDismissSpotifyWebLogin() {
        _localState.update { it.copy(showSpotifyWebLogin = false) }
    }

    /**
     * Called by the WebView login when an sp_dc cookie is successfully
     * extracted from the Spotify session. Validates it the same way the
     * manual paste flow does.
     */
    fun onSpotifyWebLoginCookieExtracted(spDcCookie: String) {
        _localState.update { it.copy(showSpotifyWebLogin = false) }
        onConnectSpotifyWithCookie(spDcCookie)
    }

    /**
     * Validates the user-provided sp_dc cookie and connects their Spotify account.
     *
     * Calls [TokenManager.connectSpotifyWithCookie] which exchanges the cookie for
     * an access token. On success the dialog is dismissed; on failure an error
     * message is displayed in the dialog.
     *
     * @param cookie The raw sp_dc cookie value pasted by the user.
     */
    fun onConnectSpotifyWithCookie(cookie: String, username: String = "") {
        if (cookie.isBlank()) {
            _localState.update { it.copy(spotifyCookieError = "Cookie cannot be empty") }
            return
        }

        viewModelScope.launch {
            _localState.update {
                it.copy(isSpotifyCookieValidating = true, spotifyCookieError = null)
            }

            val success = tokenManager.connectSpotifyWithCookie(cookie, username)

            if (success) {
                _localState.update {
                    it.copy(
                        showSpotifyCookieDialog = false,
                        spotifyCookieError = null,
                        isSpotifyCookieValidating = false,
                    )
                }
            } else {
                _localState.update {
                    it.copy(
                        spotifyCookieError = "Invalid or expired sp_dc cookie. Please try again.",
                        isSpotifyCookieValidating = false,
                    )
                }
            }
        }
    }

    /**
     * Dismisses the Spotify cookie input dialog without connecting.
     */
    fun onDismissSpotifyCookieDialog() {
        _localState.update {
            it.copy(
                showSpotifyCookieDialog = false,
                spotifyCookieError = null,
                isSpotifyCookieValidating = false,
            )
        }
    }

    /**
     * Disconnects the Spotify account by clearing all stored credentials.
     */
    fun onDisconnectSpotify() {
        viewModelScope.launch {
            musicRepository.cancelPendingDownloadsForSource("SPOTIFY")
            tokenManager.clearAuth(AuthService.SPOTIFY)
        }
    }

    // -- YouTube actions ------------------------------------------------------

    /**
     * Opens the YouTube Music WebView login flow. The user signs in via
     * Google's own page inside the WebView and the app extracts the full
     * cookie string (SAPISID + LOGIN_INFO at minimum) automatically.
     *
     * Phase 1 spike: this is the new primary entry point. The manual
     * cookie paste dialog stays available via [onConnectYouTubeManual]
     * for users who hit the Google "browser not secure" block.
     */
    fun onConnectYouTube() {
        _localState.update {
            it.copy(showYouTubeWebLogin = true)
        }
    }

    /**
     * Fallback: opens the manual cookie paste dialog. Triggered from
     * the WebView's "Paste cookie" toolbar action when the WebView path
     * is blocked or otherwise unwanted.
     */
    fun onConnectYouTubeManual() {
        _localState.update {
            it.copy(
                showYouTubeWebLogin = false,
                showYouTubeCookieDialog = true,
                youTubeCookieError = null,
                isYouTubeCookieValidating = false,
            )
        }
    }

    /** Dismisses the YouTube WebView login screen. */
    fun onDismissYouTubeWebLogin() {
        _localState.update { it.copy(showYouTubeWebLogin = false) }
    }

    /**
     * Called by the WebView login when the YouTube session cookies are
     * successfully harvested. Validates and persists via the same flow
     * the manual paste path uses.
     */
    fun onYouTubeWebLoginCookieExtracted(cookies: String) {
        _localState.update { it.copy(showYouTubeWebLogin = false) }
        onConnectYouTubeWithCookie(cookies)
    }

    /**
     * Validates the user-provided YouTube Music cookie and connects their account.
     *
     * The cookie must contain a SAPISID or __Secure-3PAPISID value which is
     * used for SAPISIDHASH authentication with the InnerTube API.
     *
     * @param cookie The full cookie string from the user's music.youtube.com browser session.
     */
    fun onConnectYouTubeWithCookie(cookie: String) {
        if (cookie.isBlank()) {
            _localState.update { it.copy(youTubeCookieError = "Cookie cannot be empty") }
            return
        }

        // Pre-validate before sending to TokenManager
        val sapiSid = youTubeCookieHelper.extractSapiSid(cookie)
        if (sapiSid == null) {
            _localState.update {
                it.copy(
                    youTubeCookieError = "Missing SAPISID cookie. Make sure you copied the FULL " +
                        "cookie header from music.youtube.com (Network tab > any request > Cookie header).",
                )
            }
            return
        }

        if (!youTubeCookieHelper.hasLoginInfo(cookie)) {
            _localState.update {
                it.copy(
                    youTubeCookieError = "Missing LOGIN_INFO cookie. yt-dlp requires LOGIN_INFO " +
                        "to download. Make sure you're copying the complete Cookie header, not " +
                        "individual cookies. The header should be one long string with many values " +
                        "separated by semicolons.",
                )
            }
            return
        }

        viewModelScope.launch {
            _localState.update {
                it.copy(isYouTubeCookieValidating = true, youTubeCookieError = null)
            }

            val success = tokenManager.connectYouTubeWithCookie(cookie)

            if (success) {
                _localState.update {
                    it.copy(
                        showYouTubeCookieDialog = false,
                        youTubeCookieError = null,
                        isYouTubeCookieValidating = false,
                    )
                }
            } else {
                _localState.update {
                    it.copy(
                        youTubeCookieError = "Failed to save cookie. Please try again.",
                        isYouTubeCookieValidating = false,
                    )
                }
            }
        }
    }

    /**
     * Dismisses the YouTube cookie input dialog without connecting.
     */
    fun onDismissYouTubeCookieDialog() {
        _localState.update {
            it.copy(
                showYouTubeCookieDialog = false,
                youTubeCookieError = null,
                isYouTubeCookieValidating = false,
            )
        }
    }

    /**
     * Disconnects the YouTube account by clearing stored credentials.
     */
    fun onDisconnectYouTube() {
        viewModelScope.launch {
            musicRepository.cancelPendingDownloadsForSource("YOUTUBE")
            tokenManager.clearAuth(AuthService.YOUTUBE_MUSIC)
        }
    }

    /**
     * Dismisses the YouTube error dialog.
     */
    fun onDismissYouTubeError() {
        _localState.update { it.copy(youTubeError = null) }
    }

    // -- Quality --------------------------------------------------------------

    /**
     * Updates the preferred audio quality tier and persists it to DataStore.
     *
     * @param tier The new [QualityTier] to use for future downloads.
     */
    fun onQualityChanged(tier: QualityTier) {
        viewModelScope.launch {
            qualityPreference.setQualityTier(tier)
        }
    }

    /** Persists the user's selected theme mode. Flows into MainActivity via Hilt. */
    fun onThemeChanged(mode: ThemeMode) {
        viewModelScope.launch {
            themePreference.setThemeMode(mode)
        }
    }

    // ── Sleep timer (fork issue ParaliyzedEvo/Stash#26) ─────────────────
    val sleepTimerState = sleepTimerController.state
    fun onSleepTimerMinutes(minutes: Int) = sleepTimerController.startMinutes(minutes)
    fun onSleepTimerEndOfTrack() = sleepTimerController.stopAtEndOfTrack()
    fun onSleepTimerCancel() = sleepTimerController.cancel()

    /** Persists the pure-black (AMOLED) dark preference. */
    fun onAmoledDarkChanged(enabled: Boolean) {
        viewModelScope.launch {
            themePreference.setAmoledDark(enabled)
        }
    }

    /**
     * Persists a new download-network mode AND re-schedules the two
     * workers that depend on it ([StashDiscoveryWorker],
     * [TagEnrichmentWorker]) so the updated `Constraints` take effect
     * immediately. WorkManager snapshots constraints at enqueue time —
     * without the re-schedule, the setting would only apply to future
     * installs, not the current one.
     */
    fun onDownloadNetworkModeChanged(mode: DownloadNetworkMode) {
        viewModelScope.launch {
            downloadNetworkPreference.setMode(mode)
            StashDiscoveryWorker.schedulePeriodic(appContext, mode)
            TagEnrichmentWorker.schedulePeriodic(appContext, mode)
        }
    }

    // -- YouTube History actions ----------------------------------------------

    /** Flip the YT-history opt-in. Settings screen shows the first-enable
     *  dialog; by the time this is called, the user has already confirmed. */
    fun onYouTubeHistoryEnabledChanged(enabled: Boolean) {
        viewModelScope.launch {
            youTubeHistoryPreference.setEnabled(enabled)
        }
    }

    /**
     * v0.9.26 — flip the Stash-Mixes opt-out. Persists the pref AND triggers
     * the orchestrator that cancels/re-schedules the five background workers
     * and flips `is_active` on the built-in recipes + playlists. See
     * [com.stash.core.data.repository.MusicRepository.applyStashMixesEnabled].
     */
    fun onStashMixesEnabledChanged(enabled: Boolean) {
        viewModelScope.launch {
            stashMixPreference.setEnabled(enabled)
            runCatching { musicRepository.applyStashMixesEnabled(enabled) }
                .onFailure { e ->
                    android.util.Log.e("SettingsVM", "applyStashMixesEnabled failed: ${e.message}", e)
                }
        }
    }

    /**
     * Hide/show the Qobuz discovery sections on Home (New Releases, Qobuz
     * Playlists, Top Albums + the genre chips). Pref-only — Home's
     * discovery flow gates its own catalog fetches off this.
     */
    fun onQobuzDiscoveryEnabledChanged(enabled: Boolean) {
        viewModelScope.launch { homeDiscoveryPreference.setEnabled(enabled) }
    }

    /** Ambient animated background on Now Playing (Settings > Appearance). */
    fun onAmbientAnimationEnabledChanged(enabled: Boolean) {
        viewModelScope.launch { nowPlayingPreference.setAmbientAnimationEnabled(enabled) }
    }

    /** Clear the kill-switch after PROTOCOL_BROKEN. Exposed to the Settings
     *  UI's "Retry YouTube sync" button on the red health badge. */
    fun onRetryYouTubeHistory() {
        viewModelScope.launch {
            youTubeScrobblerState.setDisabledReason(null)
            youTubeScrobblerState.resetConsecutiveFailures()
        }
    }

    // -- Lossless mode toggle ------------------------------------------------

    /**
     * Master switch for the lossless-source pipeline. When true, the
     * download path tries the squid.wtf-proxied Qobuz registry before
     * falling back to yt-dlp; when false (default), yt-dlp runs as it
     * always has and the lossless code is dead at runtime.
     */
    fun onLosslessEnabledChanged(enabled: Boolean) {
        viewModelScope.launch {
            losslessPrefs.setEnabled(enabled)
        }
    }

    /**
     * Persist the user's lossless quality tier. Forward-only: existing
     * downloaded tracks are unchanged. New downloads go out at the
     * selected tier on the next sync.
     */
    fun onLosslessQualityTierChanged(tier: LosslessQualityTier) {
        viewModelScope.launch {
            losslessPrefs.setQualityTier(tier)
        }
    }

    /**
     * v0.9.17: opt-in YouTube fallback for the FLAC-only download path.
     * When false (default), tracks with no lossless match sit in
     * WAITING_FOR_LOSSLESS and the LosslessRetryWorker re-resolves them
     * later. When true, yt-dlp takes over at the user's
     * [QualityTier]; the pref's setter also requeues any deferred rows
     * so the existing backlog drains immediately.
     */
    fun onYoutubeFallbackChanged(value: Boolean) {
        viewModelScope.launch {
            losslessPrefs.setYoutubeFallbackEnabled(value)
        }
    }

    // -- ARCOD connect -------------------------------------------------------

    /**
     * Live "is ARCOD connected" flag, derived from the presence of a
     * non-blank access token in [ArcodCredentialStore]. Drives the
     * "Connect ARCOD" / "ARCOD — connected" row label. Also surfaced into
     * [SettingsUiState.arcodConnected] via the main `combine`; exposed
     * standalone for callers that only need this one bit.
     */
    val arcodConnected: StateFlow<Boolean> =
        arcodCredentialStore.accessToken
            .map { !it.isNullOrBlank() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = false,
            )

    /**
     * Persists the Supabase session harvested from the ARCOD connect
     * WebView's localStorage. The ARCOD source/interceptor read it back
     * from [ArcodCredentialStore] reactively, so no further wiring is needed.
     */
    fun onArcodConnected(accessToken: String, refreshToken: String, expiresAtMs: Long) {
        viewModelScope.launch {
            arcodCredentialStore.save(accessToken, refreshToken, expiresAtMs)
        }
    }

    /**
     * Persists the user-pasted `captcha_verified_at` cookie value
     * for qobuz.squid.wtf. Empty / blank input clears the stored value.
     * Whitespace is trimmed inside [LosslessSourcePreferences.setCaptchaCookieValue].
     */
    fun onSquidWtfCaptchaCookieChanged(value: String) {
        viewModelScope.launch {
            losslessPrefs.setCaptchaCookieValue(value)
        }
    }

    // -- qbdlx (direct-Qobuz lossless, 5th source) ---------------------------

    /**
     * Per-source enable toggle for qbdlx. Gates BOTH download and streaming
     * (the source reads `qbdlxEnabledNow()` in both `isEnabled()` and
     * `isEnabledForStreaming()`). Default true.
     */
    val qbdlxEnabled: StateFlow<Boolean> =
        losslessPrefs.qbdlxEnabled.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = true,
        )

    /** Persist the qbdlx enable flip. */
    fun onQbdlxEnabledChange(enabled: Boolean) {
        viewModelScope.launch { losslessPrefs.setQbdlxEnabled(enabled) }
    }

    /** Store (or clear, on blank) the user-pasted qbdlx token, then re-check expiry. */
    fun onQbdlxTokenPaste(token: String) {
        viewModelScope.launch {
            qbdlxCredentialStore.setPastedToken(token.ifBlank { null })
            _qbdlxExpired.value = qbdlxCredentialStore.allDead()
            _qbdlxTokenChoices.value = qbdlxCredentialStore.poolForPicker()
        }
    }

    private fun refreshQbdlxExpired() {
        viewModelScope.launch { _qbdlxExpired.value = qbdlxCredentialStore.allDead() }
    }

    private fun refreshQbdlxTokens() {
        viewModelScope.launch {
            _qbdlxTokenChoices.value = qbdlxCredentialStore.poolForPicker()
            _qbdlxPinnedToken.value = qbdlxCredentialStore.pinnedToken()
        }
    }

    /** Pin a specific pool token (or null = Auto), then refresh the picker state. */
    fun onQbdlxTokenPinned(token: String?) {
        viewModelScope.launch {
            qbdlxCredentialStore.setPinnedToken(token)
            _qbdlxPinnedToken.value = qbdlxCredentialStore.pinnedToken()
            _qbdlxTokenChoices.value = qbdlxCredentialStore.poolForPicker()
        }
    }

    // -- Streaming quality (per-network tiers + Save Data) -------------------

    /** Persist the lossless tier requested when streaming on Wi-Fi. */
    fun onStreamingWifiTierChanged(tier: LosslessQualityTier) =
        viewModelScope.launch { streamingQualityPrefs.setWifiTier(tier) }

    /** Persist the lossless tier requested when streaming on cellular. */
    fun onStreamingCellularTierChanged(tier: LosslessQualityTier) =
        viewModelScope.launch { streamingQualityPrefs.setCellularTier(tier) }

    /** Persist the master Save-Data streaming override. */
    fun onStreamingSaveDataChanged(value: Boolean) =
        viewModelScope.launch { streamingQualityPrefs.setSaveData(value) }

    /**
     * Clear the rate-limiter's circuit breaker for the squid.wtf
     * source. Useful when the breaker tripped on a transient outage
     * and the user knows the source is back up — skips the 30-min
     * organic timeout. No-op if the breaker isn't currently tripped.
     */
    fun onResetLosslessRateLimiter() {
        viewModelScope.launch {
            losslessRateLimiter.reset(QobuzSource.SOURCE_ID)
        }
    }

    // -- Library & Likes (v0.9.13) ------------------------------------------

    /**
     * Master toggle for the auto-save scrobbler. When true,
     * AutoSaveScrobbler observes plays and pushes tracks crossing the
     * day-distinct-plays threshold into Spotify Liked Songs.
     */
    fun onAutoSaveEnabledChanged(value: Boolean) {
        viewModelScope.launch { likePreferences.setAutoSaveEnabled(value) }
    }

    /**
     * Persist the day-distinct-plays threshold (1..10). Values outside
     * the range are coerced inside [LikePreferences.setAutoSaveThreshold].
     */
    fun onAutoSaveThresholdChanged(value: Int) {
        viewModelScope.launch { likePreferences.setAutoSaveThreshold(value) }
    }

    /** Whether tapping the heart adds to the local Stash Liked Songs playlist. */
    fun onHeartDefaultStashChanged(value: Boolean) {
        viewModelScope.launch { likePreferences.setHeartDefaultStash(value) }
    }

    /** Whether tapping the heart pushes to Spotify Liked Songs. */
    fun onHeartDefaultSpotifyChanged(value: Boolean) {
        viewModelScope.launch { likePreferences.setHeartDefaultSpotify(value) }
    }

    /** Whether tapping the heart pushes to YT Music Liked Music. */
    fun onHeartDefaultYtMusicChanged(value: Boolean) {
        viewModelScope.launch { likePreferences.setHeartDefaultYtMusic(value) }
    }

    /**
     * v0.9.52 like-mirroring. Enabling a mirror requires the explicit
     * "I understand" ack — the pref is only written on confirm (see
     * [onMirrorWarningConfirmed]); disabling is immediate, no dialog.
     */
    fun onMirrorToggleRequested(destination: Destination, enable: Boolean) {
        if (!enable) {
            viewModelScope.launch { setMirrorPref(destination, false) }
            return
        }
        _localState.update { it.copy(pendingMirrorWarning = destination) }
    }

    fun onMirrorWarningConfirmed() {
        val destination = _localState.value.pendingMirrorWarning ?: return
        _localState.update { it.copy(pendingMirrorWarning = null) }
        viewModelScope.launch { setMirrorPref(destination, true) }
    }

    fun onMirrorWarningDismissed() {
        _localState.update { it.copy(pendingMirrorWarning = null) }
    }

    private suspend fun setMirrorPref(destination: Destination, value: Boolean) {
        when (destination) {
            Destination.SPOTIFY -> likePreferences.setMirrorLikesSpotify(value)
            Destination.YT_MUSIC -> likePreferences.setMirrorLikesYtMusic(value)
            Destination.STASH -> Unit // local likes are always on; not a mirror target
        }
    }

    // -- Internal state -------------------------------------------------------

    /**
     * Local (non-persisted) state that is combined with reactive flows from
     * [TokenManager], [MusicRepository], and [QualityPreference] to produce
     * [SettingsUiState].
     */
    private data class LocalState(
        val showSpotifyWebLogin: Boolean = false,
        val showYouTubeWebLogin: Boolean = false,
        val showYouTubeCookieDialog: Boolean = false,
        val showSpotifyCookieDialog: Boolean = false,
        val spotifyCookieError: String? = null,
        val isSpotifyCookieValidating: Boolean = false,
        val youTubeCookieError: String? = null,
        val isYouTubeCookieValidating: Boolean = false,
        val youTubeError: String? = null,
        /**
         * Transient Last.fm auth state used to override the session-flow-
         * derived default. Non-null while we're mid-flow (AwaitingAuth
         * after fetching a token) or showing an error. Cleared when the
         * flow completes or the user dismisses the error.
         */
        val lastFmAuthOverride: LastFmAuthState? = null,
        /** True while a manual scrobble-drain is in-flight. */
        val isScrobbleDraining: Boolean = false,
        /**
         * Result of the most recent manual drain. Consumed by the UI
         * (snackbar) and then cleared via [onClearScrobbleDrainResult].
         */
        val lastScrobbleDrainResult: LastFmScrobbler.DrainResult? = null,
        /** Current status of the database backup operation. */
        val databaseBackupState: DatabaseBackupState = DatabaseBackupState.Idle,
        /** Whether the import confirmation dialog is showing. */
        val showImportConfirmation: Boolean = false,
        /** The URI of the database file chosen for import, while waiting for confirmation. */
        val pendingImportUri: Uri? = null,
        /**
         * Last-known liveness of `cacheDir/crashes/`. Refreshed by
         * [refreshDiagnostics] on Settings entry and again after the user
         * taps share. Used by the Settings UI to enable/disable the
         * "Share latest crash report" button + render its subtitle.
         */
        val hasCrashReport: Boolean = false,
        /**
         * v0.9.52: non-null while the like-mirroring "I understand" warning
         * dialog is showing for that destination. The pref is only written
         * on confirm, so dismissing leaves mirroring off.
         */
        val pendingMirrorWarning: Destination? = null,
    )

    // -- Diagnostics ----------------------------------------------------------

    /**
     * Re-list crash files and update [hasCrashReport]. Called on init and
     * after share to keep the button's enabled state in sync with disk.
     */
    fun refreshDiagnostics() {
        val present = crashFileStore.latestCrashFile() != null
        _localState.update { it.copy(hasCrashReport = present) }
    }

    /**
     * Returns the newest crash file paired with a content:// URI suitable
     * for an ACTION_SEND share Intent — or null if no file exists. The
     * screen wraps the result in the actual Intent and calls
     * `Context.startActivity`. Refreshes [hasCrashReport] as a side effect
     * so a missing file flips the button's state immediately.
     */
    fun latestCrashShareTarget(): CrashShareTarget? {
        val file = crashFileStore.latestCrashFile()
        if (file == null) {
            _localState.update { it.copy(hasCrashReport = false) }
            return null
        }
        return CrashShareTarget(
            file = file,
            contentUri = crashFileStore.shareUriFor(file),
        )
    }

    /** Bundle returned by [latestCrashShareTarget]; the screen builds the Intent. */
    data class CrashShareTarget(
        val file: java.io.File,
        val contentUri: android.net.Uri,
    )
}
