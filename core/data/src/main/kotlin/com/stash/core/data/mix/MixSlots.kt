package com.stash.core.data.mix

import com.stash.core.data.db.entity.StashMixRecipeEntity
import kotlin.math.roundToInt

/**
 * How a recipe's [StashMixRecipeEntity.targetLength] places split between recommendations ([discovery]) and the
 * user's own downloads ([library]). Recommendations get `targetLength × discoveryRatio`, rounded; the library gets
 * the rest, so the two always add up to the target: 40 × 0.85 → 34 + 6, 50 × 0.5 → 25 + 25, ratio 1.0 → 40 + 0.
 *
 * Before this, [MixGenerator] truncated the library share on its own: `1f − 0.85f` is 0.14999998, × 40 = 5.99…,
 * which `toInt()` made 5, so an 85% mix held 34 + 5 = 39 songs. [MixGenerator.generate] (library places) and
 * `StashMixRefreshWorker.materializeMix` (recommendation places) both read their number from here.
 */
data class MixSlots(val discovery: Int, val library: Int) {
    companion object {
        fun of(recipe: StashMixRecipeEntity): MixSlots {
            val target = recipe.targetLength.coerceAtLeast(0)
            val discovery = (target * recipe.discoveryRatio.coerceIn(0f, 1f)).roundToInt().coerceIn(0, target)
            return MixSlots(discovery = discovery, library = target - discovery)
        }
    }
}
