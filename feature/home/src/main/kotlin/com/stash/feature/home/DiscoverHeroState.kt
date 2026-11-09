package com.stash.feature.home

/** The Discover hero (materialized Daily Discover playlist). Null when not yet available. */
data class DiscoverHeroState(
    val title: String,
    val subtitle: String,
    val artUrl: String?,
    val playlistId: Long,
    /**
     * Offline mode: Daily Discover is all streamed recommendations, so the card shows the
     * "Switch to Online" hint in place of a track count it can't play.
     */
    val offline: Boolean = false,
)
