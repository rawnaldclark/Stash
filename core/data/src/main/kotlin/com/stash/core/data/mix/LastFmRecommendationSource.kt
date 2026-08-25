package com.stash.core.data.mix

import android.content.Context
import android.util.Log
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.lastfm.LastFmSessionPreference
import com.stash.core.data.lastfm.LastFmSourcePreference
import com.stash.core.data.prefs.StashMixPreference
import com.stash.core.data.sync.workers.StashMixRefreshWorker
import com.stash.core.model.PlaylistType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UI state for the Last.fm source surface (issue #255). Everything the
 * Sync-tab card and its manage screen render, derived reactively.
 *
 * @property connected   A Last.fm session exists (user completed web auth).
 * @property enabled     The user hasn't turned Recommendations off.
 * @property trackCount  Tracks currently in the materialized playlist;
 *                       null until the first refresh has created it.
 * @property lastRefreshedAt  Epoch-millis of the last successful recipe
 *                       refresh; null never-refreshed.
 */
data class LastFmRecommendationState(
    val connected: Boolean = false,
    val enabled: Boolean = true,
    val trackCount: Int? = null,
    val lastRefreshedAt: Long? = null,
)

/**
 * Treats Last.fm as a sync **source**, not just a scrobble destination.
 *
 * Last.fm has no public playlist API, so "importing from Last.fm" means
 * something different here than it does for Spotify/YouTube: a dedicated,
 * app-managed Stash Mix recipe — **"Recommended by Last.fm"** — whose slots
 * fill entirely with tracks Last.fm recommends (`track.getSimilar` seeded
 * from the user's recent plays and top tracks), matched against YouTube
 * Music / Spotify and downloaded/streamed through the existing discovery
 * pipeline ([com.stash.core.data.sync.workers.StashMixRefreshWorker] +
 * [com.stash.core.data.sync.workers.StashDiscoveryWorker]). No new worker,
 * table, or fetch path — the feature rides machinery that already handles
 * candidate generation, library/skip filtering, cross-mix dedup, and
 * stream-only stubs.
 *
 * Lifecycle:
 *  - The recipe is NOT shipped in [StashMixDefaults] — users without
 *    Last.fm must not accumulate an eternally-empty pure-discovery mix.
 *    It's find-or-created lazily by [ensureRecipe].
 *  - [reconcile] runs at app startup and after every Last.fm connect /
 *    disconnect: the recipe (and its materialized playlist) are active iff
 *    a session exists AND the user hasn't opted out via
 *    [LastFmSourcePreference]. Disabling hides the playlist rather than
 *    deleting it — re-enabling restores the accumulated track list, the
 *    same contract as the Stash-Mixes master opt-out.
 *
 * Deliberately `isBuiltin = false`: Home's Discover hero resolves "the"
 * builtin playlist as `builtinPlaylistIds.first()`, and Library/Home rails
 * exclude builtin ids by set membership. Managing our row outside the
 * builtin machinery keeps those assumptions valid while behaving identically
 * to a user-built mix on every screen.
 */
@Singleton
class LastFmRecommendationSource @Inject constructor(
    private val recipeDao: StashMixRecipeDao,
    private val playlistDao: PlaylistDao,
    private val sessionPreference: LastFmSessionPreference,
    private val sourcePreference: LastFmSourcePreference,
    private val stashMixPreference: StashMixPreference,
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val TAG = "LastFmRecSource"

        /** Exact recipe (and therefore playlist) name. */
        const val RECIPE_NAME = "Recommended by Last.fm"

        /**
         * Pure recommendations: every slot comes from Last.fm candidates,
         * nothing from the local library — that's what makes it a *source*
         * rather than another mix flavor.
         */
        const val DISCOVERY_RATIO = 1.0f

        /** Rotating window size; matches Daily Discover's scale. */
        const val TARGET_LENGTH = 40

        /**
         * Seed strategy: similar TRACKS to what the user actually plays —
         * the closest thing to Last.fm's own recommendation shape among the
         * supported strategies (ARTIST_SIMILAR is Daily Discover's lane).
         */
        const val SEED_STRATEGY = "TRACK_SIMILAR"
    }

    /**
     * Reactive state for the Sync-tab Last.fm card + manage screen. Combines
     * session, preference, and (when the recipe exists) its materialized
     * playlist's live track count.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeState(): Flow<LastFmRecommendationState> =
        combine(
            sessionPreference.session,
            sourcePreference.recommendationsEnabled,
            recipeDao.observeByName(RECIPE_NAME),
        ) { session, enabled, recipe ->
            StateInputs(session != null, enabled, recipe?.playlistId, recipe?.lastRefreshedAt)
        }.flatMapLatest { inputs ->
            if (inputs.playlistId == null) {
                flowOf(
                    LastFmRecommendationState(
                        connected = inputs.connected,
                        enabled = inputs.enabled,
                        trackCount = null,
                        lastRefreshedAt = inputs.lastRefreshedAt,
                    ),
                )
            } else {
                playlistDao.getByIdFlow(inputs.playlistId).map { playlist ->
                    LastFmRecommendationState(
                        connected = inputs.connected,
                        enabled = inputs.enabled,
                        // Hidden (is_active=0) counts as not-materialized for
                        // display purposes — the user turned it off, so the
                        // card shouldn't quote a stale number.
                        trackCount = playlist?.takeIf { it.isActive }?.trackCount,
                        lastRefreshedAt = inputs.lastRefreshedAt,
                    )
                }
            }
        }

    /**
     * Bring persisted state in line with reality. Idempotent; called from
     * app startup and after Last.fm connect/disconnect.
     */
    suspend fun reconcile() {
        ensureRecipe()
        val connected = sessionPreference.session.first() != null
        val enabled = sourcePreference.recommendationsEnabled.first()
        applyActivation(active = connected && enabled, kickRefresh = false)
        Log.i(TAG, "reconcile: connected=$connected enabled=$enabled")
    }

    /**
     * User flipped the Recommendations toggle on the Sync surface. Persists
     * the choice and applies it immediately; enabling kicks a one-shot
     * single-recipe refresh so the playlist starts building without waiting
     * for the daily cycle (skipped when the Stash-Mixes master switch is
     * off — that opt-out owns all mix scheduling).
     */
    suspend fun setRecommendationsEnabled(enabled: Boolean) {
        sourcePreference.setRecommendationsEnabled(enabled)
        applyActivation(active = enabled && sessionPreference.session.first() != null, kickRefresh = enabled)
        Log.i(TAG, "setRecommendationsEnabled($enabled)")
    }

    /** Ensure the managed recipe row exists; returns its id. */
    internal suspend fun ensureRecipe(): Long {
        recipeDao.getByName(RECIPE_NAME)?.let { return it.id }
        val id = recipeDao.insert(managedRecipe())
        Log.i(TAG, "created recipe '$RECIPE_NAME' (id=$id)")
        return id
    }

    /**
     * Apply the desired activation state to the recipe AND its materialized
     * playlist. Deactivation never deletes: the recipe keeps its playlistId
     * and discovery backlog, the playlist keeps its rows — flipping back on
     * restores everything in place.
     */
    private suspend fun applyActivation(active: Boolean, kickRefresh: Boolean) {
        val recipe = recipeDao.getByName(RECIPE_NAME) ?: return
        // The Stash-Mixes master switch owns every mix surface, and this
        // recipe materializes a STASH_MIX like any other. Gate HERE — the one
        // place activation is applied — so no caller can light it up while the
        // user has mixes switched off. Re-enabling mixes restores it on the
        // next reconcile() (app start, or a Last.fm connect/toggle).
        val effectiveActive = active && stashMixPreference.current()
        if (recipe.isActive != effectiveActive) {
            recipeDao.setActive(recipe.id, effectiveActive)
        }
        val playlistId = recipe.playlistId
        if (playlistId != null) {
            val playlist = playlistDao.getById(playlistId)
            // Only touch STASH_MIX rows: if the user somehow deleted the
            // playlist, getById returns null and there's nothing to hide.
            if (playlist != null && playlist.type == PlaylistType.STASH_MIX && playlist.isActive != effectiveActive) {
                playlistDao.setActiveById(playlistId, effectiveActive)
            }
        }
        if (effectiveActive && kickRefresh) {
            // Single-recipe one-shot: builds/rebuilds only this mix. REPLACE
            // policy coalesces rapid double-taps.
            runCatching { StashMixRefreshWorker.enqueueOneTime(context, recipe.id) }
                .onFailure { Log.w(TAG, "refresh enqueue failed; recipe still activated", it) }
        }
    }

    /** The managed recipe definition. Inactive until reconciled into existence. */
    internal fun managedRecipe() = StashMixRecipeEntity(
        name = RECIPE_NAME,
        description = "Tracks Last.fm recommends, based on what you listen to.",
        affinityBias = 0f,
        freshnessWindowDays = 0,
        discoveryRatio = DISCOVERY_RATIO,
        targetLength = TARGET_LENGTH,
        seedStrategy = SEED_STRATEGY,
        isBuiltin = false,
        isActive = false,
    )

    /** Narrow carrier so the combine lambda stays single-expression. */
    private data class StateInputs(
        val connected: Boolean,
        val enabled: Boolean,
        val playlistId: Long?,
        val lastRefreshedAt: Long?,
    )
}
