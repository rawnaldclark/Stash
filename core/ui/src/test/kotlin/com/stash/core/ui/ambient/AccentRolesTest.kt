package com.stash.core.ui.ambient

import androidx.compose.ui.graphics.Color
import com.stash.core.ui.theme.StashPurple
import com.stash.core.ui.theme.StashPurpleDark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contrast rules of AccentRoles.kt (R1..R7), proven over a sweep of covers: every hue in 15
 * degree steps at three saturations and three lightnesses, run through the web's vote exactly as a
 * solid cover of that colour would be, plus the brand default and a set of neutrals, on all three
 * grounds.
 */
class AccentRolesTest {

    /** One 8-bit channel step: Compose stores an sRGB colour (alpha included) at that precision. */
    private val EIGHT_BIT = 1f / 255f

    private fun hex(c: Color): String = "#%02x%02x%02x".format(c.red.channel(), c.green.channel(), c.blue.channel())

    private fun solid(c: Color, n: Int = 400): IntArray {
        val argb = (0xFF shl 24) or (c.red.channel() shl 16) or (c.green.channel() shl 8) or c.blue.channel()
        return IntArray(n) { argb }
    }

    /** The sweep: 24 hues x 3 saturations x 3 lightnesses, each as the vote reads a solid cover of it. */
    private val sweep: List<Pair<String, AmbientPalette>> = buildList {
        for (h in 0 until 360 step 15) {
            for (s in listOf(0.55, 0.7, 0.9)) {
                for (l in listOf(0.45, 0.53, 0.62)) {
                    val p = ambientPaletteFromPixels(solid(hslToColor(h.toDouble(), s, l)), key = "h$h s$s l$l")
                    add("h=$h s=$s l=$l" to assertNotNullAndGet(p, "h=$h s=$s l=$l"))
                }
            }
        }
    }

    /** Neutrals: pure greys, a sepia, a cool blue-grey, very dark and very light covers. */
    private val neutrals: List<Pair<String, AmbientPalette>> = listOf(
        "grey" to Color(0xFF808080),
        "black" to Color(0xFF050505),
        "white" to Color(0xFFF8F8F8),
        "sepia" to Color(0xFF8C8070),
        "blue-grey" to Color(0xFF707884),
    ).map { (name, c) -> name to assertNotNullAndGet(readCoverPalette(solid(c), key = name), name) }

    private fun assertNotNullAndGet(p: AmbientPalette?, what: String): AmbientPalette {
        assertNotNull(what, p)
        return p!!
    }

    private fun minContrast(c: Color, over: List<Color>) = over.minOf { contrastRatio(c, it) }

    /** R1, R3, R4, R5 for any palette; R2 for all but the brand on dark (see the brand test). */
    private fun assertRules(what: String, roles: AccentRoles, ground: Ground, checkOnAccent: Boolean = true) {
        val bases = ground.bases
        // R1: the accent stands out from every base.
        assertTrue("$what R1 accent ${hex(roles.accent)} ${minContrast(roles.accent, bases)}", minContrast(roles.accent, bases) >= 3.0)
        // R2: its ink is one of the two inks and reads on it.
        assertTrue("$what R2 ink is white or #1a0b2e", roles.onAccent == Color.White || roles.onAccent == InkOnBright)
        if (checkOnAccent) {
            assertTrue(
                "$what R2 onAccent on ${hex(roles.accent)}: ${contrastRatio(roles.onAccent, roles.accent)}",
                contrastRatio(roles.onAccent, roles.accent) >= 4.5,
            )
        }
        // R3: the text ink reads on every base and on the wash over it.
        val washed = bases.map { compositeOver(roles.wash, it) }
        assertTrue("$what R3 ink ${hex(roles.ink)}", minContrast(roles.ink, bases + washed) >= 4.5)
        // R4: the ground's own text still reads on the wash.
        assertTrue("$what R4 text on wash", minContrast(ground.text, washed) >= 7.0)
        assertTrue("$what R4 secondary on wash", minContrast(ground.textSecondary, washed) >= 4.5)
        // Compose keeps an sRGB colour in 8 bits a channel, alpha included: allow half a step.
        assertTrue("$what R4 wash is translucent", roles.wash.alpha in 0f..ground.washAlpha + EIGHT_BIT)
        // R5: the page tint keeps the background's text readable; none on AMOLED.
        val tinted = compositeOver(roles.pageTint, ground.background)
        assertTrue("$what R5 text on page tint", contrastRatio(ground.text, tinted) >= 7.0)
        assertTrue("$what R5 secondary on page tint", contrastRatio(ground.textSecondary, tinted) >= 4.5)
        if (ground == Ground.AMOLED) assertEquals("$what R5 AMOLED has no page tint", 0f, roles.pageTint.alpha)
    }

