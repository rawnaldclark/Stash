package com.stash.core.media.service

import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.library.canonicalWordsForSave
import com.stash.core.data.library.findReusableByWords
import com.stash.core.model.isScannedTrackId
import javax.inject.Inject

/**
 * The library row a heart on the media notification lands on ([StashPlaybackService]'s
 * like toggle). The `mediaId` may be a v0.9.30 streaming-engine synthetic id
 * (`videoId.hashCode().toLong()`) that doesn't exist in `tracks`; passing it to a FK-bearing write
 * crashes the service (#105). Mirrors the upsert pattern in `MusicRepositoryImpl.ensureTrackPersisted`
 * and `SearchDownloadCoordinator.upsertSearchTrack`.
 */
class LikeTargetResolver @Inject constructor(
    private val trackDao: TrackDao,
    private val blocklistGuard: BlocklistGuard,
) {

    /**
     * @return the real `tracks.id` to like; never a synthetic id. A stream with no row gets a new
     *   streamable row.
     * @throws IllegalStateException when no identity is recoverable: neither a real candidate id nor
     *   a youtubeId / title / artist that maps to one.
     */
    suspend fun resolve(
        candidateId: Long,
        youtubeId: String?,
        title: String,
        artist: String,
        album: String,
        albumArtist: String,
    ): Long {
        // An All Songs file has no row and must never get one (nor adopt a library song of the
        // same name by its words): the caller's failure path rolls the heart back.
        check(!isScannedTrackId(candidateId)) { "An All Songs file can't be liked" }

        // Real Room PK already? Cheap exit.
        if (candidateId > 0L && trackDao.getById(candidateId) != null) return candidateId

        // YouTube id is the most reliable secondary identity.
        if (!youtubeId.isNullOrBlank()) {
            trackDao.findByYoutubeId(youtubeId)?.let { return it.id }
        }

        // By words only under SameSongRule: a like on "Song (Live)" or on another
        // video of the song must not land on the library's "Song".
        trackDao.findReusableByWords(
            title = title,
            artist = artist,
            youtubeId = youtubeId,
            spotifyUri = null,
            forDownload = false,
        )?.let { return it.id }

        check(title.isNotBlank() && artist.isNotBlank()) {
            "Cannot resolve track id for like: no youtubeId and no title/artist metadata"
        }

        // The user's own save: under a blocked loose key it keeps the strict words, so the liked
        // song isn't hidden at once and swept at the next launch (canonicalWordsForSave).
        val (cTitle, cArtist) = blocklistGuard.canonicalWordsForSave(title, artist)
        return trackDao.insert(
            TrackEntity(
                title = title,
                artist = artist,
                album = album,
                albumArtist = albumArtist,
                youtubeId = youtubeId,
                canonicalTitle = cTitle,
                canonicalArtist = cArtist,
                source = com.stash.core.model.MusicSource.YOUTUBE,
                isStreamable = true,
                isDownloaded = false,
            )
        )
    }
}
