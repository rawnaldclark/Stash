package com.stash.feature.sync

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.sync.AuthExpiryState
import com.stash.core.data.sync.SyncPhase
import com.stash.core.data.sync.SyncPreferences
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.model.MusicSource
import com.stash.core.model.SyncMode
import com.stash.core.data.sync.DayOfWeekSet
import com.stash.core.data.sync.SyncScheduler
import com.stash.core.data.sync.SyncStateManager
import com.stash.core.data.sync.toDisplayStatus
import com.stash.core.data.sync.unreadPlaylistsIn
import com.stash.core.model.SyncDisplayStatus
import com.stash.data.download.files.LibrarySizeHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Presentation-layer model for a single sync history row.
 *
 * Decoupled from the Room entity so the UI has no transitive Room dependency.
 */
data class SyncHistoryInfo(
    val id: Long,
    val startedAt: Long,
    val completedAt: Long? = null,
    val status: String,
    val tracksDownloaded: Int = 0,
    val tracksFailed: Int = 0,
    val newTracksFound: Int = 0,
    val playlistsChecked: Int = 0,
    val bytesDownloaded: Long = 0,
    val trigger: com.stash.core.model.SyncTrigger = com.stash.core.model.SyncTrigger.MANUAL,
    /** Online (true) / Offline (false) / unknown-legacy (null) at the time of this sync. */
    val streamingMode: Boolean? = null,
    val errorMessage: String? = null,
    val diagnostics: String? = null,
    /**
     * Richer display summary derived from the raw sync record. Prefer this
     * over [status] for rendering — it distinguishes partial success,
     * interruption (process kill), and genuine failure.
     */
    val displayStatus: SyncDisplayStatus = SyncDisplayStatus.Idle,
) {
    /**
     * The playlists this run listed but couldn't read, by name. Decoded once per
     * record from [diagnostics], not on every recomposition of the History row
     * (its screen recomposes with each progress tick of a running sync).
     */
    val unreadPlaylists: com.stash.core.data.sync.UnreadPlaylists = unreadPlaylistsIn(diagnostics)
}

/**
 * Full UI state for the Sync screen.
 */
/**
 * Lightweight model for a Spotify playlist's sync preference toggle.
 */
data class SpotifySyncPlaylist(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val type: com.stash.core.model.PlaylistType,
    val syncEnabled: Boolean,
    val artUrl: String? = null,
    val hideFromHome: Boolean = false,
    val pinnedToHomeAt: Long? = null,
    /** The playlist's Save switch (#474's keep_offline): kept on the phone in either mode. */
    val keepOffline: Boolean = false,
    /** Taken out of the library on its account's page: listed there only, so it can come back. */
    val userHidden: Boolean = false,
    /** When the playlist first arrived (epoch ms): the Sync tab's covers lead with the newest. */
    val dateAdded: Long = 0L,
    /** A mix that can stack ([com.stash.core.data.sync.MixStacking]), and whether it does. */
    val stackable: Boolean = false,
    val stackSongs: Boolean = false,
    /** A saved album ([com.stash.core.data.album.SavedAlbums]): its account page lists it under Albums, never Playlists. */
    val album: Boolean = false,
    /**
     * Which Spotify account it is filed under (`playlists.spotify_account`): null for the main
     * (connected) account, an other account's tag ([SpotifyOtherAccount.tag]) for that one's. Its
     * card and page on the Sync tab are picked by it.
     */
    val spotifyAccount: String? = null,
)

/**
 * One of the other Spotify accounts (plan 2026-11-04 §3.7): its own card ("Spotify · Sam") and
 * page on the Sync tab, beside the main account's.
 *
 * @property tag What its rows carry in `playlists.spotify_account` (its normalised Spotify user
 *   id). Used to pick its rows, never shown.
 * @property syncAlbums Its own saved-albums switch, shown on its page's Albums tab.
 * @property syncMixes Its own mixes switch, shown on its page's Mixes tab.
 */
data class SpotifyOtherAccount(
    val id: Long,
    val tag: String,
    val name: String,
    val syncAlbums: Boolean,
    val syncMixes: Boolean,
)

/**
 * Lightweight model for a YouTube Music playlist's sync preference toggle.
 * Mirrors [SpotifySyncPlaylist] so the UI can use a shared toggle row
 * composable; kept as a separate type so future YouTube-only fields don't
 * pollute the Spotify struct.
 */
data class YouTubeSyncPlaylist(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val type: com.stash.core.model.PlaylistType,
    val syncEnabled: Boolean,
    val artUrl: String? = null,
    val hideFromHome: Boolean = false,
    val pinnedToHomeAt: Long? = null,
    /** The playlist's Save switch (#474's keep_offline): kept on the phone in either mode. */
    val keepOffline: Boolean = false,
    /** Taken out of the library on its account's page: listed there only, so it can come back. */
    val userHidden: Boolean = false,
    /** When the playlist first arrived (epoch ms): the Sync tab's covers lead with the newest. */
    val dateAdded: Long = 0L,
    /** A mix that can stack ([com.stash.core.data.sync.MixStacking]), and whether it does. */
    val stackable: Boolean = false,
    val stackSongs: Boolean = false,
    /** A saved album ([com.stash.core.data.album.SavedAlbums]): its account page lists it under Albums, never Playlists. */
    val album: Boolean = false,
)