    @Test fun `every swept cover meets R1 to R6 on every ground, and R7 is rare`() {
        val report = StringBuilder()
        for (ground in Ground.entries) {
            var fallbacks = 0
            var maxShift = 0.0
            for ((what, p) in sweep) {
                val direct = p.coverRolesOrNull(ground)
                val roles = p.roles(ground)
                assertRules("$what on $ground", roles, ground)
                if (direct == null) {
                    fallbacks++
                    // R7: the fallback is a neutral accent.
                    assertTrue("$what R7 neutral", roles.accent.toHsl()[1] <= 0.08)
                } else {
                    // R6: a cover accent keeps its colour.
                    assertTrue("$what R6 saturation ${roles.accent.toHsl()[1]}", roles.accent.toHsl()[1] >= 0.45)
                    // ...and its hue (8-bit rounding aside).
                    val dh = Math.abs(roles.accent.toHsl()[0] - p.vib.toHsl()[0]).let { minOf(it, 360 - it) }
                    assertTrue("$what hue kept ($dh)", dh <= 6.0)
                    maxShift = maxOf(maxShift, Math.abs(roles.accent.toHsl()[2] - p.vib.toHsl()[2]))
                }
            }
            val rate = fallbacks.toDouble() / sweep.size
            report.append("$ground: R7 fallback ${"%.1f".format(rate * 100)} % ($fallbacks/${sweep.size}), max accent shift ${"%.2f".format(maxShift)}\n")
            assertTrue("$ground R7 rate $rate", rate <= 0.05)
        }
        println(report)
    }

    @Test fun `neutral covers wear a grey accent, pale on dark and dark on light`() {
        for ((what, p) in neutrals) {
            assertEquals(what, PaletteKind.NEUTRAL, p.kind)
            for (ground in Ground.entries) {
                val roles = p.roles(ground)
                assertRules("$what on $ground", roles, ground)
                val hsl = roles.accent.toHsl()
                assertTrue("$what $ground R6 grey ${hsl[1]}", hsl[1] <= 0.08)
                listOf(roles.ink, roles.dom, roles.vib, roles.mut).forEach {
                    assertTrue("$what $ground grey role ${hex(it)}", it.toHsl()[1] <= 0.08)
                }
                if (ground.isLight) {
                    assertEquals("$what light L", 0.30, hsl[2], 0.03)
                    assertEquals("$what light ink", Color.White, roles.onAccent)
                } else {
                    assertEquals("$what dark L", 0.80, hsl[2], 0.03)
                    assertEquals("$what dark ink", InkOnBright, roles.onAccent)
                }
            }
        }
    }

    @Test fun `a cover no move can make safe falls back to neutral roles (R7)`() {
        // A navy too dark and too blue to reach 3:1 on the dark bases within 0.34 lightness: at
        // its fixed hue and saturation even the lightest allowed step stays under the luminance R1 needs.
        val base = ambientPaletteFromPixels(solid(Color(0xFF2840C8)))!!
        val navy = base.copy(vib = Color(0xFF0A0A40), key = "navy")
        assertEquals(null, navy.coverRolesOrNull(Ground.DARK))
        val roles = navy.roles(Ground.DARK)
        assertRules("navy fallback", roles, Ground.DARK)
        val hsl = roles.accent.toHsl()
        assertTrue("neutral accent (${hsl[1]})", hsl[1] <= 0.08)
        assertEquals("the neutral's pale grey", 0.80, hsl[2], 0.03)
        assertEquals(InkOnBright, roles.onAccent)
        // Its ambient keeps the cover's colours.
        assertEquals(navy.dom, roles.dom)
        assertEquals(navy.vib, roles.vib)
        assertEquals(navy.mut, roles.mut)
    }

