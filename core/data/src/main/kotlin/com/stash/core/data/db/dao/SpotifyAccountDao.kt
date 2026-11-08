package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stash.core.data.db.entity.SpotifyAccountEntity
import kotlinx.coroutines.flow.Flow

/**
 * The extra Spotify accounts (plan 2026-11-04 §2.1); the main account has no row here.
 *
 * Deleting a row deletes only the row: the account's sign-in (token store) and its playlist rows
 * are the caller's to deal with (hide its rows, forget its token), and nothing cascades.
 * Every switch has its own setter, so two toggles in a row can never undo each other the way a
 * read-modify-write of the whole row could.
 */
@Dao
interface SpotifyAccountDao {

    /**
     * Adds an extra account and returns its row id. Throws on a [SpotifyAccountEntity.userId]
     * already added (unique): look it up with [getByUserId] first and sign that one in again.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(account: SpotifyAccountEntity): Long

    @Query("SELECT * FROM spotify_accounts WHERE id = :id")
    suspend fun getById(id: Long): SpotifyAccountEntity?

    /** The extra account with this Spotify user id (in `SpotifyProfile.accountKey` form), or null. */
    @Query("SELECT * FROM spotify_accounts WHERE user_id = :userId")
    suspend fun getByUserId(userId: String): SpotifyAccountEntity?

    /** Every extra account, in the order they were added. */
    @Query("SELECT * FROM spotify_accounts ORDER BY added_at ASC, id ASC")
    suspend fun getAll(): List<SpotifyAccountEntity>

    /** [getAll], kept current. */
    @Query("SELECT * FROM spotify_accounts ORDER BY added_at ASC, id ASC")
    fun observeAll(): Flow<List<SpotifyAccountEntity>>

    /** How many extras there are (the add flow caps them). */
    @Query("SELECT COUNT(*) FROM spotify_accounts")
    suspend fun count(): Int

    /** Renames an extra. Returns the rows changed: 0 when it is gone. */
    @Query("UPDATE spotify_accounts SET label = :label WHERE id = :id")
    suspend fun updateLabel(id: Long, label: String): Int

    @Query("UPDATE spotify_accounts SET sync_playlists = :on WHERE id = :id")
    suspend fun setSyncPlaylists(id: Long, on: Boolean): Int

    @Query("UPDATE spotify_accounts SET sync_liked = :on WHERE id = :id")
    suspend fun setSyncLiked(id: Long, on: Boolean): Int

    @Query("UPDATE spotify_accounts SET sync_albums = :on WHERE id = :id")
    suspend fun setSyncAlbums(id: Long, on: Boolean): Int

    @Query("UPDATE spotify_accounts SET sync_mixes = :on WHERE id = :id")
    suspend fun setSyncMixes(id: Long, on: Boolean): Int

    /**
     * Records when the extra's sign-in stopped working ([at], epoch millis), or that it works
     * again (null, after signing in again).
     */
    @Query("UPDATE spotify_accounts SET signed_out_at = :at WHERE id = :id")
    suspend fun updateSignedOutAt(id: Long, at: Long?): Int

    /** Deletes the row only (see the class note). Returns the rows deleted. */
    @Query("DELETE FROM spotify_accounts WHERE id = :id")
    suspend fun delete(id: Long): Int
}
