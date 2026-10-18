package com.stash.core.ui.ambient

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The one crossfade: it never jumps, whatever the moment a new song's colours arrive. */
class AccentAnimatorTest {

    private fun cover(argb: Long): AccentRoles {
        val px = IntArray(400) { argb.toInt() }
        return ambientPaletteFromPixels(px)!!.roles(Ground.DARK)
    }

    private val brand = brandRoles(Ground.DARK)
    private val orange = cover(0xFFE2733A)
    private val teal = cover(0xFF14A394)
    private val blue = cover(0xFF2840C8)

    @Test fun `starts settled on its first roles`() {
        val a = AccentAnimator(brand)
        assertEquals(brand, a.displayed())
        assertEquals(brand.accent, a.role { it.accent })
    }

    @Test fun `retarget starts from what is displayed, mid-crossfade included`() {
        val a = AccentAnimator(brand)
        assertTrue(a.retarget(orange))
        assertEquals("nothing moves at the retarget", brand, a.displayed())
        a.mix = 0.4f
        val shown = a.displayed()
        assertTrue(a.retarget(teal))
        assertEquals("the interrupted crossfade's colours are the new start", shown, a.displayed())
        assertEquals(shown, a.from)
        assertEquals(teal, a.to)
        assertEquals(0f, a.mix)
        a.mix = 1f
        assertEquals(teal, a.displayed())
    }

    @Test fun `retarget to where it is already heading changes nothing`() {
        val a = AccentAnimator(brand)
        a.retarget(orange)
        a.mix = 0.5f
        val shown = a.displayed()
        assertFalse(a.retarget(orange))
        assertEquals(0.5f, a.mix)
        assertEquals(shown, a.displayed())
    }

    @Test fun `a lean toward a neighbour is folded into the next start`() {
        val a = AccentAnimator(orange)
        a.neighbour = teal
        a.blend = 0.3f
        val leaning = a.displayed()
        assertEquals(mixSrgb(orange.accent, teal.accent, 0.3f), leaning.accent)
        assertTrue(a.retarget(teal))
        assertEquals("no jump when the swipe commits", leaning, a.displayed())
        assertNull(a.neighbour)
        assertEquals(0f, a.blend)
    }

    @Test fun `role reads agree with the whole snapshot`() {
        val a = AccentAnimator(brand)
        a.retarget(blue)
        a.mix = 0.37f
        a.neighbour = orange
        a.blend = 0.2f
        val all = a.displayed()
        assertEquals(all.accent, a.role { it.accent })
        assertEquals(all.onAccent, a.role { it.onAccent })
        assertEquals(all.ink, a.role { it.ink })
        assertEquals(all.wash, a.role { it.wash })
        assertEquals(all.pageTint, a.role { it.pageTint })
        assertEquals(all.dom, a.role { it.dom })
        assertEquals(all.vib, a.role { it.vib })
        assertEquals(all.mut, a.role { it.mut })
    }

    @Test fun `colour is continuous across any run of retargets`() {
        val targets = listOf(brand, orange, teal, blue)
        val rnd = Random(7)
        val a = AccentAnimator(brand)
        var previous = a.displayed()
        repeat(500) {
            // The crossfade advances a little (as frames would), sometimes all the way...
            val mixBefore = a.mix
            a.mix = (a.mix + rnd.nextFloat() * 0.3f).coerceAtMost(1f)
            val advanced = a.displayed()
            // ...and each frame moves a channel by no more than the mix step it was given (plus one
            // 8-bit step: Compose stores sRGB colours at that precision).
            val step = a.mix - mixBefore
            assertTrue("frame step bounded", maxDelta(previous.accent, advanced.accent) <= step + 1f / 255f)
            assertTrue("frame step bounded", maxDelta(previous.dom, advanced.dom) <= step + 1f / 255f)
            // A new song arrives at an arbitrary moment: nothing visibly moves at that instant.
            val before = a.displayed()
            a.retarget(targets[rnd.nextInt(targets.size)])
            assertEquals(before, a.displayed())
            previous = a.displayed()
        }
    }

    @Test fun `snapTo settles at once`() {
        val a = AccentAnimator(brand)
        a.retarget(orange)
        a.mix = 0.2f
        a.snapTo(teal)
        assertEquals(teal, a.displayed())
        assertEquals(1f, a.mix)
    }

    private fun maxDelta(a: Color, b: Color) =
        maxOf(Math.abs(a.red - b.red), Math.abs(a.green - b.green), Math.abs(a.blue - b.blue))
}
