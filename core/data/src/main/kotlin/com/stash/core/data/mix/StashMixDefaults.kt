package com.stash.core.data.mix

import android.content.SharedPreferences
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.db.entity.StashMixRecipeEntity

/**
 * Ships Stash's built-in mix recipes. As of v0.9.40 there is a single
 * built-in flagship — **Daily Discover** (ARTIST_SIMILAR, 100% recommendations:
 * all 40 places are finds, none are the user's own downloads) — and everything
 * else is user-built via the Mix Builder.
 * The earlier "Deep Cuts" and "First Listen" builtins were retired:
 * `StashApplication.maybeRemoveRetiredBuiltinMixes` deletes them (and their
 * playlists) from existing installs; fresh installs never seed them.
 *
 * Only seeds when [StashMixRecipeDao.countBuiltins] is zero, so users
 * don't get defaults re-inserted every launch. Upgrades go through
 * `StashApplication.maybeRetuneStashMixes`, which calls [retuneBuiltins] to
 * non-destructively update surviving built-in rows in place.
 */
object StashMixDefaults {

    /** The app's one-time-migration flags file (SharedPreferences), where the tuning flag lives. */
    const val TUNING_PREFS_NAME = "stash_migrations"

    /**
     * Which built-in tuning this install has, in [TUNING_PREFS_NAME]. `StashApplication.maybeRetuneStashMixes`
     * runs [retuneBuiltins] while it is below the current version, then records that version.
     */
    const val TUNING_VERSION_KEY = "stash_mix_recipe_tuning_version"

    /**
     * Asks the next cold start to retune the built-ins again. A replace-restore swaps in the backup's recipe rows (one
     * made before tuning v3 holds an 85% Daily Discover), while this flag, in SharedPreferences that a restore leaves
     * alone, still says the current tuning ran. Written to disk at once, since a restore restarts the app straight after.
     */
    fun rearmRetune(prefs: SharedPreferences) {
        prefs.edit().remove(TUNING_VERSION_KEY).commit()
    }

    suspend fun seedIfNeeded(dao: StashMixRecipeDao) {
        if (dao.countBuiltins() > 0) return
        ALL.forEach { dao.insert(it) }
    }

    /**
     * Rewrites each surviving built-in row's tunables to the values in [ALL], matched by name
     * ([StashMixRecipeDao.retuneBuiltin]): no wipe, so its playlist and discovery backlog stay.
     * Built-ins can't be edited by the user, so this overwrites nothing they chose. The caller
     * gates it to once per tuning version and rebuilds the mixes after it.
     *
     * @return the number of rows updated.
     */
    suspend fun retuneBuiltins(dao: StashMixRecipeDao): Int {
        var updated = 0
        for (recipe in ALL.filter { it.isBuiltin }) {
            updated += dao.retuneBuiltin(
                name = recipe.name,
                discoveryRatio = recipe.discoveryRatio,
                freshnessWindowDays = recipe.freshnessWindowDays,
                targetLength = recipe.targetLength,
                affinityBias = recipe.affinityBias,
                seedStrategy = recipe.seedStrategy,
                moodKeysCsv = recipe.moodKeysCsv,
                tagSampleDepth = recipe.tagSampleDepth,
            )
        }
        return updated
    }

    val ALL: List<StashMixRecipeEntity> = listOf(
        StashMixRecipeEntity(
            name = "Daily Discover",
            description = "Fresh finds via similar artists, new every day.",
            affinityBias = 0.0f,
            freshnessWindowDays = 7,
            discoveryRatio = 1.0f,
            targetLength = 40,
            seedStrategy = "ARTIST_SIMILAR",
            isBuiltin = true,
        ),
        // v0.9.40: "Deep Cuts" and "First Listen" retired — Daily Discover is
        // the sole built-in; users build the rest via the Mix Builder.
        // Existing installs get the two removed by
        // StashApplication.maybeRemoveRetiredBuiltinMixes().
    )
}
