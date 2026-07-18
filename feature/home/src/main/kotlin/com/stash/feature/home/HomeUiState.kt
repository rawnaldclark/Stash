package com.stash.feature.home

import com.stash.core.data.discovery.Genre
import com.stash.core.data.discovery.GenreCatalog
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.PlaylistSummary
import com.stash.feature.home.banner.MetadataBackfillBannerState

/**
 * UI state for the redesigned Home screen — a pure *discovery* surface.
 *
 * Library content (Stash/daily mixes, liked songs, recently-added, custom
 * playlists) used to live here too; it has been relocated to the Library
 * screen. Home now carries only the Discover hero plus the persistent
 * "chrome" (tip jar pill, lossless prompt, metadata-backfill banner).
 *
 * Note: sync-status, per-source connection booleans, and `hasEverSynced`
 * used to live here too — they powered the SyncStatusCard at the top of
 * the Home screen. The card was relocated to the Sync tab; its data
 * plumbing moved with it to `:feature:sync`'s SyncViewModel/SyncUiState.
 */
data class HomeUiState(
    /** The Discover hero. Null until the Daily Discover playlist materializes. */
    val hero: DiscoverHeroState? = null,

    val isLoading: Boolean = true,
    /**
     * Non-null when the user has not enabled lossless AND has not
     * dismissed the Home banner. Drives the "Try lossless audio"
     * banner. A singleton sentinel — its mere presence signals "show the banner".
     */
    val losslessPrompt: LosslessPromptState? = null,

    /**
     * v0.9.13: live tip-jar state. Drives the Home pill (compact
     * `$X/$Y` indicator) and the Tip Jar bottom sheet. Sourced from
     * [com.stash.core.data.tipjar.TipJarRepository] which fetches
     * the public supporters JSON. Defaults to [com.stash.core.data.tipjar.TipJarState.EMPTY]
     * before the repo's first emission so the Home pill never crashes
     * on null.
     */
    val tipJar: com.stash.core.data.tipjar.TipJarState =
        com.stash.core.data.tipjar.TipJarState.EMPTY,

    /**
     * v0.9.35: state of the "re-tagging library" banner. Hidden in
     * the steady state — only renders while [com.stash.data.download.backfill.MetadataBackfillWorker]
     * is actively processing rows, and for a 2-second "Done" pulse
     * after completion.
     */
    val metadataBackfillBanner: MetadataBackfillBannerState =
        MetadataBackfillBannerState.Hidden,

    // ── Qobuz discovery rows (genre-filterable) ────────────────────────
    /** Genre chips; the first ("All") is the no-filter default. */
    val genres: List<Genre> = GenreCatalog.GENRES,
    /** Currently-selected chip label; drives the three rows below. */
    val selectedGenre: String = "All",
    val newReleases: List<AlbumSummary> = emptyList(),
    val topAlbums: List<AlbumSummary> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),

    // ── Home mix rails (derived from playlists + recipes) ──────────────
    val madeForYou: List<HomeMix> = emptyList(),
    val radios: List<HomeMix> = emptyList(),
    val moodDecades: List<HomeMix> = emptyList(),
    val yourMixes: List<HomeMix> = emptyList(),
    val customMixPlaylistIds: Set<Long> = emptySet(),
) {
    /** True before the Discover hero has materialized — drives the cold-start placeholder. */
    val isColdStart: Boolean get() = hero == null
}

/** A mix as a Home rail card. buildState only meaningful for STASH_MIX. */
data class HomeMix(
    val id: Long, val title: String, val artUrl: String?,
    val source: com.stash.core.model.MusicSource,
    val buildState: com.stash.core.data.mix.MixBuildState = com.stash.core.data.mix.MixBuildState.READY,
    /** Hero-pager subtitle ("N tracks") for the Your-mix pages. */
    val trackCount: Int = 0,
)

/**
 * Sentinel for the "Try lossless audio" Home banner. Singleton
 * (data object) because the banner copy is static — its mere
 * presence in the UI state signals "show the banner."
 */
data object LosslessPromptState

/**
 * Sort options for the Home Playlists grid. Deliberately duplicated from
 * the Library module's `SortOrder` to avoid a cross-module dependency for
 * three enum values. If a third surface ever needs the same options, lift
 * to a shared module rather than crossing the feature:library boundary.
 *
 * ponytail: retained — HomeViewModel/HomeScreen still reference it; they're
 * rewritten in the later Home-redesign tasks, which will remove this.
 */
enum class PlaylistSortOrder { RECENT, ALPHABETICAL, MOST_PLAYED }