    @Test fun `the brand wears the design system's primary and onPrimary`() {
        val dark = AmbientPalette.Default.roles(Ground.DARK)
        assertEquals(StashPurple, dark.accent)
        assertEquals(Color.White, dark.onAccent)
        assertEquals(StashPurple, AmbientPalette.Default.roles(Ground.AMOLED).accent)
        val light = AmbientPalette.Default.roles(Ground.LIGHT)
        assertEquals(StashPurpleDark, light.accent)
        assertEquals(Color.White, light.onAccent)
        // The ambient keeps the web's default purple, unlifted on light.
        for (ground in Ground.entries) {
            val r = brandRoles(ground)
            assertEquals(AmbientPalette.Default.dom, r.dom)
            assertEquals(AmbientPalette.Default.vib, r.vib)
            assertEquals(AmbientPalette.Default.mut, r.mut)
            // White on #8b5cf6 is the theme's own pairing (4.23:1, a 24dp glyph); light holds 4.5.
            assertRules("brand on $ground", r, ground, checkOnAccent = ground == Ground.LIGHT)
            assertTrue("brand onAccent on $ground", contrastRatio(r.onAccent, r.accent) >= 4.2)
        }
    }

    @Test fun `a cover that already holds its contrast keeps its own colour`() {
        // Orange #e2733a: 3:1 on every dark base and 4.5:1 under #1a0b2e as it is.
        val orange = ambientPaletteFromPixels(solid(Color(0xFFE2733A)))!!
        val roles = orange.roles(Ground.DARK)
        assertEquals("#e2733a", hex(roles.accent))
        assertEquals(InkOnBright, roles.onAccent)
        assertEquals(orange.dom, roles.dom)
    }

    @Test fun `a yellow on the light ground is darkened, stays yellow, and its ink reads`() {
        val yellow = ambientPaletteFromPixels(solid(Color(0xFFF0DC28)))!!
        val roles = yellow.roles(Ground.LIGHT)
        val before = yellow.vib.toHsl()
        val after = roles.accent.toHsl()
        assertTrue("darker", after[2] < before[2] - 0.1)
        assertEquals("same hue", before[0], after[0], 6.0)
        assertTrue("3:1 on white", contrastRatio(roles.accent, Color.White) >= 3.0)
        assertTrue("ink reads", contrastRatio(roles.onAccent, roles.accent) >= 4.5)
    }

    @Test fun `roles are lifted for the light ground as the ambient always was`() {
        val teal = ambientPaletteFromPixels(solid(Color(0xFF14A394)))!!
        val lifted = teal.forScheme(light = true)
        val roles = teal.roles(Ground.LIGHT)
        assertEquals(lifted.dom, roles.dom)
        assertEquals(lifted.mut, roles.mut)
        assertEquals(teal.dom, teal.roles(Ground.DARK).dom)
    }

    @Test fun `lerpRoles runs end to end and stops at the ends`() {
        val a = brandRoles(Ground.DARK)
        val b = ambientPaletteFromPixels(solid(Color(0xFFE2733A)))!!.roles(Ground.DARK)
        assertEquals(a, lerpRoles(a, b, 0f))
        assertEquals(b, lerpRoles(a, b, 1f))
        assertEquals(b, lerpRoles(a, b, 1.2f)) // an overshooting easing never builds a bad colour
        val mid = lerpRoles(a, b, 0.5f)
        assertEquals((a.accent.red + b.accent.red) / 2, mid.accent.red, EIGHT_BIT) // 8-bit channels
    }
}
