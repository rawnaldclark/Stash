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

    /**
     * Find a recipe by its exact name — the lookup behind app-managed
     * recipes (e.g. [com.stash.core.data.mix.LastFmRecommendationSource]'s
     * "Recommended by Last.fm"), which are find-or-create by name because
     * they're seeded lazily rather than shipped in [StashMixDefaults].
     */
    @Query("SELECT * FROM stash_mix_recipes WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): StashMixRecipeEntity?

    /** Reactive twin of [getByName] for UI surfaces that track one recipe. */
    @Query("SELECT * FROM stash_mix_recipes WHERE name = :name LIMIT 1")
    fun observeByName(name: String): Flow<StashMixRecipeEntity?>

    /**
     * Return every materialized playlist_id across builtin recipes —
     * used by the "reset builtins" migration to delete the old backing
     * playlists before we wipe and reseed the recipe table.
     */
    @Query("SELECT playlist_id FROM stash_mix_recipes WHERE is_builtin = 1 AND playlist_id IS NOT NULL")
    suspend fun getBuiltinPlaylistIds(): List<Long>

    /** Delete every builtin recipe. Paired with [getBuiltinPlaylistIds]. */
    @Query("DELETE FROM stash_mix_recipes WHERE is_builtin = 1")
    suspend fun deleteAllBuiltins(): Int

    /** Deletes a USER recipe (never a builtin). Caller deletes the materialized
     *  playlist separately (FK is SET_NULL, not CASCADE). */
    @Query("DELETE FROM stash_mix_recipes WHERE id = :id AND is_builtin = 0")
    suspend fun deleteCustom(id: Long)

    /** Built-in recipes matching [names] — used to fetch their playlist ids
     *  before retiring them (e.g. removing "Deep Cuts"/"First Listen"). */
    @Query("SELECT * FROM stash_mix_recipes WHERE is_builtin = 1 AND name IN (:names)")
    suspend fun getBuiltinsByName(names: List<String>): List<StashMixRecipeEntity>

    /** Deletes named built-in recipes (CASCADE removes their discovery_queue
     *  rows). Caller deletes the materialized playlists separately. Custom
     *  recipes with the same name are never touched. */
    @Query("DELETE FROM stash_mix_recipes WHERE is_builtin = 1 AND name IN (:names)")
    suspend fun deleteBuiltinsByName(names: List<String>): Int

    /**
     * v0.9.26 — flip `is_active` on every built-in mix recipe in one
     * shot. Used by the Stash-Mixes opt-out toggle: setting `active = 0`
     * pauses the recipe-refresh flow without destroying the user's
     * accumulated discovery state (re-enabling restores everything).
     */
    @Query("UPDATE stash_mix_recipes SET is_active = :active WHERE is_builtin = 1")
    suspend fun setActiveForBuiltins(active: Boolean): Int

    /**
     * Non-destructive tuning migration — updates an individual builtin
     * recipe's knobs without dropping its materialized playlist or
     * cascading its discovery_queue. Used when we ship a new default
     * (e.g. bumping discovery_ratio) and want existing installs to pick
     * it up without wiping the user's accumulated discovery state.
     *
     * v0.9.20: extended from 4 to 6 fields (added affinityBias + seedStrategy).
     * v0.9.40: extended to 8 fields (added moodKeysCsv + tagSampleDepth) for the
     * tag-seeded engine retune, so a single atomic UPDATE can re-point a builtin
     * onto TAG_GRAPH with a deep-cut depth.
     */
    @Query(
        """
        UPDATE stash_mix_recipes
        SET discovery_ratio = :discoveryRatio,
            freshness_window_days = :freshnessWindowDays,
            target_length = :targetLength,
            affinity_bias = :affinityBias,
            seed_strategy = :seedStrategy,
            mood_keys_csv = :moodKeysCsv,
            tag_sample_depth = :tagSampleDepth
        WHERE is_builtin = 1 AND name = :name
        """
    )
    suspend fun retuneBuiltin(
        name: String,
        discoveryRatio: Float,
        freshnessWindowDays: Int,
        targetLength: Int,
        affinityBias: Float,
        seedStrategy: String,
        moodKeysCsv: String,
        tagSampleDepth: Int,
    ): Int
}
