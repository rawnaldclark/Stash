package com.stash.feature.settings

import com.stash.core.auth.model.AuthState
import com.stash.core.data.youtube.YouTubeScrobblerHealth
import com.stash.core.model.DownloadNetworkMode
import com.stash.core.model.QualityTier
import com.stash.core.model.ThemeMode
import com.stash.data.download.lossless.LosslessQualityTier
import com.stash.feature.settings.components.SquidCaptchaStatus

/**
 * Immutable UI state for the Settings screen.
 *
 * @property spotifyAuthState Current Spotify authentication lifecycle state.
 * @property youTubeAuthState Current YouTube Music authentication lifecycle state.
 * @property audioQuality Selected download / streaming quality tier.
 * @property totalStorageBytes Total bytes occupied by the music library on disk (filesystem walk via [com.stash.data.download.files.LibrarySizeHolder]).
 * @property totalTracks Number of tracks currently stored in the library.
 * @property showYouTubeCookieDialog Whether the YouTube cookie input dialog should be visible.
 * @property showSpotifyCookieDialog Whether the Spotify sp_dc cookie input dialog should be visible.
 * @property spotifyCookieError Error message to display in the Spotify cookie dialog, or null if none.
 * @property isSpotifyCookieValidating Whether the sp_dc cookie is currently being validated.
 * @property youTubeCookieError Error message to display in the YouTube cookie dialog, or null if none.
 * @property isYouTubeCookieValidating Whether the YouTube cookie is currently being validated.
 */
