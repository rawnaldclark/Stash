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
    val artUrl: String? = null,
    val syncEnabled: Boolean = true,
    val tracks: List<Track> = emptyList(),
)

enum class PlaylistType {
    DAILY_MIX,
    LIKED_SONGS,
    CUSTOM
}
