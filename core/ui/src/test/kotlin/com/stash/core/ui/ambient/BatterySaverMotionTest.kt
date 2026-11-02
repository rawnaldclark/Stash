package com.stash.core.ui.ambient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule [rememberReducedMotion] applies: motion stands still with the system's "Remove
 * animations" OR Android's Battery Saver, and moves only when neither is on.
 */
class BatterySaverMotionTest {

    @Test
    fun `motion moves only when neither Remove animations nor Battery Saver is on`() {
        assertFalse(motionStill(removeAnimations = false, batterySaverOn = false))
    }

    @Test
    fun `Battery Saver alone stills motion`() {
        assertTrue(motionStill(removeAnimations = false, batterySaverOn = true))
    }

    @Test
    fun `Remove animations alone stills motion, as before`() {
        assertTrue(motionStill(removeAnimations = true, batterySaverOn = false))
        assertTrue(motionStill(removeAnimations = true, batterySaverOn = true))
    }
}
