package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.stash.core.data.db.entity.SourceAccountEntity
import com.stash.core.model.MusicSource
import kotlinx.coroutines.flow.Flow

/**
 * Data-access object for [SourceAccountEntity].
 *
 * Manages music service account metadata (Spotify, YouTube, etc.).
 */
@Dao
interface SourceAccountDao {

    // ── Insert / Update ─────────────────────────────────────────────────

    /** Insert a source account, replacing on conflict (e.g. same source). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: SourceAccountEntity): Long

    /** Update an existing source account entity. */
    @Update
    suspend fun update(account: SourceAccountEntity)

    // ── Queries ─────────────────────────────────────────────────────────

    /** Find an account by its music source type. */
    @Query("SELECT * FROM source_accounts WHERE source = :source LIMIT 1")
    suspend fun getBySource(source: MusicSource): SourceAccountEntity?

    /** Reactive stream of all source accounts. */
    @Query("SELECT * FROM source_accounts")
    fun getAll(): Flow<List<SourceAccountEntity>>

    /** Reactive stream of only connected (authenticated) accounts. */
    @Query("SELECT * FROM source_accounts WHERE is_connected = 1")
    fun getConnected(): Flow<List<SourceAccountEntity>>

    // ── Status update ───────────────────────────────────────────────────

    /**
     * Update the connection status for a source account.
     *
     * @param id          Row ID of the account.
     * @param isConnected Whether the account is currently connected.
     * @param connectedAt Epoch-millis timestamp of connection, or null if disconnecting.
     */
    @Query(
        """
        UPDATE source_accounts
        SET is_connected = :isConnected,
            connected_at = :connectedAt
        WHERE id = :id
        """
    )
    suspend fun updateConnectionStatus(id: Long, isConnected: Boolean, connectedAt: Long?)
}
