package com.stash.core.model

/**
 * What a queue is playing FROM — shown in Now Playing ("Playing from Chill
 * Vibes") and persisted alongside the queue so a resumed session remembers
 * its context, not just its tracks.
 */
sealed class PlaybackSource {
    data object Library : PlaybackSource()
    data class Liked(val filter: String) : PlaybackSource()
    data class Playlist(val playlistId: Long, val name: String) : PlaybackSource()
    data class Artist(val name: String) : PlaybackSource()
    data class Album(val name: String, val artist: String) : PlaybackSource()
    data class Radio(val label: String) : PlaybackSource()
    data object Search : PlaybackSource()

    /**
     * A queue continued from a linked browser ("Pick up where you left off", link-sync spec §8.3): the other
     * device's "Playing from" label, as text only. Display only; the queue is this device's own from then on.
     */
    data class Handoff(val label: String) : PlaybackSource()
    data object Unknown : PlaybackSource()

    /** Pipe-delimited encoding for DataStore. Names containing "|" will
     *  mis-split on restore — acceptable for a display-only label. */
    fun serialize(): String = when (this) {
        is Library -> "LIBRARY"
        is Liked -> "LIKED|$filter"
        is Playlist -> "PLAYLIST|$playlistId|$name"
        is Artist -> "ARTIST|$name"
        is Album -> "ALBUM|$name|$artist"
        is Radio -> "RADIO|$label"
        is Search -> "SEARCH"
        is Handoff -> "HANDOFF|$label"
        is Unknown -> "UNKNOWN"
    }

    /** User-facing label for the Now Playing "Playing from" line. */
    val displayLabel: String
        get() = when (this) {
            is Library -> "Library"
            is Liked -> when (filter) {
                "ALL" -> "Liked Songs"
                else -> "Liked Songs (${filter.lowercase().replaceFirstChar { it.uppercase() }})"
            }
            is Playlist -> name
            is Artist -> name
            is Album -> name
            is Radio -> "$label Radio"
            is Search -> "Search"
            is Handoff -> label
            is Unknown -> ""
        }

    companion object {
        fun deserialize(raw: String?): PlaybackSource {
            if (raw.isNullOrBlank()) return Unknown
            val parts = raw.split("|")
            return when (parts.getOrNull(0)) {
                "LIBRARY" -> Library
                "LIKED" -> Liked(parts.getOrElse(1) { "ALL" })
                "PLAYLIST" -> {
                    val id = parts.getOrNull(1)?.toLongOrNull() ?: return Unknown
                    Playlist(id, parts.getOrElse(2) { "" })
                }
                "ARTIST" -> Artist(parts.getOrElse(1) { "" })
                "ALBUM" -> Album(parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
                "RADIO" -> Radio(parts.getOrElse(1) { "" })
                "SEARCH" -> Search
                "HANDOFF" -> Handoff(parts.drop(1).joinToString("|"))
                else -> Unknown
            }
        }
    }
}
