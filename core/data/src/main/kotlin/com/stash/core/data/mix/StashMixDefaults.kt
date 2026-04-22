package com.stash.core.data.mix

import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.db.entity.StashMixRecipeEntity

/**
 * Ships Stash's built-in mix recipes. The launch surface is deliberately
 * narrow — a single "Stash Discover" mix — so we can validate the engine
 * (scoring, refresh, discovery) on one flagship experience before adding
 * more recipes. Future releases will expand this list and eventually
 * let users author their own via a mix-builder UI.
 *
 * Only seeds when [StashMixRecipeDao.countBuiltins] is zero, so users
 * don't get defaults re-inserted every launch. Upgrades from pre-0.4.1
 * installs that already have multiple builtins go through
 * `StashApplication.maybeReseedStashMixes` which clears the old set
 * first and then runs this seed.
 */
object StashMixDefaults {

    suspend fun seedIfNeeded(dao: StashMixRecipeDao) {
        if (dao.countBuiltins() > 0) return
        ALL.forEach { dao.insert(it) }
    }

    /**
     * The single launch recipe.
     *
     * Intentional tuning choices:
     *  - No tag filter — works on every library size, including ones
     *    that haven't finished tag enrichment. Once enrichment is deep,
     *    we can layer tag-biased variants on top.
     *  - Slight positive affinity bias — kept for when we add mixes that
     *    do include library slots; Discover itself is 100% discovery so
     *    affinity only influences the seed selection upstream.
     *  - 14-day freshness window so you don't hear the same tracks the
     *    mix surfaced yesterday.
     *  - **1.0 discovery ratio** (raised from 0.6 on 2026-04-22). Stash
     *    Discover is *pure* Last.fm recommendations — no library tracks
     *    mixed in. The user's top artists still seed the similar-artist
     *    query (see [StashMixRefreshWorker.queueDiscoveryForRecipe]), and
     *    fresh-install users with no listening history fall back to
     *    library-top-artists so the mix populates on day one instead of
     *    waiting for scrobbles to accumulate. `StashMixRefreshWorker`'s
     *    re-link pass is what actually fills the playlist — downloaded
     *    Discovery candidates get appended after the (empty) library
     *    slots on every refresh.
     */
    val ALL: List<StashMixRecipeEntity> = listOf(
        StashMixRecipeEntity(
            name = "Stash Discover",
            description = "Fresh tracks Last.fm thinks you'll like — 100% new.",
            includeTagsCsv = "",
            affinityBias = 0.2f,
            freshnessWindowDays = 14,
            discoveryRatio = 1.0f,
            targetLength = 50,
            isBuiltin = true,
        ),
    )
}