data class SettingsUiState(
    val spotifyAuthState: AuthState = AuthState.NotConnected,
    val youTubeAuthState: AuthState = AuthState.NotConnected,
    val audioQuality: QualityTier = QualityTier.MAX,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Pure-black (AMOLED) backgrounds whenever the effective theme is dark. */
    val amoledDark: Boolean = false,
    /**
     * Network + power conditions under which Stash runs background
     * downloads (Stash Discover, tag enrichment). Changing this in
     * Settings re-schedules both workers with new WorkManager constraints.
     */
    val downloadNetworkMode: DownloadNetworkMode = DownloadNetworkMode.WIFI_AND_CHARGING,
    val ytHistoryEnabled: Boolean = false,
    /**
     * v0.9.26 — user opt-out for Stash Mixes (Daily Discover / Deep Cuts /
     * First Listen). Default true preserves current behavior; flipping off
     * cancels the discovery / refresh / enrichment workers and hides the
     * built-in mix playlists from Home / Library. See issues #56, #57.
     */
    val stashMixesEnabled: Boolean = true,
    val ytHistoryHealth: YouTubeScrobblerHealth = YouTubeScrobblerHealth.DISABLED,
    val ytPendingCount: Int = 0,
    /**
     * Master switch for the lossless-source download path
     * (squid.wtf-proxied Qobuz). On by default as of v0.9.8 — every
     * track is routed through the registry first and falls back to
     * yt-dlp only when no source has a confident lossless match (or
     * the captcha is unverified). Files end up 5–10× larger than Opus.
     *
     * Existing v0.9.7 users who explicitly toggled this off keep their
     * saved `false`; v0.9.7 users who never opened the toggle pick up
     * the new default (functionally identical to v0.9.7 behaviour
     * because captcha-unverified silently falls back to yt-dlp/MP3).
     */
    val losslessEnabled: Boolean = true,
    /**
     * Selected lossless quality tier (CD / Hi-Res / Max). Defaults to HI_RES
     * which matches [LosslessSourcePreferences]'s stored default. The actual
     * first-emission from the DataStore flow replaces this within a few ms on
     * cold start; the default only guards the brief pre-emission window.
     */
    val losslessQualityTier: LosslessQualityTier = LosslessQualityTier.HI_RES,
    /**
     * Streaming lossless tier requested while on Wi-Fi. Distinct from the
     * download tier ([losslessQualityTier]); backed by
     * [com.stash.data.download.prefs.StreamingQualityPreferences.wifiTier].
     * Defaults to HI_RES (full quality on unmetered transport).
     */
    val streamingWifiTier: LosslessQualityTier = LosslessQualityTier.HI_RES,
    /**
     * Streaming lossless tier requested while on cellular. Defaults to CD —
     * the data-saving floor — so metered streaming stays light unless the
     * user opts up. Backed by
     * [com.stash.data.download.prefs.StreamingQualityPreferences.cellularTier].
     */
    val streamingCellularTier: LosslessQualityTier = LosslessQualityTier.CD,
    /**
     * Master "Save Data" override for streaming. When true, callers force
     * CD on every network regardless of the per-transport tiers. Backed by
     * [com.stash.data.download.prefs.StreamingQualityPreferences.saveData].
     */
    val streamingSaveData: Boolean = false,
    /**
     * Manually-pasted `captcha_verified_at` cookie value from
     * `qobuz.squid.wtf`. Bridges the captcha gate when the user
     * prefers manual paste over the in-app WebView solver — they
     * solve ALTCHA in their browser, copy the cookie value, and
     * paste here. Empty string == not configured.
     */
    val squidWtfCaptchaCookie: String = "",
    /**
     * Tri-state describing the squid.wtf captcha cookie's liveness,
     * derived from the cookie value plus `QobuzSource.lastKnownBadCookie`.
     * Drives the routing-status row's label ("active" / "expired" /
     * "optional") and whether the "solve captcha →" link is shown.
     *
     * Why a tri-state instead of `cookie.isNotEmpty()`: the cookie has
     * a ~30-min sliding-window expiry server-side. Once expired,
     * QobuzSource records the offending value and `isEnabled()`
     * returns false until the user re-pastes — but pre-fix the row
     * still rendered as "active" with no action link, hiding the
     * solver from the user. See `squidCaptchaStatus` mapping fn.
     */
    val squidCaptchaStatus: SquidCaptchaStatus = SquidCaptchaStatus.NotConfigured,
    /**
     * True when an ARCOD (arcod.xyz) Supabase session is stored — i.e. the
     * user has connected ARCOD as a third independent lossless source.
     * Drives the "Connect ARCOD" row's label.
     */
    val arcodConnected: Boolean = false,
    val totalStorageBytes: Long = 0,
    val totalTracks: Int = 0,
    val showYouTubeCookieDialog: Boolean = false,
    val showYouTubeWebLogin: Boolean = false,
    val showSpotifyWebLogin: Boolean = false,
    val showSpotifyCookieDialog: Boolean = false,
    val spotifyCookieError: String? = null,
    val isSpotifyCookieValidating: Boolean = false,
    val youTubeCookieError: String? = null,
    val isYouTubeCookieValidating: Boolean = false,
    val youTubeError: String? = null,
    /**
     * User-selected SAF tree URI for external storage (SD card / USB-OTG /
     * any folder). Null = using the app's internal music directory. When
     * non-null, new downloads are written there via ContentResolver.
     */
    val externalTreeUri: android.net.Uri? = null,
    /**
     * Live state of the one-shot "Move existing library" migration. The
     * Settings UI watches this to render progress, a Done banner, or an
     * Error message when the user migrates their internal library to an
     * external SAF target.
     */
    val moveLibraryState: com.stash.data.download.files.MoveLibraryState =
        com.stash.data.download.files.MoveLibraryState.Idle,
    /** Last.fm connection state — drives the Settings → Last.fm section. */
    val lastFmState: LastFmAuthState = LastFmAuthState.NotConfigured,
    /** True while a manual scrobble-drain is in-flight. */
    val isScrobbleDraining: Boolean = false,
    /**
     * One-shot result of the most recent manual scrobble drain. Non-null
     * triggers a snackbar; the UI clears it via onClearScrobbleDrainResult.
     */
    val scrobbleDrainResult: com.stash.core.data.lastfm.LastFmScrobbler.DrainResult? = null,
    val autoSaveEnabled: Boolean = false,
    val autoSaveThreshold: Int = 3,
    val heartDefaultStash: Boolean = true,
    val heartDefaultSpotify: Boolean = true,
    val heartDefaultYtMusic: Boolean = false,
    /** v0.9.13: count of tracks auto-saved in the last 7 days, for the diagnostics line. */
    val autoSavedCountLast7Days: Int = 0,
    /** v0.9.52 like-mirroring: per-service opt-in (default off), gated behind the warning dialog. */
    val mirrorLikesSpotify: Boolean = false,
    val mirrorLikesYtMusic: Boolean = false,
    /** Non-null while the "I understand" warning dialog is showing for that destination. */
    val pendingMirrorWarning: com.stash.core.data.social.Destination? = null,
    /**
     * v0.9.17: when lossless is on, controls whether yt-dlp is allowed
     * to take over a track that no lossless source could resolve. When
     * false (default), the track sits in WAITING_FOR_LOSSLESS until a
     * source resolves it; when true, yt-dlp picks it up at
     * [audioQuality]. Only meaningful while [losslessEnabled] is true.
     */
    val youtubeFallbackEnabled: Boolean = false,
    /**
     * True when at least one crash report exists in `cacheDir/crashes/`.
     * Drives the enabled state of the "Share latest crash report" button
     * in the Diagnostics section. Refreshed on every Settings entry by the
     * ViewModel (see `refreshDiagnostics`); also re-checked after the user
     * taps share, in case the file was deleted between init and tap.
     */
    val hasCrashReport: Boolean = false,
    /** Current status of the database export/import operation. */
    val databaseBackupState: DatabaseBackupState = DatabaseBackupState.Idle,
    /** Whether the import confirmation dialog is showing. */
    val showImportConfirmation: Boolean = false,
)

/**
 * State of a database export or import operation in Settings.
 */
sealed interface DatabaseBackupState {
    data object Idle : DatabaseBackupState
    data object Exporting : DatabaseBackupState
    data object Importing : DatabaseBackupState
    data class Success(val message: String) : DatabaseBackupState
    data class Error(val message: String) : DatabaseBackupState
}

/**
 * Connection state for the Last.fm scrobbler integration.
 *
 * - [NotConfigured]: the APK was built without a Last.fm API key / secret.
 *   UI shows a disabled card explaining the developer setup step.
 * - [Disconnected]: credentials present, user hasn't auth'd yet.
 * - [AwaitingAuth]: we requested an auth token and opened the user's
 *   browser; waiting for the user to tap "Finish connecting" after
 *   approving on Last.fm's site.
 * - [Connected]: session key stored; scrobbler is live.
 * - [Error]: something went wrong. Dismissable back to [Disconnected].
 */
sealed interface LastFmAuthState {
    data object NotConfigured : LastFmAuthState
    data object Disconnected : LastFmAuthState
    data class AwaitingAuth(val token: String) : LastFmAuthState
    data class Connected(val username: String, val pendingScrobbles: Int) : LastFmAuthState
    data class Error(val message: String) : LastFmAuthState
}
