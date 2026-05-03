package com.stash.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthService
import com.stash.core.auth.model.AuthState
import com.stash.core.auth.youtube.YouTubeCookieHelper
import android.content.Context
import android.net.Uri
import com.stash.core.data.prefs.DownloadNetworkPreference
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
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.data.download.files.MoveLibraryCoordinator
import com.stash.data.download.files.MoveLibraryState
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.qobuz.QobuzSource
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.QualityTier
import com.stash.core.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    private val qualityPreference: QualityPreference,
    private val themePreference: ThemePreference,
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
    private val youTubeHistoryScrobbler: YouTubeHistoryScrobbler,
    private val youTubeScrobblerState: YouTubeScrobblerState,
    private val losslessPrefs: LosslessSourcePreferences,
    private val losslessRateLimiter: AggregatorRateLimiter,
) : ViewModel() {

    /** Internal mutable UI state that is combined with token-manager flows. */
    private val _localState = MutableStateFlow(LocalState())

    // Phase 8: `blockedCount` + `onRunYtLibraryBackfill` relocated to
    // SyncViewModel — the Blocked Songs + Fix-wrong-version rows moved
    // out of Settings into the Sync tab's Library section.

    /**
     * The main UI state, combining reactive auth states from [TokenManager],
     * the persisted quality tier, and local UI state.
     */
    val uiState: StateFlow<SettingsUiState> = combine(
        tokenManager.spotifyAuthState,
        tokenManager.youTubeAuthState,
        musicRepository.getTrackCount(),
        musicRepository.getTotalStorageBytes(),
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
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val spotifyAuth = values[0] as AuthState
        val youTubeAuth = values[1] as AuthState
        val trackCount = values[2] as Int
        val storageBytes = values[3] as Long
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
            downloadNetworkMode = downloadNetworkMode,
            totalStorageBytes = storageBytes,
            totalTracks = trackCount,
            showSpotifyWebLogin = local.showSpotifyWebLogin,
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
            ytHistoryHealth = ytHistoryHealth,
            ytPendingCount = ytPendingCount,
            losslessEnabled = losslessEnabled,
            squidWtfCaptchaCookie = squidWtfCaptchaCookie,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    // -- Storage actions ------------------------------------------------------

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
     * Opens the YouTube Music cookie input dialog.
     */
    fun onConnectYouTube() {
        _localState.update {
            it.copy(
                showYouTubeCookieDialog = true,
                youTubeCookieError = null,
                isYouTubeCookieValidating = false,
            )
        }
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
     * Persists the user-pasted `captcha_verified_at` cookie value
     * for qobuz.squid.wtf. Empty / blank input clears the stored value.
     * Whitespace is trimmed inside [LosslessSourcePreferences.setCaptchaCookieValue].
     */
    fun onSquidWtfCaptchaCookieChanged(value: String) {
        viewModelScope.launch {
            losslessPrefs.setCaptchaCookieValue(value)
        }
    }

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

    // -- Internal state -------------------------------------------------------

    /**
     * Local (non-persisted) state that is combined with reactive flows from
     * [TokenManager], [MusicRepository], and [QualityPreference] to produce
     * [SettingsUiState].
     */
    private data class LocalState(
        val showSpotifyWebLogin: Boolean = false,
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
    )
}
