package com.stash.feature.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.discovery.GenreCatalog
import com.stash.core.data.discovery.HomeDiscoveryRepository
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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
     * The Discover hero: the builtin Daily Discover playlist, once it has
     * materialized (`trackCount > 0`). Emits `null` before then — that
     * feeds the cold-start placeholder (see [HomeUiState.isColdStart]).
     *
     * `getBuiltinPlaylistIds()` is suspend, so it's read once into a flow
     * and combined with the live playlists stream; the hero re-resolves
     * whenever playlists change (e.g. the discovery run fills it in).
     */
    private val heroFlow: Flow<DiscoverHeroState?> = combine(
        musicRepository.getAllPlaylists(),
        flow { emit(recipeDao.getBuiltinPlaylistIds().firstOrNull()) },
    ) { playlists, builtinId ->
        playlists
            .firstOrNull { it.id == builtinId && it.trackCount > 0 }
            ?.let { playlist ->
                DiscoverHeroState(
                    title = playlist.name,
                    subtitle = "${playlist.trackCount} tracks · updated daily",
                    artUrl = playlist.artUrl,
                    playlistId = playlist.id,
                )
            }
    }

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
     * changes ([flatMapLatest] cancels the previous fetch). Clears the rows
     * immediately on switch (empty emit) so stale content doesn't linger, then
     * fetches all three in parallel. The repository is fail-soft — a failed row
     * comes back empty and the screen hides it; discovery never blocks Home.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val discoveryFlow: Flow<DiscoveryUi> = genreFilter.flatMapLatest { label ->
        flow {
            val genreId = GenreCatalog.idFor(label)
            emit(DiscoveryUi(label, emptyList(), emptyList(), emptyList()))
            coroutineScope {
                val newReleases = async { homeDiscoveryRepository.newReleases(genreId) }
                val playlists = async { homeDiscoveryRepository.communityPlaylists(genreId) }
                val topAlbums = async { homeDiscoveryRepository.topAlbums(genreId) }
                emit(DiscoveryUi(label, newReleases.await(), topAlbums.await(), playlists.await()))
            }
        }
    }

    /** Select a genre chip; re-derives all three discovery rows. */
    fun onSelectGenre(label: String) { genreFilter.value = label }

    val uiState: StateFlow<HomeUiState> = combine(
        heroFlow,
        losslessPromptFlow,
        tipJarRepository.state,
        metadataBackfillBannerFlow,
        discoveryFlow,
    ) { hero, losslessPrompt, tipJar, metadataBackfillBanner, discovery ->
        HomeUiState(
            hero = hero,
            isLoading = false,
            losslessPrompt = losslessPrompt,
            tipJar = tipJar,
            metadataBackfillBanner = metadataBackfillBanner,
            selectedGenre = discovery.selectedGenre,
            newReleases = discovery.newReleases,
            topAlbums = discovery.topAlbums,
            playlists = discovery.playlists,
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
        viewModelScope.launch {
            val streamingOn = streamingPreference.current()
            val tracks = musicRepository.getTracksByPlaylist(heroPlaylistId).first()
                .let { if (streamingOn) it else it.filter { t -> t.filePath != null } }
            if (tracks.isNotEmpty()) playerRepository.setQueue(tracks, startIndex = 0)
        }
    }

    companion object {
        /** SharedPreferences file backing the one-time streaming disclosure flag. */
        private const val STREAMING_DISCLOSURE_PREFS = "streaming_disclosure"
        /** Boolean flag — true once the user has dismissed the disclosure dialog. */
        private const val STREAMING_DISCLOSURE_SEEN_KEY = "streaming_disclosure_seen"
    }
}
