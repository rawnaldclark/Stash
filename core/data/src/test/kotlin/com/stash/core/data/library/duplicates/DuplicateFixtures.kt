package com.stash.core.data.library.duplicates

import com.stash.core.common.TrackCanonical
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DiscoveryQueueEntity
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.db.entity.TrackSkipEventEntity
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import java.time.Instant

/**
 * Rows for the duplicate tests, on a real in-memory [StashDatabase]. The song is the owner's
 * "Starting Over" by LSD and the Search for God (plan 2026-11-01 §2 (b)): a downloaded, liked row
 * with a video id, and id-less red stubs of it that Stash Mix discovery filed.
 */
internal class DuplicateFixtures(private val db: StashDatabase) {

    suspend fun song(
        title: String = TITLE,
        artist: String = ARTIST,
        lengthMs: Long = 304_000L,
        youtubeId: String? = null,
        spotifyUri: String? = null,
        source: MusicSource = if (spotifyUri != null) MusicSource.SPOTIFY else MusicSource.YOUTUBE,
        file: String? = null,
        format: String = "opus",
        plays: Int = 0,
        likedAt: Long? = null,
        explicit: Boolean? = null,
        flagged: Boolean = false,
        isrc: String? = null,
    ): Long = db.trackDao().insert(
        TrackEntity(
            title = title, artist = artist, durationMs = lengthMs, youtubeId = youtubeId, spotifyUri = spotifyUri,
            source = source, filePath = file, isDownloaded = file != null, fileFormat = format, playCount = plays,
            stashLikedAt = likedAt, explicit = explicit, matchFlagged = flagged, isrc = isrc, isStreamable = true,
            canonicalTitle = TrackCanonical.title(title), canonicalArtist = TrackCanonical.artist(artist),
        ),
    )

    /** An id-less discovery stub: no video id, no Spotify id, red. */
    suspend fun stub(title: String = TITLE, lengthMs: Long = 304_000L, plays: Int = 0, explicit: Boolean? = null, file: String? = null): Long =
        song(title = title, lengthMs = lengthMs, plays = plays, explicit = explicit, file = file)

    suspend fun playlist(name: String, type: PlaylistType = PlaylistType.CUSTOM, source: MusicSource = MusicSource.BOTH): Long =
        db.playlistDao().insert(PlaylistEntity(name = name, source = source, sourceId = "test_$name", type = type))

    suspend fun member(playlistId: Long, trackId: Long, position: Int, userAdded: Boolean = false, removed: Boolean = false) {
        db.playlistDao().insertCrossRef(
            PlaylistTrackCrossRef(
                playlistId = playlistId, trackId = trackId, position = position, locallyAdded = userAdded,
                addedAt = Instant.ofEpochMilli(1_000L + position), removedAt = if (removed) Instant.ofEpochMilli(5_000L) else null,
            ),
        )
    }

    suspend fun play(trackId: Long, at: Long = 10_000L) {
        db.listeningEventDao().insert(ListeningEventEntity(trackId = trackId, startedAt = at))
    }

    suspend fun skip(trackId: Long, at: Long = 11_000L) {
        db.trackSkipEventDao().insert(TrackSkipEventEntity(trackId = trackId, skippedAt = at, positionMs = 1_000L))
    }

    suspend fun download(trackId: Long, status: DownloadStatus = DownloadStatus.PENDING, userRequested: Boolean = true): Long =
        db.downloadQueueDao().insert(DownloadQueueEntity(trackId = trackId, status = status, userRequested = userRequested))

    /** A Stash Mix recipe and a discovery row in [status] pointing at [trackId]: the stub is in that mix's pool. */
    suspend fun discovered(trackId: Long, status: String = DiscoveryQueueEntity.STATUS_DONE): Long {
        val recipe = db.stashMixRecipeDao().insert(StashMixRecipeEntity(name = "Daily Discover"))
        return db.discoveryQueueDao().insertIfNew(
            DiscoveryQueueEntity(recipeId = recipe, artist = ARTIST, title = TITLE, seedArtist = "Slowdive", status = status, trackId = trackId),
        )
    }

    fun count(sql: String): Int = db.openHelper.readableDatabase.query(sql).use { c -> c.moveToFirst(); c.getInt(0) }

    suspend fun row(id: Long): TrackEntity? = db.trackDao().getById(id)

    suspend fun link(playlistId: Long, trackId: Long): PlaylistTrackCrossRef? = db.playlistDao().getCrossRef(playlistId, trackId)

    companion object {
        const val TITLE = "Starting Over"
        const val ARTIST = "LSD and the Search for God"
    }
}
