package com.stash.feature.home

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.discovery.GenreCatalog
import com.stash.core.data.discovery.HomeDiscoveryRepository
import com.stash.core.data.mix.MixBuildState
import com.stash.core.data.mix.mixBuildState
import com.stash.core.data.prefs.DownloadNetworkPreference
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.workers.StashDiscoveryWorker
import com.stash.core.data.sync.workers.StashMixRefreshWorker
import com.stash.core.media.PlayerRepository
import com.stash.core.model.Playlist
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.backfill.MetadataBackfillState
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.PlaylistSummary
import com.stash.feature.home.banner.MetadataBackfillBannerState
import com.stash.feature.home.banner.metadataBackfillBannerStateFor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the redesigned Home screen — a pure *discovery* surface.
 *
 * Home now carries only the **Discover hero** (the materialized builtin Daily
 * Discover playlist) plus the persistent "chrome": tip-jar pill, lossless
 * prompt, metadata-backfill banner, and the streaming toggle. All library
 * content (mixes, liked songs, recently-added, custom playlists) and its
 * flows/methods were relocated to the Library screen (`:feature:library`).
 *
 * Data sources are Flow-based so the hero updates automatically when the
 * Daily Discover playlist materializes after a discovery run.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val losslessPrefs: LosslessSourcePreferences,
    private val settingsDeepLinkController: com.stash.core.data.navigation.SettingsDeepLinkController,
    private val tipJarRepository: com.stash.core.data.tipjar.TipJarRepository,
    private val recipeDao: StashMixRecipeDao,
    private val discoveryQueueDao: DiscoveryQueueDao,
    private val playlistDao: com.stash.core.data.db.dao.PlaylistDao,
    private val downloadNetworkPreference: DownloadNetworkPreference,
    private val streamingPreference: StreamingPreference,
    private val metadataBackfillState: MetadataBackfillState,
    private val homeDiscoveryRepository: HomeDiscoveryRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /**
     * Master streaming-mode toggle observed by the Home `StreamingModeToggle`.
     * Mirrors [StreamingPreference.enabled] one-for-one — writes route
     * through `MusicRepository.applyStreamingMode` (currently just a pref
     * write — v0.9.30 Path A: Library is downloaded-only regardless).
     *
     * Gated for visibility by `StashConstants.STREAMING_ENGINE_ENABLED`
     * inside the composable — the StateFlow keeps emitting regardless,
     * so when the kill-switch is flipped on the Home toggle picks up the
     * current pref value immediately without a recompose cycle.
     */
    val streamingEnabled: StateFlow<Boolean> = streamingPreference.enabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    /**
     * One-shot event: emit when the user is about to enable streaming for
     * the first time so the Home screen can show the privacy disclosure
     * dialog. The pref is already being flipped — the dialog is purely
     * informational ("here's what streaming means"), not a confirmation
     * gate.
     */
    private val _showStreamingDisclosure = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val showStreamingDisclosure: SharedFlow<Unit> = _showStreamingDisclosure.asSharedFlow()

    /**
     * v0.9.30 Path A: simplified one-arg streaming toggle.
     *
     * Off→On: if the user has never seen the disclosure, emit a one-shot
     * event so the screen renders the AlertDialog after the pref flips.
     * On→Off: no prompt — flip the pref instantly.
     *
     * Library is always downloaded-only regardless of this toggle; the
     * pref gates only search-tap streaming and the Now Playing wifi
     * indicator. See `MusicRepository.applyStreamingMode` for the
     * (deliberately minimal) side-effects.
     */
    fun onStreamingToggle(enabled: Boolean) {
        viewModelScope.launch {
            musicRepository.applyStreamingMode(enabled = enabled)
            if (enabled) {
                val prefs = context.getSharedPreferences(
                    STREAMING_DISCLOSURE_PREFS,
                    Context.MODE_PRIVATE,
                )
                if (!prefs.getBoolean(STREAMING_DISCLOSURE_SEEN_KEY, false)) {
                    _showStreamingDisclosure.tryEmit(Unit)
                    prefs.edit().putBoolean(STREAMING_DISCLOSURE_SEEN_KEY, true).apply()
                }
            }
        }
    }

    private val _userMessages = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** One-shot snackbar messages. */
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    init {
        // v0.9.13: warm the tip-jar cache on cold-start, then trigger a
        // network refresh if the cache is stale (>15 min). Failures are
        // silently absorbed by the repo — the pill always shows
        // something thanks to the bundled fallback.
        viewModelScope.launch {
            tipJarRepository.warmUp()
            if (tipJarRepository.isStale()) {
                tipJarRepository.refresh()
            }
        }
    }

    /** v0.9.13: callable from the screen on resume to keep the pill fresh. */
    fun refreshTipJarIfStale() {
        viewModelScope.launch {
            if (tipJarRepository.isStale()) tipJarRepository.refresh()
        }
    }

    /**
     * Session-scoped "minimize Daily Discover": long-press the hero →
     * "Minimize for now" drops the hero page until the next app launch.
     * Deliberately not persisted — it's "hide it for a bit", not a setting.
     */
    private val _heroMinimized = MutableStateFlow(false)
    val heroMinimized: StateFlow<Boolean> = _heroMinimized

    fun setHeroMinimized(minimized: Boolean) {
        _heroMinimized.value = minimized
    }

    /** Everything derived from the playlists + recipe streams in one holder. */
    private data class HomePlaylistData(
        val hero: DiscoverHeroState?,
        val madeForYou: List<HomeMix>,
        val radios: List<HomeMix>,
        val moodDecades: List<HomeMix>,
        val yourMixes: List<HomeMix>,
        val customMixPlaylistIds: Set<Long>,
    )

    private val homePlaylistFlow: Flow<HomePlaylistData> = combine(
        musicRepository.getAllPlaylists(),
        recipeDao.observeAll(),
        discoveryQueueDao.observeNonFailedCountsByRecipe(),
        // Builtin ids are a one-shot suspend read wrapped as a flow.
        flow { emit(recipeDao.getBuiltinPlaylistIds()) },
    ) { playlists, recipes, discoveryCounts, builtinIdList ->
        val builtinIds = builtinIdList.toSet()
        val customRecipes = recipes.filter { !it.isBuiltin && it.playlistId != null }
        val customMixPlaylistIds = customRecipes.mapNotNull { it.playlistId }.toSet()

        // Per-custom-mix build state (Building… / No tracks) — mirrors LibraryViewModel.
        val trackCounts = playlists.associate { it.id to it.trackCount }
        val discoveryByRecipe = discoveryCounts.associate { it.recipeId to it.count }
        val buildingMixIds = mutableSetOf<Long>()
        val emptyMixIds = mutableSetOf<Long>()
        for (recipe in customRecipes) {
            val playlistId = recipe.playlistId ?: continue
            when (
                mixBuildState(
                    recipe = recipe,
                    trackCount = trackCounts[playlistId] ?: 0,
                    nonFailedDiscoveryCount = discoveryByRecipe[recipe.id] ?: 0,
                )
            ) {
                MixBuildState.BUILDING -> buildingMixIds.add(playlistId)
                MixBuildState.EMPTY -> emptyMixIds.add(playlistId)
                MixBuildState.READY -> Unit
            }
        }

        // Hero: the builtin Daily Discover playlist, once it has materialized.
        val hero = playlists
            .firstOrNull { it.id == builtinIdList.firstOrNull() && it.trackCount > 0 }
            ?.let { playlist ->
                DiscoverHeroState(
                    title = playlist.name,
                    subtitle = "${playlist.trackCount} tracks · updated daily",
                    artUrl = playlist.artUrl,
                    playlistId = playlist.id,
                )
            }

        // Classify each playlist into its Home rail. buildState only carries for
        // STASH_MIX (YOUR_MIXES); builtin Daily Discover is excluded from YOUR_MIXES.
        val madeForYou = mutableListOf<HomeMix>()
        val radios = mutableListOf<HomeMix>()
        val moodDecades = mutableListOf<HomeMix>()
        val yourMixes = mutableListOf<HomeMix>()
        for (p in playlists.filter { !it.hideFromHome }) {
            when (mixRail(p)) {
                MixRail.MADE_FOR_YOU -> madeForYou += p.toHomeMix()
                MixRail.RADIOS -> radios += p.toHomeMix()
                MixRail.MOOD_DECADES -> moodDecades += p.toHomeMix()
                MixRail.YOUR_MIXES -> if (p.id !in builtinIds) {
                    val state = when {
                        p.id in buildingMixIds -> MixBuildState.BUILDING
                        p.id in emptyMixIds -> MixBuildState.EMPTY
                        else -> MixBuildState.READY
                    }
                    yourMixes += p.toHomeMix(state)
                }
                null -> Unit
            }
        }

        HomePlaylistData(hero, madeForYou, radios, moodDecades, yourMixes, customMixPlaylistIds)
    }

    private fun Playlist.toHomeMix(buildState: MixBuildState = MixBuildState.READY) =
        HomeMix(
            id = id, title = name, artUrl = artUrl, source = source,
            buildState = buildState, trackCount = trackCount,
        )

    /**
     * Lossless connect nudge: only visible when the user has not
     * enabled lossless AND has not dismissed the banner. Once dismissed,
     * the DataStore write makes the Flow re-emit null and the banner
     * disappears on its own.
     */
    private val losslessPromptFlow = combine(
        losslessPrefs.enabled,
        losslessPrefs.bannerDismissed,
    ) { enabled, dismissed ->
        if (!enabled && !dismissed) LosslessPromptState else null
    }

    /**
     * v0.9.35: drives [HomeUiState.metadataBackfillBanner]. Pure-mapped
     * from [MetadataBackfillState.snapshot] so the banner sealed type
     * doesn't have to plumb through the raw DataStore record. Hidden in
     * the steady state (the dominant case post-backfill).
     */
    private val metadataBackfillBannerFlow: Flow<MetadataBackfillBannerState> =
        metadataBackfillState.snapshot.map { metadataBackfillBannerStateFor(it) }

    /** Selected genre chip label ("All" = no filter). Drives the discovery rows. */
    private val genreFilter = MutableStateFlow("All")

    private data class DiscoveryUi(
        val selectedGenre: String,
        val newReleases: List<AlbumSummary>,
        val topAlbums: List<AlbumSummary>,
        val playlists: List<PlaylistSummary>,
    )

    /**
     * The three Qobuz discovery rows, re-fetched whenever the genre chip
     * changes ([flatMapLatest] cancels the previous fetch). The previous
     * genre's rows STAY on screen while the new fetch runs (the scan keeps
     * the last loaded state) — the rows sit directly under the hero, so
     * clearing them eagerly made the mix rails jump up for the fetch
     * duration on every chip tap. The chip highlight still updates
     * instantly via the loading emission (null rows = keep previous), and
     * the loaded emission replaces wholesale — even when empty — so stale
     * content never survives a completed fetch. Fail-soft as before.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val discoveryFlow: Flow<DiscoveryUi> = genreFilter
        .flatMapLatest { label ->
            flow<Pair<String, DiscoveryUi?>> {
                val genreId = GenreCatalog.idFor(label)
                emit(label to null) // loading: re-label, keep previous rows
                coroutineScope {
                    val newReleases = async { homeDiscoveryRepository.newReleases(genreId) }
                    val playlists = async { homeDiscoveryRepository.communityPlaylists(genreId) }
                    val topAlbums = async { homeDiscoveryRepository.topAlbums(genreId) }
                    emit(label to DiscoveryUi(label, newReleases.await(), topAlbums.await(), playlists.await()))
                }
            }
        }
        .scan(DiscoveryUi("All", emptyList(), emptyList(), emptyList())) { prev, (label, loaded) ->
            loaded ?: prev.copy(selectedGenre = label)
        }

    /** Select a genre chip; re-derives all three discovery rows. */
    fun onSelectGenre(label: String) { genreFilter.value = label }

    val uiState: StateFlow<HomeUiState> = combine(
        homePlaylistFlow,
        losslessPromptFlow,
        tipJarRepository.state,
        metadataBackfillBannerFlow,
        discoveryFlow,
    ) { home, losslessPrompt, tipJar, metadataBackfillBanner, discovery ->
        HomeUiState(
            hero = home.hero,
            isLoading = false,
            losslessPrompt = losslessPrompt,
            tipJar = tipJar,
            metadataBackfillBanner = metadataBackfillBanner,
            selectedGenre = discovery.selectedGenre,
            newReleases = discovery.newReleases,
            topAlbums = discovery.topAlbums,
            playlists = discovery.playlists,
            madeForYou = home.madeForYou,
            radios = home.radios,
            moodDecades = home.moodDecades,
            yourMixes = home.yourMixes,
            customMixPlaylistIds = home.customMixPlaylistIds,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    /**
     * Hide the "Try lossless audio" Home banner forever. Writes
     * through to DataStore; the prompt Flow re-emits null on the
     * next tick and the banner disappears.
     */
    fun dismissLosslessBanner() {
        viewModelScope.launch {
            losslessPrefs.setBannerDismissed(true)
        }
    }

    /**
     * v0.9.35: called by the Home re-tagging banner's `LaunchedEffect`
     * after the 2-second "Done" pulse expires. Flips
     * [MetadataBackfillState] back to IDLE, which causes the snapshot
     * Flow to emit a [MetadataBackfillBannerState.Hidden] mapping and
     * the banner vanishes from the screen.
     */
    fun onMetadataBackfillFinishedAcknowledged() {
        viewModelScope.launch { metadataBackfillState.markFinishedAcknowledged() }
    }

    /**
     * v0.9.13: Queue a Settings deep-link to the Lossless / Audio Quality
     * card. The Settings screen reads + clears this on entry and scrolls
     * the targeted card into view. Called by [LosslessConnectBanner]'s
     * tap handler immediately before navigation, so the read happens
     * after the navigation has actually started.
     */
    fun requestSettingsLosslessFocus() {
        settingsDeepLinkController.request(com.stash.core.data.navigation.SettingsFocus.LOSSLESS)
    }

    /**
     * Play the Discover hero: load the builtin Daily Discover playlist's
     * tracks and start playback from the top. In streaming mode every
     * member is playable; offline, only on-disk tracks are queued (the
     * player's offline gate would skip stream-only rows anyway).
     */
    fun playHero() {
        val heroPlaylistId = uiState.value.hero?.playlistId ?: return
        playMix(heroPlaylistId)
    }

    /** Play any mix playlist from the hero pager (same gate as [playHero]). */
    fun playMix(playlistId: Long) {
        viewModelScope.launch {
            val streamingOn = streamingPreference.current()
            val tracks = musicRepository.getTracksByPlaylist(playlistId).first()
                .let { if (streamingOn) it else it.filter { t -> t.filePath != null } }
            if (tracks.isNotEmpty()) playerRepository.setQueue(tracks, startIndex = 0)
        }
    }

    // ── Stash-mix actions (moved from LibraryViewModel; Library's copies are
    //    removed in Task 8 when the Library mixes shelf goes away) ────────────

    /**
     * Manually re-run the Stash Mix refresh worker for a single recipe (the
     * one whose materialized playlist is [playlistId]). Used by the "Refresh
     * this mix" action on Stash Mix cards.
     *
     * Emits snackbar lifecycle messages via [userMessages]: "Refreshing X…"
     * on enqueue, then "Refreshed X" or "Refresh failed" on the worker's
     * terminal WorkInfo state. If the playlist is tagged `STASH_MIX` but no
     * recipe back-links it (data-integrity bug — menu shouldn't have
     * appeared), logs a warning and surfaces a "not linked to a recipe"
     * message instead of silently no-opping.
     */
    fun refreshMix(playlistId: Long) {
        viewModelScope.launch {
            val recipe = recipeDao.findByPlaylistId(playlistId)
            if (recipe == null) {
                // Data-integrity bug: playlist.type == STASH_MIX but no recipe
                // back-links it. Menu shouldn't have appeared. Log + soft-fail.
                Log.w(TAG, "refreshMix: no recipe back-links playlistId=$playlistId")
                _userMessages.tryEmit("Couldn't refresh — this mix isn't linked to a recipe")
                return@launch
            }

            _userMessages.tryEmit("Refreshing ${recipe.name}…")

            // Build the request ourselves so we can capture its id for exact-
            // match WorkInfo filtering below. enqueueUniqueWork uses the same
            // unique name + REPLACE policy as StashMixRefreshWorker.enqueueOneTime,
            // mirroring lines 154-168 of that worker.
            val request = OneTimeWorkRequestBuilder<StashMixRefreshWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setInputData(workDataOf(StashMixRefreshWorker.KEY_RECIPE_ID to recipe.id))
                .build()
            val uniqueName = "${StashMixRefreshWorker.ONE_SHOT_WORK_NAME}_${recipe.id}"
            WorkManager.getInstance(context)
                .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)

            // v0.9.20: fire the full discovery pipeline. queueDiscoveryForRecipe
            // inside the mix refresh worker enqueues new Last.fm candidates into
            // discovery_queue PENDING; this trigger processes them right now (subject
            // to user's DownloadNetworkMode pref) instead of waiting up to 24h for
            // the periodic schedule. The chain in StashDiscoveryWorker's tail will
            // fire DiscoveryDownloadWorker, which fires StashMixRefreshWorker again
            // at the end — the mix re-materializes with newly-downloaded survivors
            // without the user lifting another finger.
            val mode = downloadNetworkPreference.current()
            StashDiscoveryWorker.enqueueOneTime(context, mode)

            // Observe the unique-work Flow; filter to OUR enqueued request's id
            // so historical entries from earlier taps (or earlier sessions)
            // don't fire stale "Refreshed" Toasts.
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(uniqueName)
                .firstOrNull { infos ->
                    val ours = infos.firstOrNull { it.id == request.id } ?: return@firstOrNull false
                    when (ours.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            _userMessages.tryEmit("Refreshed ${recipe.name}")
                            true
                        }
                        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                            _userMessages.tryEmit("Refresh failed — try again later")
                            true
                        }
                        else -> false
                    }
                }
        }
    }

    /**
     * Delete a user-built Stash Mix: removes the materialized playlist (via
     * the protected-playlist cascade, NOT blacklisting), then deletes the
     * backing recipe row.
     *
     * Order matters: capture the recipe BEFORE the cascade runs, because
     * `deletePlaylistWithCascade` nulls the recipe's `playlist_id` FK
     * (SET_NULL), after which `findByPlaylistId` would no longer resolve it.
     */
    fun deleteCustomMix(playlist: Playlist) {
        viewModelScope.launch {
            val recipe = recipeDao.findByPlaylistId(playlist.id) // capture BEFORE cascade nulls the FK
            musicRepository.deletePlaylistWithCascade(playlist.id, alsoBlacklist = false)
            recipe?.let { recipeDao.deleteCustom(it.id) }
            _userMessages.tryEmit("Deleted “${playlist.name}”")
        }
    }

    /**
     * Home cards carry only a [HomeMix] (id), not the full [Playlist]. Resolve
     * the playlist from the current stream and delegate to [deleteCustomMix].
     * No-ops if it's already gone.
     */
    fun deleteCustomMix(playlistId: Long) {
        viewModelScope.launch {
            val playlist = musicRepository.getAllPlaylists().first()
                .firstOrNull { it.id == playlistId } ?: return@launch
            deleteCustomMix(playlist)
        }
    }

    /**
     * If [playlistId] backs a user (non-builtin) recipe whose last refresh
     * is older than [STALE_MIX_MS], kick a refresh. Fire-and-forget from the
     * mix-card tap so opening a stale custom mix transparently freshens it.
     * No-ops for builtin recipes (those refresh on the periodic schedule)
     * and for playlists with no backing recipe.
     */
    fun refreshMixIfStale(playlistId: Long) {
        viewModelScope.launch {
            val r = recipeDao.findByPlaylistId(playlistId) ?: return@launch
            val stale = (r.lastRefreshedAt ?: 0L) < System.currentTimeMillis() - STALE_MIX_MS
            if (!r.isBuiltin && stale) refreshMix(playlistId)
        }
    }

    /**
     * Resolve the recipe id backing [playlistId] asynchronously, invoking
     * [onResult] with the id (or null if no recipe back-links it). Used by
     * the context-sheet Edit action to build the MixBuilder nav arg, since
     * the playlist→recipe mapping isn't carried synchronously in uiState.
     */
    fun editRecipeId(playlistId: Long, onResult: (Long?) -> Unit) {
        viewModelScope.launch {
            onResult(recipeDao.findByPlaylistId(playlistId)?.id)
        }
    }

    /** Hide (or unhide) a mix's playlist from the Home rails. */
    fun setHideFromHome(playlistId: Long, hidden: Boolean) {
        viewModelScope.launch { playlistDao.setHideFromHome(playlistId, hidden) }
    }

    companion object {
        private const val TAG = "HomeViewModel"
        /** A custom mix older than this (24h) is refreshed on open. */
        private const val STALE_MIX_MS = 24L * 60 * 60 * 1000

        /** SharedPreferences file backing the one-time streaming disclosure flag. */
        private const val STREAMING_DISCLOSURE_PREFS = "streaming_disclosure"
        /** Boolean flag — true once the user has dismissed the disclosure dialog. */
        private const val STREAMING_DISCLOSURE_SEEN_KEY = "streaming_disclosure_seen"
    }
}
