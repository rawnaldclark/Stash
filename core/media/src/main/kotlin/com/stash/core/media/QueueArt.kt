package com.stash.core.media

import com.stash.core.model.Track

/** Covers learnt for queued tracks ([PlayerRepository.learnArtwork]). */
internal object QueueArt {
    /** [tracks] with each one [arts] names (by id) and that has no cover of its own given that cover; the others as they were. */
    fun fill(tracks: List<Track>, arts: Map<Long, String>): List<Track> {
        if (arts.isEmpty()) return tracks
        return tracks.map { t ->
            val art = arts[t.id]
            if (art != null && t.albumArtUrl.isNullOrBlank() && t.albumArtPath.isNullOrBlank()) t.copy(albumArtUrl = art) else t
        }
    }
}
