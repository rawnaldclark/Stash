package com.stash.core.model

/** One-shot target for navigating to a (remote) album page. */
data class AlbumNavTarget(
    val albumId: String,
    val name: String,
    val artUrl: String?,
    val artistName: String,
)