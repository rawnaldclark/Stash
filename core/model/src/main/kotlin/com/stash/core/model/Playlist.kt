package com.stash.core.model

data class Playlist(
    val id: Long = 0,
    val name: String,
    val source: MusicSource,
    val sourceId: String = "",
    val type: PlaylistType = PlaylistType.CUSTOM,
    val mixNumber: Int? = null,
    val lastSynced: Long? = null,
    val trackCount: Int = 0,
    val isActive: Boolean = true,
    /**
     * Primary cover URL. For daily mixes that have a 2-tile mosaic stored
     * in the DB as `"url1|url2"`, this is the first (primary) tile so
     * single-image callers (`AsyncImage(model = playlist.artUrl)`) continue
     * to work unchanged. Mosaic-aware callers should read [artTileUrls].
     */
    val artUrl: String? = null,
    /**
     * All cover tile URLs (length 0..2). For daily mixes this is the first
     * 2 unique album covers from the current tracklist and updates every
     * sync; for other playlists it mirrors [artUrl] (at most one entry).
     */
    val artTileUrls: List<String> = emptyList(),
    val syncEnabled: Boolean = false,
    val tracks: List<Track> = emptyList(),
)

enum class PlaylistType {
    DAILY_MIX,
    LIKED_SONGS,
    CUSTOM
}
