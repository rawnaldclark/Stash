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
     *  - Slight positive affinity bias so the mix leans into what you
     *    already like without turning into straight Heavy Rotation.
     *  - 14-day freshness window so you don't hear the same tracks the
     *    mix surfaced yesterday.
     *  - 60% discovery ratio (raised from 0.25 on 2026-04-21 after user
     *    testing — library-only slots dominated the mix and the user
     *    "recognized" most tracks from their Spotify/YouTube imports).
     *    30 discovery slots + 20 library anchors means the mix stays
     *    mostly-fresh while keeping stylistic familiarity.
     */
    val ALL: List<StashMixRecipeEntity> = listOf(
        StashMixRecipeEntity(
            name = "Stash Discover",
            description = "Your favorites blended with new tracks Stash thinks you'll like.",
            includeTagsCsv = "",
            affinityBias = 0.2f,
            freshnessWindowDays = 14,
            discoveryRatio = 0.6f,
            targetLength = 50,
            isBuiltin = true,
        ),
    )
}
