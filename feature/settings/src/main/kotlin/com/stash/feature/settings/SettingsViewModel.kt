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
 * Equalizer controls update [_localState] immediately for responsive UI, and
 * will persist via EqualizerStore / apply via EqualizerManager once those
 * components are available (currently being built by another agent).
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
    private val equalizerManager: com.stash.core.media.equalizer.EqualizerManager,
    private val equalizerStore: com.stash.core.media.equalizer.EqualizerStore,
    private val lastFmApiClient: LastFmApiClient,
    private val lastFmSessionPreference: LastFmSessionPreference,
    private val lastFmCredentials: LastFmCredentials,
    private val listeningEventDao: ListeningEventDao,
    private val lastFmScrobbler: LastFmScrobbler,
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
            eqEnabled = local.eqEnabled,
            eqPreset = local.eqPreset,
            eqBandGains = local.eqBandGains,
            eqBassBoost = local.eqBassBoost,
            eqVirtualizer = local.eqVirtualizer,
            externalTreeUri = externalTree,
            moveLibraryState = moveState,
            lastFmState = lastFmState,
            isScrobbleDraining = local.isScrobbleDraining,
            scrobbleDrainResult = local.lastScrobbleDrainResult,
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

    // -- Equalizer actions ----------------------------------------------------

    /**
     * Enables or disables the equalizer effect chain.
     *
     * When the EqualizerManager is available this will also toggle the
     * underlying Android [android.media.audiofx.Equalizer] instance.
     */
    fun setEqEnabled(enabled: Boolean) {
        _localState.update { it.copy(eqEnabled = enabled) }
        equalizerManager.setEnabled(enabled)
    }

    /**
     * Selects an equalizer preset and applies its band gains.
     *
     * @param preset The preset name (e.g. "Rock", "Jazz", "Flat").
     */
    fun setEqPreset(preset: String) {
        _localState.update { it.copy(eqPreset = preset) }
        val eqPreset = com.stash.core.media.equalizer.EqPreset.fromName(preset)
        equalizerManager.applyPreset(eqPreset)
    }

    /**
     * Updates the gain for a single equalizer band.
     *
     * Automatically switches the preset to "Custom" since the user is
     * manually adjusting values.
     *
     * @param band           Zero-based band index (0..4 for a 5-band EQ).
     * @param normalizedGain Gain in 0..1 range where 0.5 is flat.
     */
    fun setEqBandGain(band: Int, normalizedGain: Float) {
        _localState.update { state ->
            val updatedGains = state.eqBandGains.toMutableList().apply {
                if (band in indices) this[band] = normalizedGain.coerceIn(0f, 1f)
            }
            state.copy(
                eqBandGains = updatedGains,
                eqPreset = "Custom",
            )
        }
        // Convert normalized 0..1 to millibels (-1200..+1200)
        val gainMb = ((normalizedGain - 0.5f) * 2400).toInt()
        equalizerManager.setBandGain(band, gainMb)
    }

    /**
     * Updates the bass boost strength.
     *
     * @param normalized Strength in 0..1 range (maps to 0..1000 internally).
     */
    fun setBassBoost(normalized: Float) {
        _localState.update { it.copy(eqBassBoost = normalized.coerceIn(0f, 1f)) }
        equalizerManager.setBassBoost((normalized * 1000).toInt())
    }

    /**
     * Updates the virtualizer / surround strength.
     *
     * @param normalized Strength in 0..1 range (maps to 0..1000 internally).
     */
    fun setVirtualizer(normalized: Float) {
        _localState.update { it.copy(eqVirtualizer = normalized.coerceIn(0f, 1f)) }
        equalizerManager.setVirtualizer((normalized * 1000).toInt())
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
        // Equalizer state -- stored locally until EqualizerStore is available
        val eqEnabled: Boolean = false,
        val eqPreset: String = "Flat",
        val eqBandGains: List<Float> = listOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f),
        val eqBassBoost: Float = 0f,
        val eqVirtualizer: Float = 0f,
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