data class SyncUiState(
    val syncPhase: SyncPhase = SyncPhase.Idle,
    val overallProgress: Float = 0f,
    val syncPreferences: SyncPreferences = SyncPreferences(),
    val spotifyConnected: Boolean = false,
    val youTubeConnected: Boolean = false,
    val soundCloudConnected: Boolean = false,
    val recentSyncs: List<SyncHistoryInfo> = emptyList(),
    val isSyncing: Boolean = false,
    /** Spotify playlists available for sync preference toggles: every Spotify account's ([SpotifySyncPlaylist.spotifyAccount]). */
    val spotifyPlaylists: List<SpotifySyncPlaylist> = emptyList(),
    /** The other Spotify accounts, in the order they were added; each has its own card and page. */
    val spotifyOtherAccounts: List<SpotifyOtherAccount> = emptyList(),
    /** True once [spotifyOtherAccounts] has been read: an other account's page waits for it. */
    val spotifyOtherAccountsLoaded: Boolean = false,
    /** YouTube Music playlists available for sync preference toggles. */
    val youTubePlaylists: List<YouTubeSyncPlaylist> = emptyList(),
    val soundCloudPlaylists: List<YouTubeSyncPlaylist> = emptyList(),
    /**
     * Per-source sync modes. Each service's Sync Preferences card
     * renders its own Refresh/Accumulate chip row bound to one of
     * these. Defaults to ACCUMULATE for both.
     */
    val spotifySyncMode: SyncMode = SyncMode.ACCUMULATE,
    val youtubeSyncMode: SyncMode = SyncMode.ACCUMULATE,
    /**
     * Non-null while the Refresh-confirm dialog is shown for that source.
     * Set when the user taps Refresh on a source currently in ACCUMULATE
     * (Refresh deletes rotated-out downloads, so we ask first); cleared on
     * confirm/cancel. null = no dialog.
     */
    val pendingRefreshSource: MusicSource? = null,
    /**
     * When true, the YT Music Liked Songs sync filters out UGC, cover,
     * live, and podcast tracks. Other YT content is unaffected. Default false.
     */
    val youtubeLikedStudioOnly: Boolean = false,
    /**
     * Whether sync discovers the service's own algorithmic mixes. Default true
     * (opt-out) — see SyncPreferencesManager.discoverAutoMixes.
     */
    val discoverAutoMixes: Boolean = true,
    /** Whether sync reads Spotify's saved albums. Default true: SyncPreferencesManager.syncSpotifySavedAlbums. */
    val syncSpotifySavedAlbums: Boolean = true,
    /**
     * Days-of-week bitmask for the auto-sync schedule. Bit 0 = Mon … bit 6 = Sun.
     * Default 127 = every day.
     */
    val syncDays: Int = 0b1111111,
    /** Number of tracks that could not be matched to a YouTube video. */
    val unmatchedCount: Int = 0,
    /**
     * Number of tracks the user flagged from Now Playing as "wrong
     * match." Drives the Sync-tab review card alongside [unmatchedCount]
     * so flagged tracks are reachable even when no sync failures exist.
     */
    val flaggedCount: Int = 0,

    // -- Hero card: last-sync metadata ----------------------------------------
    /**
     * When the most recent sync finished ([syncedAt]: its start while it has no real end), or
     * null if never synced. The screen words it ("synced 3 minutes ago") as it draws, against a clock that
     * ticks while the tab is on screen: [rememberSyncedAgo].
     */
    val lastSyncFinishedAt: Long? = null,
    /** New tracks the most recent sync added, or null if never synced. */
    val lastSyncTrackCount: Int? = null,
    /** Short health label: "✓ healthy", "! partial", "× failed", or "". */
    val lastSyncHealthLabel: String = "",
    /** Tint colour for [lastSyncHealthLabel]. */
    val lastSyncHealthColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent,

    // -- Track count card inputs -----------------------------------------------
    /**
     * Song counts, storage and the latest sync's status: the
     * [com.stash.feature.sync.components.TrackCountCard] under the account
     * cards, and the song count in both heroes. Assembled by
     * [SyncViewModel.observeSyncStatusCard] from the latest sync history,
     * Room song counts (following the mode) and the disk-walked library size.
     */
    val syncStatus: SyncStatusInfo = SyncStatusInfo(),
    /**
     * True after at least one sync has started. Picks the Ready face over
     * the synced heroes, and gates the [com.stash.feature.sync.components.TrackCountCard].
     * Read straight from the latest sync, not from [syncStatus]: that waits for the song count,
     * which takes seconds on a big library, and the Ready face would stay up meanwhile.
     */
    val hasEverSynced: Boolean = false,
    /**
     * The first-sync sheet ("How should your music arrive?") is up. A new
     * install's first Sync asks this before it runs; see [SyncViewModel.onSyncNow].
     */
    val showArrivalSheet: Boolean = false,
    /**
     * "Save to this phone" was picked on the first-sync sheet: the screen opens
     * this source's playlists so the user can choose what to download, then
     * clears it with [SyncViewModel.onManageOpened].
     */
    val openManageFor: SyncSource? = null,
)

/**
 * What the "Undo last sync" card shows: the restore point taken before the
 * last sync, and how many playlists that sync changed.
 */
data class UndoCard(
    val syncId: Long,
    val createdAtEpochMs: Long,
    val changedPlaylists: Int,
)

/** A sync is under way, waiting for its network included: not idle, done or failed. */
internal fun SyncPhase.isActive(): Boolean =
    this !is SyncPhase.Idle && this !is SyncPhase.Completed && this !is SyncPhase.Error

/**
 * ViewModel backing the Sync screen.
 *
 * Combines live sync phase, user preferences, auth state for each service,
 * and recent sync history into a single reactive [SyncUiState].
 */
