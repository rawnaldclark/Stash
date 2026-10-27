package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stash.core.data.db.entity.TrackBlocklistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackBlocklistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: TrackBlocklistEntity)

    @Query("DELETE FROM track_blocklist WHERE canonical_key = :canonicalKey")
    suspend fun deleteByKey(canonicalKey: String)

    @Query("SELECT * FROM track_blocklist WHERE canonical_key = :canonicalKey LIMIT 1")
    suspend fun findByKey(canonicalKey: String): TrackBlocklistEntity?

    @Query("SELECT * FROM track_blocklist WHERE spotify_uri = :spotifyUri LIMIT 1")
    suspend fun findBySpotifyUri(spotifyUri: String): TrackBlocklistEntity?

    @Query("SELECT * FROM track_blocklist WHERE youtube_id = :youtubeId LIMIT 1")
    suspend fun findByYoutubeId(youtubeId: String): TrackBlocklistEntity?

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM track_blocklist
            WHERE canonical_key = :canonicalKey
               OR (spotify_uri IS NOT NULL AND spotify_uri = :spotifyUri)
               OR (youtube_id  IS NOT NULL AND youtube_id  = :youtubeId)
        )
        """
    )
    suspend fun isBlocked(
        canonicalKey: String,
        spotifyUri: String?,
        youtubeId: String?,
    ): Boolean

    /** [isBlocked] by the ids alone: a song whose canonical words are blank names no key (its words would block every such title). */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM track_blocklist
            WHERE (spotify_uri IS NOT NULL AND spotify_uri = :spotifyUri)
               OR (youtube_id  IS NOT NULL AND youtube_id  = :youtubeId)
        )
        """
    )
    suspend fun isIdBlocked(spotifyUri: String?, youtubeId: String?): Boolean

    @Query("SELECT canonical_key FROM track_blocklist")
    suspend fun getAllKeys(): List<String>

    @Query("SELECT * FROM track_blocklist ORDER BY blocked_at DESC")
    fun observeAll(): Flow<List<TrackBlocklistEntity>>

    @Query("SELECT COUNT(*) FROM track_blocklist")
    fun observeCount(): Flow<Int>
}
