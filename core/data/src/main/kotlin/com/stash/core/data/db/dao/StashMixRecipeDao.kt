package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.stash.core.data.db.entity.StashMixRecipeEntity
import kotlinx.coroutines.flow.Flow

/**
 * CRUD + query access for Stash Mix recipes — the declarative definitions
 * the [com.stash.core.data.mix.MixGenerator] runs against the library.
 */
@Dao
interface StashMixRecipeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recipe: StashMixRecipeEntity): Long

    @Update
    suspend fun update(recipe: StashMixRecipeEntity)

    @Query("SELECT * FROM stash_mix_recipes WHERE id = :id")
    suspend fun getById(id: Long): StashMixRecipeEntity?

    /**
     * All recipes ordered `is_builtin DESC` (built-ins first) then by name.
     * UI list + scheduler iteration both need stable ordering.
     */
    @Query(
        """
        SELECT * FROM stash_mix_recipes
        ORDER BY is_builtin DESC, name ASC
        """
    )
    fun observeAll(): Flow<List<StashMixRecipeEntity>>

    /** Only active recipes — what the refresh worker iterates. */
    @Query("SELECT * FROM stash_mix_recipes WHERE is_active = 1 ORDER BY name ASC")
    suspend fun getActive(): List<StashMixRecipeEntity>

    /** Toggle active/inactive (drives the Sync-screen mix-preference switches). */
    @Query("UPDATE stash_mix_recipes SET is_active = :active WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean)

    /** Record the materialized playlist's id on first refresh so subsequent refreshes update in place. */
    @Query("UPDATE stash_mix_recipes SET playlist_id = :playlistId WHERE id = :id")
    suspend fun setPlaylistId(id: Long, playlistId: Long)

    /** Stamp refresh time on successful run. */
    @Query("UPDATE stash_mix_recipes SET last_refreshed_at = :at WHERE id = :id")
    suspend fun setLastRefreshedAt(id: Long, at: Long)

    /**
     * Builtin seed check — the recipe seeder calls this on first launch to
     * decide whether to insert defaults. Once seeded, users may edit the
     * builtin rows but we never re-seed over them.
     */
    @Query("SELECT COUNT(*) FROM stash_mix_recipes WHERE is_builtin = 1")
    suspend fun countBuiltins(): Int

    /** Look up a recipe by the playlist it materialized into. */
    @Query("SELECT * FROM stash_mix_recipes WHERE playlist_id = :playlistId LIMIT 1")
    suspend fun findByPlaylistId(playlistId: Long): StashMixRecipeEntity?
}