@HiltViewModel
class SyncViewModel @Inject constructor(
    private val syncScheduler: SyncScheduler,
    private val syncStateManager: SyncStateManager,
    private val syncPreferencesManager: SyncPreferencesManager,
    private val tokenManager: TokenManager,
    private val syncHistoryDao: SyncHistoryDao,
    private val playlistDao: com.stash.core.data.db.dao.PlaylistDao,
    private val syncUndoDao: com.stash.core.data.db.dao.SyncUndoDao,
    private val syncLog: com.stash.core.data.sync.SyncLog,
    private val downloadQueueDao: com.stash.core.data.db.dao.DownloadQueueDao,
    private val musicRepository: com.stash.core.data.repository.MusicRepository,
    private val blocklistGuard: com.stash.core.data.blocklist.BlocklistGuard,
    /**
     * Disk-walked library size (storage-mode-aware: internal File walk
     * OR SAF DocumentFile traversal). Drives the TrackCountCard's
     * storage and FLAC numbers. Mirrors HomeViewModel's injection — the Room
     * `file_size_bytes` column is bypassed because legacy libraries
     * have it stuck at 0 for thousands of rows.
     */
    private val librarySizeHolder: LibrarySizeHolder,
    /**
     * Online-vs-offline preference. Drives the Sync Now button label so
     * users can tell whether tapping it will download tracks to disk
     * (offline mode) or merely surface the library for streaming
     * playback (online mode). See [streamingEnabled].
     */
    private val streamingPreference: com.stash.core.data.prefs.StreamingPreference,
    /**
     * Last.fm-as-a-source manager (issue #255). Owns the "Recommended by
     * Last.fm" mix recipe lifecycle; the Sync tab only renders its state
     * and forwards the user's toggle.
     */
    private val lastFmRecommendationSource: com.stash.core.data.mix.LastFmRecommendationSource,
    /** "Play something new" on the synced hero. */
    private val playerRepository: com.stash.core.media.PlayerRepository,
    /** Live progress of the songs downloading now, for the Sync tab's Downloads row. */
    private val downloadManager: com.stash.data.download.DownloadManager,
    /** The other Spotify accounts: a card and a page each, and their own albums and mixes switches. */
    private val spotifyAccountDao: com.stash.core.data.db.dao.SpotifyAccountDao,
    @param:ApplicationContext private val context: Context? = null,
) : ViewModel() {

    /** The Sync tab's Downloads row: how many run, wait and failed, and how far the running ones are. */
    data class DownloadsSummary(
        val downloading: Int = 0,
        val waiting: Int = 0,
        val failed: Int = 0,
        /** Mean progress of the songs downloading now, 0..1; null while none runs. */
        val progress: Float? = null,
    )

    private val progressByTrackId = MutableStateFlow<Map<Long, Float>>(emptyMap())

    val downloadsSummary: StateFlow<DownloadsSummary> = combine(
        downloadQueueDao.observeActiveDownloadRows(),
        downloadQueueDao.getFailedDownloads(),
        progressByTrackId,
    ) { active, failed, progress ->
        val running = active.filter { it.status == com.stash.core.model.DownloadStatus.IN_PROGRESS }
        DownloadsSummary(
            downloading = running.size,
            waiting = active.size - running.size,
            failed = failed.size,
            progress = running.takeIf { it.isNotEmpty() }
                ?.map { progress[it.trackId] ?: 0f }?.average()?.toFloat(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsSummary())

    init {
        // A song's last progress tick, dropped once its download ends either way (as the Downloads screen keeps it).
        viewModelScope.launch {
            downloadManager.progress.collect { update ->
                progressByTrackId.value = when (update.status) {
                    com.stash.data.download.model.DownloadStatus.COMPLETED,
                    com.stash.data.download.model.DownloadStatus.FAILED,
                    com.stash.data.download.model.DownloadStatus.UNMATCHED,
                    com.stash.data.download.model.DownloadStatus.DEFERRED,
                    -> progressByTrackId.value - update.trackId
                    else -> progressByTrackId.value + (update.trackId to update.progress)
                }
            }
        }
    }

    /**
     * Reactive count of blocked songs, displayed as a badge on the
     * "Blocked Songs" row in the Sync screen's Library section.
     * v0.9.15: sources from BlocklistGuard (track_blocklist) instead
     * of the dropped tracks.is_blacklisted flag.
     */
    val blockedCount: StateFlow<Int> =
        blocklistGuard.observeCount()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = 0,
            )

    /**
     * Reactive count of FAILED rows in download_queue. Drives the
     * "Failed Downloads" card on the Sync tab — the card hides itself
     * when this is 0 so a healthy library shows no clutter.
     */
    val failedDownloadsCount: StateFlow<Int> =
        downloadQueueDao.getFailedDownloads()
            .map { it.size }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = 0,
            )

    /**
     * The "Undo last sync" card: the last sync's restore point and how many
     * playlists that sync changed, or null when there's nothing to undo. Absent
     * means the card isn't rendered at all, so the control only exists when it
     * can actually do something: no point, a sync that changed nothing, or a
     * sync still running (undoing under it would race its writes).
     *
     * The count is worked out against the library when the point appears and
     * when a sync stops, not stored with the point, which is taken before the
     * sync has changed anything.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val undoCard: StateFlow<UndoCard?> =
        combine(
            syncUndoDao.latestPoint(),
            syncStateManager.phase.map { it.isActive() }.distinctUntilChanged(),
        ) { point, syncing -> point.takeUnless { syncing } }
            .mapLatest { point -> point?.let { undoCardFor(it) } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null,
            )

    private suspend fun undoCardFor(point: com.stash.core.data.db.entity.SyncUndoPointEntity): UndoCard? {
        val changed = try {
            syncUndoDao.countChangedPlaylists(point.syncId, point.createdAtEpochMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("SyncViewModel", "Couldn't count what sync ${point.syncId} changed", e)
            return null
        }
        return if (changed > 0) UndoCard(point.syncId, point.createdAtEpochMs, changed) else null
    }

    /**
     * Live account of what the running sync is finding. Drives the terminal on
     * the Sync tab; empty until the first sync of this app session.
     */
    val syncLogLines: StateFlow<List<com.stash.core.data.sync.SyncLog.Line>> =
        syncLog.lines
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    /** The log as plain text, for the copy-to-clipboard action (bug reports). */
    fun syncLogAsText(): String = syncLog.asPlainText()

    /** True while an undo is applying — the button disables so it can't double-fire. */
    private val _undoInProgress = MutableStateFlow(false)
    val undoInProgress: StateFlow<Boolean> = _undoInProgress

    /** One-shot result message after an undo ("Restored 2,919 tracks…"), or null. */
    private val _undoResult = MutableStateFlow<String?>(null)
    val undoResult: StateFlow<String?> = _undoResult

    /**
     * Roll the library back to how it looked before the last sync: playlists it
     * hid come back, playlists it added are hidden, playlists it emptied are
     * refilled in order. Tracks the user added or removed by hand, and Download
     * switches flipped since, are left untouched.
     *
     * Then pauses Auto-sync until the next "Sync now", and drops a sync queued
     * meanwhile: either would otherwise redo the sync the user just undid.
     *
     * Doesn't restore deleted audio files — nothing can — but sync no longer
     * deletes them while any source is on Accumulate.
     */
    fun onUndoLastSync() {
        if (_undoInProgress.value || _uiState.value.isSyncing) return
        viewModelScope.launch {
            _undoInProgress.value = true
            try {
                _undoResult.value = undoLastSync()
            } finally {
                _undoInProgress.value = false
            }
        }
    }

    private suspend fun undoLastSync(): String {
        val point = syncUndoDao.latestPointNow()
            ?: return context?.getString(R.string.sync_undo_nothing) ?: "Nothing to undo."
        val changed = try {
            val changed = syncUndoDao.countChangedPlaylists(point.syncId, point.createdAtEpochMs)
            syncScheduler.cancelPendingSync()
            syncUndoDao.restore(point.syncId)
            changed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("SyncViewModel", "Undo of sync ${point.syncId} failed", e)
            return context?.getString(R.string.sync_undo_error) ?: "Couldn't undo that sync. Nothing was changed."
        }
        // After the restore, and never failing it: the library is already back.
        try {
            syncPreferencesManager.setAutoSyncPausedAfterUndo(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("SyncViewModel", "Couldn't pause Auto-sync after the undo", e)
        }
        val playlists = if (context != null) {
            context.resources.getQuantityString(R.plurals.sync_undo_playlists_count, changed, changed)
        } else {
            if (changed == 1) "1 playlist is" else "$changed playlists are"
        }
        val paused = if (_uiState.value.syncPreferences.autoSyncEnabled) {
            context?.getString(R.string.sync_undo_paused_suffix) ?: " Auto-sync is paused until you tap Sync."
        } else {
            ""
        }
        return context?.getString(R.string.sync_undo_result, playlists, paused)
            ?: "Undone — $playlists back to how they were before that sync.$paused"
    }

    /** Clear the one-shot undo message once the UI has shown it. */
    fun onUndoResultShown() { _undoResult.value = null }

    /**
     * Per-source auth expiry state from SyncStateManager. The Sync tab's
     * AuthExpiredBanner subscribes to this flow and renders nothing when
     * `anyExpired == false`, so re-auth surfaces only when probes flag a
     * problem at sync start.
     */
    val authExpiry: StateFlow<AuthExpiryState> =
        syncStateManager.authExpiry
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = AuthExpiryState(false, false),
            )

    /**
     * Reactive online-streaming-mode flag. The Sync Now button reads
     * this to pick its label: "Surface Library for Streaming" in Online
     * mode, "Download Tracks to Device" in Offline mode. Initial value
     * matches `StreamingPreference.enabled`'s default (false / Offline)
     * so a not-yet-loaded flow displays the safer download-mode label.
     */
    val streamingEnabled: StateFlow<Boolean> =
        streamingPreference.enabled
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = false,
            )

    /**
     * Live Last.fm source state (issue #255): connection, Recommendations
     * toggle, and the materialized playlist's track count. Drives the
     * Last.fm card in the Sync tab's Sources section and its manage screen.
     */
    val lastFmState: StateFlow<com.stash.core.data.mix.LastFmRecommendationState> =
        lastFmRecommendationSource.observeState()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = com.stash.core.data.mix.LastFmRecommendationState(),
            )

    /**
     * User toggled the "Recommended by Last.fm" playlist on/off from the
     * Sync surface. Delegates to [LastFmRecommendationSource], which
     * persists the choice, activates/deactivates recipe + playlist, and —
     * when enabling — kicks a one-shot refresh so the mix starts building.
     */
    fun onLastFmRecommendationsToggled(enabled: Boolean) {
        viewModelScope.launch {
            lastFmRecommendationSource.setRecommendationsEnabled(enabled)
        }
    }

    /**
     * Persist the user's choice between Online (streaming) and Offline
     * (download) mode. Hoisted from the Sync-tab segmented toggle so the
     * user can flip modes without leaving the Sync screen.
     */
    fun setStreamingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            streamingPreference.setEnabled(enabled)
        }
    }

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    init {
        observeSyncPhase()
        observePreferences()
        observeSyncMode()
        observeAuthStates()
        observeHistory()
        observeLastSync()
        observeSpotifyPlaylists()
        observeSpotifyOtherAccounts()
        observeYouTubePlaylists()
        observeSoundCloud()
        observeUnmatchedCount()
        observeFlaggedCount()
        observeSyncStatusCard()
    }

    // -- Public actions -------------------------------------------------------

    /** Trigger an immediate sync, replacing any pending schedule. No-ops
     *  while a sync is already in progress — see [SyncScheduler.triggerManualSync]
     *  for why re-triggering mid-sync is actively harmful, not just redundant.
     *  Offline it queues the sync until a network returns ("Waiting for network"). */
    fun onSyncNow() {
        if (_uiState.value.isSyncing) {
            android.util.Log.i("SyncViewModel", "onSyncNow ignored — sync already in progress")
            return
        }
        viewModelScope.launch {
            if (needsArrivalChoice()) {
                _uiState.update { it.copy(showArrivalSheet = true) }
                return@launch
            }
            startSync()
        }
    }

    private suspend fun startSync() {
        // The Wi-Fi rule this screen shows: what a sync queued offline downloads under.
        val wifiOnly = _uiState.value.syncPreferences.wifiOnly
        // A manual Sync ends the pause "Undo last sync" put on Auto-sync.
        if (_uiState.value.syncPreferences.autoSyncPausedAfterUndo) {
            syncPreferencesManager.setAutoSyncPausedAfterUndo(false)
        }
        syncScheduler.triggerManualSync(wifiOnly)
    }

    /**
     * Whether this Sync is a new install's first, which asks how music should
     * arrive before it runs. New installs start in Download mode with every
     * playlist switched off, so a first sync used to finish with nothing on Home
     * or in Library and users thought it had failed (2026-10-29). An install that
     * has synced before keeps its mode and switches and is never asked; it's
     * marked answered here so the history read happens once.
     */
    private suspend fun needsArrivalChoice(): Boolean {
        if (syncPreferencesManager.arrivalChosen.first()) return false
        if (syncHistoryDao.getLatest() != null) {
            syncPreferencesManager.markArrivalChosen()
            return false
        }
        return true
    }

    /**
     * The first-sync sheet's answer. "Stream everything" ([streamEverything])
     * is Stream only plus new playlists on Home: the whole account shows up
     * after one sync and nothing downloads. "Save to this phone" is Download
     * mode, and opens the first account's playlists so the user can pick what
     * to save while the sync reads them. Either way the sync starts now.
     */
    fun onArrivalChosen(streamEverything: Boolean) {
        _uiState.update { it.copy(showArrivalSheet = false) }
        viewModelScope.launch {
            // Written before the sync starts: DiffWorker reads both once per run.
            streamingPreference.setEnabled(streamEverything)
            syncPreferencesManager.setArrivalChosen(pinNewPlaylists = streamEverything)
            startSync()
            if (!streamEverything) {
                val state = _uiState.value
                val source = when {
                    state.spotifyConnected -> SyncSource.SPOTIFY
                    state.youTubeConnected -> SyncSource.YOUTUBE
                    state.soundCloudConnected -> SyncSource.SOUNDCLOUD
                    else -> null
                }
                _uiState.update { it.copy(openManageFor = source) }
            }
        }
    }

    /** The first-sync sheet was dismissed without an answer: nothing runs, it asks again next time. */
    fun onArrivalDismissed() {
        _uiState.update { it.copy(showArrivalSheet = false) }
    }

    /** The screen has navigated to [SyncUiState.openManageFor]. */
    fun onManageOpened() {
        _uiState.update { it.copy(openManageFor = null) }
    }

    /**
     * A playlist's Save switch: keep it on this phone. The playlist page's
     * Download switch does the same thing ([MusicRepository.setPlaylistDownload],
     * #474), so the two always agree. In Download mode a saved playlist is also
     * switched on for sync, as the old switch was, so later syncs download what
     * it gains and it stays on Home; off there turns sync off too. A mix is
     * never pinned by this: on a mix the pin is its Favorite (see
     * [com.stash.core.data.db.dao.PlaylistDao.setSyncEnabledAndPin]).
     */
    /**
     * The account page's Save all: every one of [playlistIds] saved as its own Save would, but their
     * songs queued together for one download run ([com.stash.core.data.repository.MusicRepository.keepPlaylistsOffline]),
     * never a run started per playlist. The rows offered are in the library already (taken-out
     * ones aren't offered).
     */
    fun onSaveAll(playlistIds: List<Long>) {
        if (playlistIds.isEmpty()) return
        viewModelScope.launch {
            if (!streamingPreference.current()) {
                val now = System.currentTimeMillis()
                playlistIds.forEach { playlistDao.setSyncEnabledAndPin(it, true, now) }
            }
            musicRepository.keepPlaylistsOffline(playlistIds)
        }
    }

    fun onSavePlaylist(playlistId: Long, save: Boolean) {
        viewModelScope.launch {
            // Saving one that's out of the library brings it back: saved is in.
            if (save) playlistDao.setUserHidden(playlistId, false)
            if (save && !streamingPreference.current()) {
                playlistDao.setSyncEnabledAndPin(playlistId, true, System.currentTimeMillis())
            }
            musicRepository.setPlaylistDownload(playlistId, save)
        }
    }

    /**
     * Cancel any in-flight sync. Backed by `WorkManager.cancelUniqueWork`,
     * which cancels the work chain and any worker currently running — the
     * worker sees a cancellation exception and exits. In-progress track
     * downloads that finish before the cancellation is observed will still
     * complete, but no new tracks are enqueued. The daily schedule stays.
     * The run's history row is closed as cancelled too, so the status card at
     * the top of the tab stops saying "Syncing...".
     */
    fun onStopSync() {
        viewModelScope.launch { syncScheduler.cancelSync() }
    }

    /**
     * A playlist's "In your library" check, on its account's page: the way to
     * turn off what a sync brought in and isn't wanted (2026-10-29). Out of the
     * library it leaves Home, Library and Songs, and later syncs leave it alone;
     * one being saved stops downloading too (songs already on the phone stay,
     * as with the Download switch). Back in, it shows again.
     */
    /**
     * "Play something new": the newest mix, from its first song. The hero that offers it only
     * shows in Stream only, where every song plays without a download.
     */
    fun onPlayPlaylist(playlistId: Long, name: String) {
        viewModelScope.launch {
            val tracks = musicRepository.getTracksByPlaylist(playlistId).first()
            if (tracks.isNotEmpty()) {
                playerRepository.setQueue(
                    tracks,
                    startIndex = 0,
                    source = com.stash.core.model.PlaybackSource.Playlist(playlistId, name),
                )
            }
        }
    }

    /** A mix's Stack switch: keep every song it brings, from the next sync on. */
    fun onSetStack(playlistId: Long, on: Boolean) {
        viewModelScope.launch { playlistDao.setStackSongs(playlistId, on) }
    }

    fun onSetInLibrary(playlistId: Long, inLibrary: Boolean) {
        viewModelScope.launch {
            if (!inLibrary) {
                musicRepository.setPlaylistDownload(playlistId, false)
            }
            playlistDao.setUserHidden(playlistId, !inLibrary)
        }
    }

    /**
     * Update the daily sync schedule time and reschedule.
     *
     * @param hour   Hour of day (0-23).
     * @param minute Minute of hour (0-59).
     */
    fun onSetSyncTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            syncPreferencesManager.setSyncTime(hour, minute)
            val prefs = _uiState.value.syncPreferences
            if (prefs.autoSyncEnabled) {
                syncScheduler.scheduleDailySync(
                    hour,
                    minute,
                    wifiOnly = prefs.wifiOnly,
                    days = DayOfWeekSet(prefs.syncDays),
                )
            }
        }
    }

    /** Toggle the daily auto-sync on or off. */
    fun onToggleAutoSync() {
        viewModelScope.launch {
            val current = _uiState.value.syncPreferences.autoSyncEnabled
            val newValue = !current
            syncPreferencesManager.setAutoSyncEnabled(newValue)
            if (newValue) {
                val prefs = _uiState.value.syncPreferences
                syncScheduler.scheduleDailySync(
                    prefs.syncHour,
                    prefs.syncMinute,
                    wifiOnly = prefs.wifiOnly,
                    days = DayOfWeekSet(prefs.syncDays),
                )
            } else {
                syncScheduler.cancelDailySync()
            }
        }
    }

    /** Toggle the Wi-Fi-only constraint on or off. */
    fun onToggleWifiOnly() {
        viewModelScope.launch {
            val current = _uiState.value.syncPreferences.wifiOnly
            syncPreferencesManager.setWifiOnly(!current)
            // A scheduled sync still waiting (say, for Wi-Fi) takes the new rule now.
            syncScheduler.applyWifiOnlyToWaitingSync(!current)
        }
    }

    /** Switch Spotify's Refresh/Accumulate mode. */
    fun onSpotifySyncModeChanged(mode: SyncMode) {
        viewModelScope.launch {
            syncPreferencesManager.setSpotifySyncMode(mode)
        }
    }

    /** Switch YouTube's Refresh/Accumulate mode. */
    fun onYoutubeSyncModeChanged(mode: SyncMode) {
        viewModelScope.launch {
            syncPreferencesManager.setYoutubeSyncMode(mode)
        }
    }

    /** Refresh chip tapped for Spotify. If currently ACCUMULATE, confirm first
     *  (Refresh deletes rotated-out downloads); if already REFRESH, no-op. */
    fun onRequestSpotifyRefresh() {
        if (_uiState.value.spotifySyncMode == SyncMode.ACCUMULATE) {
            _uiState.update { it.copy(pendingRefreshSource = MusicSource.SPOTIFY) }
        }
    }

    /** Refresh chip tapped for YouTube — see [onRequestSpotifyRefresh]. */
    fun onRequestYoutubeRefresh() {
        if (_uiState.value.youtubeSyncMode == SyncMode.ACCUMULATE) {
            _uiState.update { it.copy(pendingRefreshSource = MusicSource.YOUTUBE) }
        }
    }

    /** Confirm the pending Refresh switch — applies REFRESH to that source. */
    fun confirmRefreshMode() {
        val source = _uiState.value.pendingRefreshSource ?: return
        viewModelScope.launch {
            when (source) {
                MusicSource.YOUTUBE -> syncPreferencesManager.setYoutubeSyncMode(SyncMode.REFRESH)
                MusicSource.SPOTIFY -> syncPreferencesManager.setSpotifySyncMode(SyncMode.REFRESH)
                MusicSource.SOUNDCLOUD -> syncPreferencesManager.setSoundCloudSyncMode(SyncMode.REFRESH)
                MusicSource.LOCAL, MusicSource.BOTH, MusicSource.CLOUD -> Unit
            }
        }
        _uiState.update { it.copy(pendingRefreshSource = null) }
    }

    /** Dismiss the dialog — keep the current (Accumulate) mode. */
    fun cancelRefreshMode() {
        _uiState.update { it.copy(pendingRefreshSource = null) }
    }

    /** Persists the user's choice for the studio-only Liked Songs filter. */
    fun onYoutubeLikedStudioOnlyChanged(enabled: Boolean) {
        viewModelScope.launch {
            syncPreferencesManager.setYoutubeLikedStudioOnly(enabled)
        }
    }

    /**
     * Turn discovery of the service's own algorithmic mixes on/off (#335, #344).
     * Off stops them being fetched at all, which also shortens every sync.
     * Mixes already in the library are left alone — this is not a delete.
     */
    fun onDiscoverAutoMixesChanged(enabled: Boolean) {
        viewModelScope.launch {
            syncPreferencesManager.setDiscoverAutoMixes(enabled)
        }
    }

    /**
     * Turn the sync of Spotify's saved albums on or off (item 5, 2026-11-02). Off, sync reads no
     * saved albums; the ones already here stay, as mixes do when discovery is switched off.
     */
    fun onSyncSpotifySavedAlbumsChanged(enabled: Boolean) {
        viewModelScope.launch {
            syncPreferencesManager.setSyncSpotifySavedAlbums(enabled)
        }
    }

    /**
     * Persists the days-of-week selection and reschedules, so the first run
     * lands on the next chosen day and a schedule that had no days (so no
     * trigger) starts again. UI passes the new bitmask.
     */
    fun onSyncDaysChanged(bitmask: Int) {
        viewModelScope.launch {
            syncPreferencesManager.setSyncDays(bitmask)
            val prefs = _uiState.value.syncPreferences
            if (prefs.autoSyncEnabled) {
                syncScheduler.scheduleDailySync(
                    prefs.syncHour,
                    prefs.syncMinute,
                    wifiOnly = prefs.wifiOnly,
                    days = DayOfWeekSet(bitmask),
                )
            }
        }
    }

    // -- Internal observers ---------------------------------------------------

    private fun observeSyncPhase() {
        viewModelScope.launch {
            syncStateManager.phase.collect { phase ->
                _uiState.update {
                    it.copy(
                        syncPhase = phase,
                        overallProgress = phase.progress,
                        isSyncing = phase.isActive(),
                    )
                }
            }
        }
    }

    private fun observePreferences() {
        viewModelScope.launch {
            syncPreferencesManager.preferences.collect { prefs ->
                _uiState.update { it.copy(syncPreferences = prefs) }
            }
        }
    }

    private fun observeSyncMode() {
        viewModelScope.launch {
            syncPreferencesManager.spotifySyncMode.collect { mode ->
                _uiState.update { it.copy(spotifySyncMode = mode) }
            }
        }
        viewModelScope.launch {
            syncPreferencesManager.youtubeSyncMode.collect { mode ->
                _uiState.update { it.copy(youtubeSyncMode = mode) }
            }
        }
        viewModelScope.launch {
            syncPreferencesManager.youtubeLikedStudioOnly.collect { enabled ->
                _uiState.update { it.copy(youtubeLikedStudioOnly = enabled) }
            }
        }
        viewModelScope.launch {
            syncPreferencesManager.discoverAutoMixes.collect { enabled ->
                _uiState.update { it.copy(discoverAutoMixes = enabled) }
            }
        }
        viewModelScope.launch {
            syncPreferencesManager.syncSpotifySavedAlbums.collect { enabled ->
                _uiState.update { it.copy(syncSpotifySavedAlbums = enabled) }
            }
        }
        viewModelScope.launch {
            syncPreferencesManager.syncDays.collect { bitmask ->
                _uiState.update { it.copy(syncDays = bitmask) }
            }
        }
    }

    private fun observeAuthStates() {
        viewModelScope.launch {
            combine(
                tokenManager.spotifyAuthState,
                tokenManager.youTubeAuthState,
            ) { spotify, youtube ->
                Pair(spotify is AuthState.Connected, youtube is AuthState.Connected)
            }.collect { (spotifyConnected, youTubeConnected) ->
                _uiState.update {
                    it.copy(
                        spotifyConnected = spotifyConnected,
                        youTubeConnected = youTubeConnected,
                    )
                }
            }
        }
    }

    private fun observeHistory() {
        viewModelScope.launch {
            syncHistoryDao.getRecentSyncs(limit = 10).collect { entities ->
                _uiState.update {
                    it.copy(recentSyncs = entities.map { e -> e.toInfo() })
                }
            }
        }
    }

    /**
     * Derives hero-card last-sync metadata from the most recent history entry.
     * Uses a limit-1 query so only the minimum data is loaded for this purpose.
     * Health is derived from [SyncDisplayStatus] — same source used by
     * [SyncHistoryRow] to render icons and badge colours.
     */
    private fun observeLastSync() {
        viewModelScope.launch {
            syncHistoryDao.getRecentSyncs(limit = 1).collect { entities ->
                val latest = entities.firstOrNull()?.toInfo()
                val healthLabel = when (latest?.displayStatus) {
                    null -> ""
                    SyncDisplayStatus.Success -> "✓"
                    is SyncDisplayStatus.PartialSuccess -> "!"
                    is SyncDisplayStatus.Interrupted -> "!"
                    SyncDisplayStatus.Cancelled -> "⊘"
                    is SyncDisplayStatus.Failed -> "×"
                    else -> ""
                }
                val healthColor = when (latest?.displayStatus) {
                    SyncDisplayStatus.Success ->
                        androidx.compose.ui.graphics.Color(0xFF10B981)
                    is SyncDisplayStatus.PartialSuccess,
                    is SyncDisplayStatus.Interrupted ->
                        androidx.compose.ui.graphics.Color(0xFFF59E0B)
                    SyncDisplayStatus.Cancelled ->
                        androidx.compose.ui.graphics.Color(0xFF9CA3AF)
                    is SyncDisplayStatus.Failed ->
                        androidx.compose.ui.graphics.Color(0xFFEF4444)
                    else -> androidx.compose.ui.graphics.Color.Transparent
                }
                _uiState.update { state ->
                    state.copy(
                        lastSyncFinishedAt = latest?.syncedAt(),
                        // What the sync FOUND, not what it downloaded. In Online
                        // mode nothing downloads, so tracksDownloaded is always 0
                        // and the headline read "0 tracks" after a run that added
                        // 159 songs. newTracksFound is the honest answer in both
                        // modes — Offline downloads them, Online streams them, but
                        // either way they are new music in the library.
                        lastSyncTrackCount = latest?.newTracksFound,
                        lastSyncHealthLabel = healthLabel,
                        lastSyncHealthColor = healthColor,
                    )
                }
            }
        }
    }

    private fun observeSpotifyPlaylists() {
        viewModelScope.launch {
            playlistDao.getSpotifyPlaylistsForPreferences().collect { entities ->
                _uiState.update {
                    it.copy(
                        spotifyPlaylists = entities.map { e ->
                            SpotifySyncPlaylist(
                                id = e.id,
                                name = e.name,
                                trackCount = e.trackCount,
                                type = e.type,
                                syncEnabled = e.syncEnabled,
                                artUrl = e.artUrl,
                                hideFromHome = e.hideFromHome,
                                pinnedToHomeAt = e.pinnedToHomeAt,
                                keepOffline = e.keepOffline,
                                userHidden = e.userHidden,
                                dateAdded = e.dateAdded.toEpochMilli(),
                                stackable = com.stash.core.data.sync.MixStacking.showsSwitch(e.source, e.type, e.name, e.stackSongs),
                                stackSongs = e.stackSongs,
                                album = com.stash.core.data.album.SavedAlbums.isSavedAlbum(e.type, e.source, e.sourceId),
                                spotifyAccount = e.spotifyAccount,
                            )
                        }
                    )
                }
            }
        }
    }

    /**
     * The other Spotify accounts, for their cards and pages. A table that can't be read leaves
     * none (the main account's card and page are as they were), and says it has been read, so an
     * other account's page shows empty rather than waiting for ever.
     */
    private fun observeSpotifyOtherAccounts() {
        viewModelScope.launch {
            spotifyAccountDao.observeAll()
                .map { rows ->
                    rows.map { SpotifyOtherAccount(it.id, it.userId, it.label, it.syncAlbums, it.syncMixes) }
                }
                .catch { e ->
                    android.util.Log.w("SyncViewModel", "couldn't read the other Spotify accounts", e)
                    emit(emptyList())
                }
                .collect { others ->
                    _uiState.update { it.copy(spotifyOtherAccounts = others, spotifyOtherAccountsLoaded = true) }
                }
        }
    }

    /** An other account's own saved-albums switch, on its page's Albums tab. */
    fun onSyncOtherAccountAlbumsChanged(accountId: Long, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { spotifyAccountDao.setSyncAlbums(accountId, enabled) }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    /** An other account's own mixes switch, on its page's Mixes tab. */
    fun onSyncOtherAccountMixesChanged(accountId: Long, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { spotifyAccountDao.setSyncMixes(accountId, enabled) }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    private fun observeSoundCloud() {
        viewModelScope.launch {
            combine(tokenManager.soundCloudSession, playlistDao.getBySource(MusicSource.SOUNDCLOUD)) { session, entities ->
                session to entities.filter { session != null && com.stash.core.data.sync.SoundCloudPlaylistIds.belongsTo(it.sourceId, session.userId) }
            }.collect { (session, entities) ->
                _uiState.update { state -> state.copy(soundCloudConnected = session != null,
                    soundCloudPlaylists = entities.map { e ->
                        YouTubeSyncPlaylist(id = e.id, name = e.name, trackCount = e.trackCount, type = e.type,
                            syncEnabled = e.syncEnabled, artUrl = e.artUrl, keepOffline = e.keepOffline,
                            userHidden = e.userHidden, dateAdded = e.dateAdded.toEpochMilli())
                    }) }
            }
        }
    }

    private fun observeYouTubePlaylists() {
        viewModelScope.launch {
            playlistDao.getYouTubePlaylistsForPreferences().collect { entities ->
                _uiState.update {
                    it.copy(
                        youTubePlaylists = entities.map { e ->
                            YouTubeSyncPlaylist(
                                id = e.id,
                                name = e.name,
                                trackCount = e.trackCount,
                                type = e.type,
                                syncEnabled = e.syncEnabled,
                                artUrl = e.artUrl,
                                hideFromHome = e.hideFromHome,
                                pinnedToHomeAt = e.pinnedToHomeAt,
                                keepOffline = e.keepOffline,
                                userHidden = e.userHidden,
                                dateAdded = e.dateAdded.toEpochMilli(),
                                stackable = com.stash.core.data.sync.MixStacking.showsSwitch(e.source, e.type, e.name, e.stackSongs),
                                stackSongs = e.stackSongs,
                                album = com.stash.core.data.album.SavedAlbums.isSavedAlbum(e.type, e.source, e.sourceId),
                            )
                        }
                    )
                }
            }
        }
    }

    /**
     * Observe the live count of tracks that failed YouTube matching.
     *
     * Drives the amber warning card on the Sync screen so the user always
     * knows when there are songs requiring manual review.
     */
    private fun observeUnmatchedCount() {
        viewModelScope.launch {
            musicRepository.getUnmatchedCount().collect { count ->
                _uiState.update { it.copy(unmatchedCount = count) }
            }
        }
    }

    /**
     * Observe the live count of user-flagged "wrong match" tracks so the
     * Sync-tab review card is reachable even when no sync-side matching
     * failures exist. Without this, tracks flagged from Now Playing had
     * no entry-point surface into Failed Matches and appeared lost.
     */
    private fun observeFlaggedCount() {
        viewModelScope.launch {
            musicRepository.getFlaggedCount().collect { count ->
                _uiState.update { it.copy(flaggedCount = count) }
            }
        }
    }

    /**
     * Feeds [SyncUiState.syncStatus]: the track count card under the account
     * cards and the song count in both heroes.
     *
     * Inputs (all reactive):
     *  - `observeLatestSync()` for last-sync timestamp + display status
     *  - `observeSongCountsBySource(inLibrary = Online)` for the song counts, per
     *    source and in total. They follow the mode, as Library's Songs tab does:
     *    Online counts the songs in your library, downloaded or not; Offline
     *    counts downloaded songs. (Until 2026-11-04 both modes counted downloads
     *    only, so the Online hero read "0 songs" for someone who streams.)
     *  - `librarySizeHolder.size` for storage and FLAC (disk truth — the Room
     *    `file_size_bytes` SUM is unreliable for legacy libraries)
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeSyncStatusCard() {
        val syncStatusFlow = musicRepository.observeLatestSync().map { latestSync ->
            if (latestSync != null) {
                SyncStatusInfo(
                    lastSyncTime = latestSync.startedAt.toEpochMilli(),
                    nextSyncTime = latestSync.completedAt?.toEpochMilli()?.plus(6 * 3_600_000L),
                    state = latestSync.status,
                    displayStatus = latestSync.toDisplayStatus(),
                )
            } else {
                SyncStatusInfo(displayStatus = SyncDisplayStatus.Idle)
            }
        }

        // Online re-reads the in-library count; a flip of the mode switches the query. The count re-runs
        // on every write to the tracks table and takes real time on a big library, so it runs only when
        // its numbers can be seen. Paused while a sync runs: a sync writes constantly, and the tab shows
        // its syncing face then. Paused while nobody collects uiState: this ViewModel outlives a tab
        // switch, and "Fetch lyrics" stamps a track about every 3 s, which was a full count of a 72k
        // library every 3 s off-screen. The last numbers stay; opening the tab reads again.
        val songCountsFlow = combine(
            streamingPreference.enabled.distinctUntilChanged(),
            syncStateManager.phase.map { it.isActive() }.distinctUntilChanged(),
            _uiState.subscriptionCount.map { it > 0 }.distinctUntilChanged(),
        ) { online, syncing, onScreen -> Triple(online, syncing, onScreen) }
            .distinctUntilChanged()
            .flatMapLatest { (online, syncing, onScreen) ->
                if (syncing || !onScreen) {
                    emptyFlow()
                } else {
                    musicRepository.observeSongCountsBySource(inLibrary = online).map { online to it }
                }
            }

        viewModelScope.launch {
            combine(
                syncStatusFlow,
                songCountsFlow,
                librarySizeHolder.size,
            ) { base, (online, counts), librarySize ->
                val spotify = counts[MusicSource.SPOTIFY] ?: 0
                val youTube = counts[MusicSource.YOUTUBE] ?: 0
                val soundCloud = counts[MusicSource.SOUNDCLOUD] ?: 0
                val total = counts.values.sum()
                base.copy(
                    totalTracks = total,
                    spotifyTracks = spotify,
                    youTubeTracks = youTube,
                    soundCloudTracks = soundCloud,
                    otherTracks = total - spotify - youTube - soundCloud,
                    countsInLibrary = online,
                    songCountsKnown = true,
                    storageUsedBytes = librarySize.totalBytes,
                    flacTracks = librarySize.losslessFileCount,
                    flacStorageBytes = librarySize.losslessBytes,
                )
            }.collect { status ->
                _uiState.update { it.copy(syncStatus = status) }
            }
        }

        // Which face the tab shows doesn't wait for the count above.
        viewModelScope.launch {
            musicRepository.observeLatestSync()
                .map { latestSync -> latestSync != null }
                .distinctUntilChanged()
                .collect { synced -> _uiState.update { it.copy(hasEverSynced = synced) } }
        }
    }

    /** Map Room entity to lightweight presentation model. */
    private fun SyncHistoryEntity.toInfo() = SyncHistoryInfo(
        id = id,
        startedAt = startedAt.toEpochMilli(),
        completedAt = completedAt?.toEpochMilli(),
        status = status.name,
        tracksDownloaded = tracksDownloaded,
        tracksFailed = tracksFailed,
        newTracksFound = newTracksFound,
        playlistsChecked = playlistsChecked,
        bytesDownloaded = bytesDownloaded,
        trigger = trigger,
        streamingMode = streamingMode,
        errorMessage = errorMessage,
        diagnostics = diagnostics,
        displayStatus = toDisplayStatus(),
    )
}
