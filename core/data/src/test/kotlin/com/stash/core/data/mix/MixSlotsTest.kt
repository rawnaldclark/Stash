package com.stash.core.data.mix

import com.stash.core.data.db.entity.StashMixRecipeEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [MixSlots]: a recipe's places split between recommendations and downloads, always adding up to its
 * target length. The library share used to be truncated on its own (`1f - 0.85f` = 0.14999998), so an
 * 85% mix of 40 held 34 + 5 = 39 songs.
 */
class MixSlotsTest {

    @Test fun `40 at 85 percent is 34 recommendations and 6 downloads`() {
        assertEquals(MixSlots(discovery = 34, library = 6), MixSlots.of(recipe(40, 0.85f)))
    }

    @Test fun `50 at half and half is 25 and 25`() {
        assertEquals(MixSlots(discovery = 25, library = 25), MixSlots.of(recipe(50, 0.5f)))
    }

    @Test fun `all recommendations leaves no download places`() {
        assertEquals(MixSlots(discovery = 40, library = 0), MixSlots.of(recipe(40, 1.0f)))
    }

    @Test fun `library only leaves no recommendation places`() {
        assertEquals(MixSlots(discovery = 0, library = 40), MixSlots.of(recipe(40, 0f)))
    }

    @Test fun `every slider step adds up to the target`() {
        for (length in listOf(10, 25, 40, 50, 100)) {
            for (step in 0..20) {
                val slots = MixSlots.of(recipe(length, step * 0.05f))
                assertEquals("length $length, ratio ${step * 0.05f}", length, slots.discovery + slots.library)
            }
        }
    }

    private fun recipe(targetLength: Int, discoveryRatio: Float) =
        StashMixRecipeEntity(name = "Test", targetLength = targetLength, discoveryRatio = discoveryRatio)
}
