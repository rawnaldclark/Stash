package com.stash.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.stash.core.ui.ambient.AmbientPalette
import com.stash.core.ui.ambient.CONTAINER_MIN_CONTRAST
import com.stash.core.ui.ambient.Ground
import com.stash.core.ui.ambient.PaletteKind
import com.stash.core.ui.ambient.ThemeAccent
import com.stash.core.ui.ambient.contrastRatio
import com.stash.core.ui.ambient.readCoverPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The whole app's Material scheme follows the playing album (D9, 2026-11-01): proven over six real
 * album colours on the dark and pure-black themes (and light, phase 2's ground, for safety). Every
 * text role holds WCAG AA (4.5:1) on every surface the scheme declares, every "on" colour on its fill,
 * pure black stays black, and the idle scheme (nothing playing, or Album colours on Neutral) is
 * exactly the theme's base scheme, with no purple and no cyan left in it.
 */
class ColorSchemeTest {

    private fun solid(argb: Long, n: Int = 400) = IntArray(n) { argb.toInt() }

    /** The six album colours of the brief, read as the app reads a cover. */
    private val albums: List<Pair<String, AmbientPalette>> = listOf(
        "red" to 0xFFD32F2FL,
        "yellow" to 0xFFF5D90AL,
        "pastel" to 0xFFF4C2C2L,
        "near-black" to 0xFF0B0B0EL,
        "near-white" to 0xFFF7F7F2L,
        "saturated blue" to 0xFF1F3BFFL,
    ).map { (name, argb) ->
        val p = readCoverPalette(solid(argb), key = name)
        assertNotNull(name, p)
        name to p!!
    }

    private val bases = listOf(
        Triple("dark", StashDarkColorScheme, Ground.DARK),
        Triple("pure black", StashAmoledColorScheme, Ground.AMOLED),
        Triple("light", StashLightColorScheme, Ground.LIGHT),
    )

    private fun ColorScheme.surfaces() = listOf(
        background, surface, surfaceVariant, surfaceDim, surfaceBright,
        surfaceContainerLowest, surfaceContainerLow, surfaceContainer,
        surfaceContainerHigh, surfaceContainerHighest,
    )

    /** Every one of Material 3's 48 colour roles, named. */
    private fun ColorScheme.all(): List<Pair<String, Color>> = listOf(
        "primary" to primary, "onPrimary" to onPrimary, "primaryContainer" to primaryContainer,
        "onPrimaryContainer" to onPrimaryContainer, "inversePrimary" to inversePrimary,
        "secondary" to secondary, "onSecondary" to onSecondary, "secondaryContainer" to secondaryContainer,
        "onSecondaryContainer" to onSecondaryContainer, "tertiary" to tertiary, "onTertiary" to onTertiary,
        "tertiaryContainer" to tertiaryContainer, "onTertiaryContainer" to onTertiaryContainer,
        "background" to background, "onBackground" to onBackground, "surface" to surface,
        "onSurface" to onSurface, "surfaceVariant" to surfaceVariant, "onSurfaceVariant" to onSurfaceVariant,
        "surfaceTint" to surfaceTint, "inverseSurface" to inverseSurface, "inverseOnSurface" to inverseOnSurface,
        "error" to error, "onError" to onError, "errorContainer" to errorContainer,
        "onErrorContainer" to onErrorContainer, "outline" to outline, "outlineVariant" to outlineVariant,
        "scrim" to scrim, "surfaceBright" to surfaceBright, "surfaceContainer" to surfaceContainer,
        "surfaceContainerHigh" to surfaceContainerHigh, "surfaceContainerHighest" to surfaceContainerHighest,
        "surfaceContainerLow" to surfaceContainerLow, "surfaceContainerLowest" to surfaceContainerLowest,
        "surfaceDim" to surfaceDim, "primaryFixed" to primaryFixed, "primaryFixedDim" to primaryFixedDim,
        "onPrimaryFixed" to onPrimaryFixed, "onPrimaryFixedVariant" to onPrimaryFixedVariant,
        "secondaryFixed" to secondaryFixed, "secondaryFixedDim" to secondaryFixedDim,
        "onSecondaryFixed" to onSecondaryFixed, "onSecondaryFixedVariant" to onSecondaryFixedVariant,
        "tertiaryFixed" to tertiaryFixed, "tertiaryFixedDim" to tertiaryFixedDim,
        "onTertiaryFixed" to onTertiaryFixed, "onTertiaryFixedVariant" to onTertiaryFixedVariant,
    )

    /** The accent families: what the album colour may change. */
    private val accentRoles = setOf(
        "primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "inversePrimary",
        "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
        "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer", "surfaceTint",
        "primaryFixed", "primaryFixedDim", "onPrimaryFixed", "onPrimaryFixedVariant",
        "secondaryFixed", "secondaryFixedDim", "onSecondaryFixed", "onSecondaryFixedVariant",
        "tertiaryFixed", "tertiaryFixedDim", "onTertiaryFixed", "onTertiaryFixedVariant",
    )

    /** Hue (degrees), HSL saturation and lightness of a colour's 8-bit value. */
    private fun hsl(c: Color): DoubleArray {
        val r = c.red.toDouble()
        val g = c.green.toDouble()
        val b = c.blue.toDouble()
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2
        val d = mx - mn
        if (d < 1e-9) return doubleArrayOf(0.0, 0.0, l)
        val s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            r -> ((g - b) / d + (if (g < b) 6 else 0)) * 60
            g -> ((b - r) / d + 2) * 60
            else -> ((r - g) / d + 4) * 60
        }
        return doubleArrayOf(h, s, l)
    }

    private fun hex(c: Color) = "#%08x".format(c.value.toLong() ushr 32)

    /** A visibly purple or violet colour (StashPurple and its family, M3's baseline lavender). */
    private fun isPurple(c: Color): Boolean {
        if (c.alpha < 0.05f) return false
        val (h, s, l) = hsl(c)
        return h in 250.0..320.0 && s > 0.25 && l in 0.08..0.97
    }

    /** A visibly cyan colour (the old second brand colour). */
    private fun isCyan(c: Color): Boolean {
        val (h, s, _) = hsl(c)
        return h in 170.0..200.0 && s > 0.25
    }

    private fun assertReadable(what: String, scheme: ColorScheme) {
        val surfaces = scheme.surfaces()
        fun minOn(c: Color) = surfaces.minOf { contrastRatio(c, it) }
        fun check(label: String, ratio: Double, floor: Double) =
            assertTrue("$what: $label ${"%.2f".format(ratio)} < $floor", ratio >= floor)

        // Text in the accent, on every surface (a TextButton on a dialog, a label on a sheet).
        check("primary ${hex(scheme.primary)} on surfaces", minOn(scheme.primary), 4.5)
        check("secondary ${hex(scheme.secondary)} on surfaces", minOn(scheme.secondary), 4.5)
        check("tertiary on surfaces", minOn(scheme.tertiary), 4.5)
        // Ink on a filled accent (Button, Switch thumb, Checkbox mark).
        check("onPrimary on primary", contrastRatio(scheme.onPrimary, scheme.primary), 4.5)
        check("onSecondary on secondary", contrastRatio(scheme.onSecondary, scheme.secondary), 4.5)
        check("onTertiary on tertiary", contrastRatio(scheme.onTertiary, scheme.tertiary), 4.5)
        // A container (a selected chip or segment, a FAB): solid, its text at 4.5:1 (the page's text too),
        // and standing out from every page ground it sits on, so selected never looks fainter than an
        // unselected outline (review of #792: the old wash was ~1.2:1).
        val pageGrounds = listOf(
            scheme.background, scheme.surface, scheme.surfaceContainerLowest, scheme.surfaceContainerLow,
            scheme.surfaceContainer, scheme.surfaceContainerHigh,
        )
        for ((name, fill, on) in listOf(
            Triple("primaryContainer", scheme.primaryContainer, scheme.onPrimaryContainer),
            Triple("secondaryContainer", scheme.secondaryContainer, scheme.onSecondaryContainer),
            Triple("tertiaryContainer", scheme.tertiaryContainer, scheme.onTertiaryContainer),
        )) {
            assertEquals("$what: $name is solid", 1f, fill.alpha)
            check("on$name on $name", contrastRatio(on, fill), 4.5)
            check("onSurface on $name", contrastRatio(scheme.onSurface, fill), 4.5)
            check("$name ${hex(fill)} against the page", pageGrounds.minOf { contrastRatio(fill, it) }, CONTAINER_MIN_CONTRAST)
        }
        // A snackbar's action on the inverse surface.
        check("inversePrimary on inverseSurface", contrastRatio(scheme.inversePrimary, scheme.inverseSurface), 4.5)
        check("onPrimaryFixed on primaryFixed", contrastRatio(scheme.onPrimaryFixed, scheme.primaryFixed), 4.5)
    }

    @Test fun `six real album colours keep every text role readable, dark and pure black`() {
        val report = StringBuilder()
        for ((ground, base, g) in bases) {
            for ((album, palette) in albums) {
                val scheme = ThemeAccent(palette).scheme(base, g)
                assertReadable("$album on $ground", scheme)
                report.append(
                    "%-14s %-10s primary %s on bg %.1f:1, onPrimary %.1f:1, secondary %s %.1f:1, container %s %.1f:1 on bg, its text %.1f:1\n".format(
                        album, ground, hex(scheme.primary), contrastRatio(scheme.primary, scheme.background),
                        contrastRatio(scheme.onPrimary, scheme.primary), hex(scheme.secondary),
                        contrastRatio(scheme.secondary, scheme.background), hex(scheme.secondaryContainer),
                        contrastRatio(scheme.secondaryContainer, scheme.background),
                        contrastRatio(scheme.onSecondaryContainer, scheme.secondaryContainer),
                    ),
                )
            }
        }
        println(report)
    }

    @Test fun `a sweep of cover colours keeps every rule on every theme`() {
        // 24 hues x 3 saturations x 5 lightnesses of solid covers, plus greys: what the six above
        // stand for, so a rule that only holds for them is caught.
        val covers = buildList {
            for (h in 0 until 360 step 15) for (s in listOf(0.35f, 0.65f, 0.95f)) for (l in listOf(0.15f, 0.3f, 0.5f, 0.7f, 0.85f)) {
                add(Color.hsl(h.toFloat(), s, l))
            }
            for (v in listOf(0.05f, 0.3f, 0.5f, 0.7f, 0.95f)) add(Color.hsl(0f, 0f, v))
        }
        var n = 0
        for ((ground, base, g) in bases) {
            for (c in covers) {
                val argb = (0xFF shl 24) or ((c.red * 255).toInt() shl 16) or ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt()
                val palette = readCoverPalette(IntArray(400) { argb }, key = "sweep") ?: continue
                assertReadable("${hex(c)} on $ground", ThemeAccent(palette).scheme(base, g))
                n++
            }
        }
        assertTrue("swept $n schemes", n >= 3 * 300)
    }

    @Test fun `a coloured album colours the scheme, a colourless one stays grey`() {
        for ((ground, base, g) in bases) {
            for ((album, palette) in albums) {
                val scheme = ThemeAccent(palette).scheme(base, g)
                val (h, s, _) = hsl(scheme.primary)
                if (palette.kind == PaletteKind.COVER) {
                    val vibHue = hsl(palette.vib)[0]
                    val dh = abs(h - vibHue).let { min(it, 360 - it) }
                    assertTrue("$album on $ground: primary keeps the cover's hue ($dh)", dh <= 8.0)
                    assertTrue("$album on $ground: primary is a colour (s=$s)", s >= 0.3)
                    assertTrue("$album on $ground: differs from idle", scheme.primary != base.primary)
                } else {
                    assertTrue("$album on $ground: neutral cover, grey primary (s=$s)", s <= 0.08)
                }
            }
        }
    }

    @Test fun `the album changes only the accent families, never a ground`() {
        for ((ground, base, g) in bases) {
            for ((album, palette) in albums) {
                val before = base.all().toMap()
                val after = ThemeAccent(palette).scheme(base, g).all()
                for ((name, colour) in after) {
                    if (name in accentRoles) continue
                    assertEquals("$album on $ground: $name untouched", before.getValue(name), colour)
                }
            }
        }
    }

    @Test fun `pure black stays black under every album`() {
        for ((album, palette) in albums) {
            val s = ThemeAccent(palette).scheme(StashAmoledColorScheme, Ground.AMOLED)
            listOf(s.background, s.surface, s.surfaceDim, s.surfaceContainerLowest, s.surfaceContainerLow, s.surfaceContainer)
                .forEach { assertEquals("$album: a canvas surface", Color.Black, it) }
            assertEquals("$album: no tonal lift off black", Color.Black, s.surfaceTint)
        }
    }

    @Test fun `nothing playing is exactly the base scheme (Neutral costs nothing)`() {
        for ((ground, base, g) in bases) {
            val idle = ThemeAccent.Idle.scheme(base, g).all()
            val expected = base.all().toMap()
            for ((name, colour) in idle) assertEquals("$ground: $name", expected.getValue(name), colour)
        }
    }

    @Test fun `no purple and no cyan left in the dark and pure-black schemes`() {
        for ((ground, base, _) in bases.filter { it.third != Ground.LIGHT }) {
            for ((name, colour) in base.all()) {
                assertTrue("$ground $name ${hex(colour)} is purple", !isPurple(colour))
                assertTrue("$ground $name ${hex(colour)} is cyan", !isCyan(colour))
            }
        }
        // Light keeps its lavender grounds until phase 2, but its accents are neutral already.
        for ((name, colour) in StashLightColorScheme.all()) {
            if (name !in accentRoles) continue
            assertTrue("light $name ${hex(colour)} is a colour", hsl(colour)[1] <= 0.08)
        }
    }

    @Test fun `the idle accent is near-white on dark and a dark grey on light`() {
        for (base in listOf(StashDarkColorScheme, StashAmoledColorScheme)) {
            assertTrue("near-white primary ${hex(base.primary)}", hsl(base.primary)[2] >= 0.8)
            assertTrue("grey", hsl(base.primary)[1] <= 0.01)
        }
        assertTrue("dark grey primary on light", hsl(StashLightColorScheme.primary)[2] <= 0.35)
        assertEquals(Color.White, StashLightColorScheme.onPrimary)
    }
}
