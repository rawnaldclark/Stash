package com.stash.feature.home

/** The Discover hero (materialized Daily Discover playlist). Null when not yet available. */
data class DiscoverHeroState(
    val title: String,
    val subtitle: String,
    val artUrl: String?,
    val playlistId: Long,
)
