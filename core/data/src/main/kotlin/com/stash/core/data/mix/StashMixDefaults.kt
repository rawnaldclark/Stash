package com.stash.core.data.mix

import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.db.entity.StashMixRecipeEntity

/**
 * Ships the opinionated default Stash Mixes on first launch. Mirrors the
 * "daily mix" spots Spotify/YT have but recipe-driven, so every choice is
 * visible and editable (in a future mix-builder UI). Each recipe here is
 * deliberately tuned around signals we have from day one — affinity +
 * tags + listening freshness.
 *
 * Only seeds when [StashMixRecipeDao.countBuiltins] is zero, so users
 * don't get defaults re-inserted every launch.
 */
object StashMixDefaults {

    suspend fun seedIfNeeded(dao: StashMixRecipeDao) {
        if (dao.countBuiltins() > 0) return
        ALL.forEach { dao.insert(it) }
    }

    /**
     * The seven recipes the Stash Mixes launch ships with. Kept here (not
     * in SQL migration) so it's easy to tweak wording / tags without
     * dragging a schema bump along.
     */
    val ALL: List<StashMixRecipeEntity> = listOf(
        StashMixRecipeEntity(
            name = "Rediscovery",
            description = "Tracks you loved but haven't played recently.",
            includeTagsCsv = "",
            affinityBias = -0.5f,
            freshnessWindowDays = 60,
            discoveryRatio = 0f,
            targetLength = 50,
            isBuiltin = true,
        ),
        StashMixRecipeEntity(
            name = "Heavy Rotation",
            description = "Your most-played tracks right now.",
            includeTagsCsv = "",
            affinityBias = 0.8f,
            freshnessWindowDays = 0,
            discoveryRatio = 0f,
            targetLength = 50,
            isBuiltin = true,
        ),
        StashMixRecipeEntity(
            name = "Focus",
            description = "Instrumental, ambient, post-rock \u2014 for work and reading.",
            includeTagsCsv = "ambient,instrumental,post-rock,electronic,chillout,downtempo",
            affinityBias = 0.1f,
            freshnessWindowDays = 14,
            discoveryRatio = 0.2f,
            targetLength = 50,
            isBuiltin = true,
        ),
        StashMixRecipeEntity(
            name = "Late Night",
            description = "Downtempo, trip-hop, atmospheric. Low-key vibes.",
            includeTagsCsv = "downtempo,trip-hop,jazz,atmospheric,chillout,lo-fi",
            affinityBias = 0.1f,
            freshnessWindowDays = 14,
            discoveryRatio = 0.2f,
            targetLength = 50,
            isBuiltin = true,
        ),
        StashMixRecipeEntity(
            name = "Throwback",
            description = "Older favorites from your library.",
            includeTagsCsv = "",
            eraEndYear = 2015,
            affinityBias = 0.3f,
            freshnessWindowDays = 14,
            discoveryRatio = 0.1f,
            targetLength = 50,
            isBuiltin = true,
        ),
        StashMixRecipeEntity(
            name = "Road Trip",
            description = "Rock, alt, indie, americana \u2014 open-road energy.",
            includeTagsCsv = "rock,alternative,indie,americana,country,folk,indie rock",
            affinityBias = 0.4f,
            freshnessWindowDays = 14,
            discoveryRatio = 0.2f,
            targetLength = 50,
            isBuiltin = true,
        ),
        StashMixRecipeEntity(
            name = "New Arrivals",
            description = "Recently added to your library.",
            includeTagsCsv = "",
            affinityBias = 0f,
            freshnessWindowDays = 0,
            discoveryRatio = 0f,
            targetLength = 30,
            isBuiltin = true,
        ),
    )
}
