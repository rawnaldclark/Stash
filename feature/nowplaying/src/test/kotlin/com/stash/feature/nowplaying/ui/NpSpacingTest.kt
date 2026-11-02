package com.stash.feature.nowplaying.ui

import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Now Playing's spacing: the production values (pinned), the tuner's JSON protocol (unknown keys
 * ignored, ranges clamped, nothing malformed gets through), the BuildConfig gate that keeps release
 * builds on the production values, and where the text block goes in full art.
 */
class NpSpacingTest {

    @Test
    fun `the defaults are the production values`() {
        val s = NpSpacing()
        // The title and artist lines: master's line boxes, now applied explicitly; tracking 0 (bodyLarge's 0.5 before).
        assertEquals(22f, s.titleSize)
        assertEquals(26.8f, s.titleLineHeight)
        assertEquals(0f, s.titleLetterSpacing)
        assertEquals(15f, s.artistSize)
        assertEquals(24f, s.artistLineHeight)
        assertEquals(0f, s.artistLetterSpacing)
        // Everything else as the layout had it (StackRhythm, GapTop/Art/Bottom, UpNextLine, insets).
        assertEquals(20f, s.gapTop)
        assertEquals(36f, s.gapArt)
        assertEquals(28f, s.gapBottom)
        assertEquals(4f, s.titleToArtist)
        assertEquals(6f, s.artistToQuality)
        assertEquals(16f, s.infoToProgress)
        assertEquals(12f, s.progressToControls)
        assertEquals(2f, s.controlsToUpNext)
        assertEquals(40f, s.upNextHeight)
        assertEquals(12f, s.upNextSidePadding)
        assertEquals(24f, s.sidePadding)
        assertEquals(40f, s.centeredTitleInset)
        assertEquals(12f, s.sideButtonGap)
        assertEquals(NpAlign.SETTING, s.align)
        assertEquals(FullArtTextPosition.LAYOUT, s.fullArtText)
    }

    @Test
    fun `title and artist apply their line height even as one line`() {
        // Compose's default LineHeightStyle (Trim.Both) skips a lone line's line height altogether:
        // the slider would do nothing. Trim.None applies it, split evenly above and below.
        val applied = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
        val s = NpSpacing()
        assertEquals(applied, s.titleStyle().lineHeightStyle)
        assertEquals(applied, s.artistStyle().lineHeightStyle)
        assertEquals(LineHeightStyle.Trim.None, s.titleStyle().lineHeightStyle?.trim)
        assertEquals(22f.sp, s.titleStyle().fontSize)
        assertEquals(26.8f.sp, s.titleStyle().lineHeight)
        assertEquals(0f.sp, s.titleStyle().letterSpacing)
        assertEquals(15f.sp, s.artistStyle().fontSize)
        assertEquals(24f.sp, s.artistStyle().lineHeight)
    }

    @Test
    fun `a tuned line height reaches the style`() {
        val tuned = NpSpacingJson.parse("""{"titleLineHeight":40,"artistLineHeight":30,"titleLetterSpacing":0.5}""")
        assertEquals(40f.sp, tuned.titleStyle().lineHeight)
        assertEquals(30f.sp, tuned.artistStyle().lineHeight)
        assertEquals(0.5f.sp, tuned.titleStyle().letterSpacing)
    }

    @Test
    fun `the default line heights are master's line boxes on the Pixel 5 and the Pixel 6`() {
        // Measured on the Pixel 5 against master: the title was Inter's natural line (its ascent
        // and descent, 1984 / 494 of 2048, each rounded to a pixel: 74 px), and an applied line
        // height lands on the nearest pixel (26.6 sp, 73.15 px, measured 73). The artist applied
        // bodyLarge's 24 sp; the default keeps exactly that.
        fun naturalPx(sizeSp: Float, density: Float): Int {
            val px = sizeSp * density
            return (px * 1984f / 2048f).roundToInt() + (px * 494f / 2048f).roundToInt()
        }
        val s = NpSpacing()
        assertEquals(74, naturalPx(s.titleSize, 2.75f))
        for (density in listOf(2.75f, 2.625f)) {
            assertEquals("title at $density", naturalPx(s.titleSize, density), (s.titleLineHeight * density).roundToInt())
        }
        assertEquals("the artist keeps the theme's bodyLarge line height", 24f, s.artistLineHeight)
    }

    @Test
    fun `stored overrides apply only against the production values they were made with`() {
        val json = """{"titleSize":30}"""
        assertEquals(json, NpSpacingJson.overridesFor(json, NpSpacingJson.productionBasis))
        // New defaults baked since: the stale set is dropped, not laid out again.
        val olderBasis = NpSpacingJson.write(NpSpacing(titleLineHeight = 28f))
        assertNull(NpSpacingJson.overridesFor(json, olderBasis))
        assertNull(NpSpacingJson.overridesFor(json, null))
        assertNull(NpSpacingJson.overridesFor(null, NpSpacingJson.productionBasis))
    }

    @Test
    fun `every field reads and writes its own value`() {
        NpSpacingJson.fields.forEachIndexed { i, f ->
            val v = (f.min + f.max) / 2f
            val set = f.set(NpSpacing(), v)
            assertEquals(f.key, v, f.get(set))
            // ...and nothing else moved.
            NpSpacingJson.fields.filterIndexed { j, _ -> j != i }.forEach { other ->
                assertEquals("${f.key} moved ${other.key}", other.get(NpSpacing()), other.get(set))
            }
        }
    }

    @Test
    fun `nothing, blank or malformed JSON leaves the spacing as it was`() {
        val base = NpSpacing(titleSize = 30f)
        assertEquals(base, NpSpacingJson.parse(null, base))
        assertEquals(base, NpSpacingJson.parse("  ", base))
        assertEquals(base, NpSpacingJson.parse("{titleSize", base))
        assertEquals(base, NpSpacingJson.parse("[1,2,3]", base))
        assertEquals(base, NpSpacingJson.parse("42", base))
    }

    @Test
    fun `unknown keys are ignored and known ones applied over the base`() {
        val out = NpSpacingJson.parse("""{"titleSize":24,"wat":9,"gapArt":30,"nested":{"a":1}}""", NpSpacing(artistSize = 16f))
        assertEquals(24f, out.titleSize)
        assertEquals(30f, out.gapArt)
        assertEquals(16f, out.artistSize) // from the base
        assertEquals(NpSpacing().gapTop, out.gapTop)
    }

    @Test
    fun `numbers are clamped to each field's range`() {
        val out = NpSpacingJson.parse("""{"titleSize":500,"gapArt":-12,"titleLetterSpacing":-9,"upNextHeight":1}""")
        assertEquals(36f, out.titleSize)
        assertEquals(0f, out.gapArt)
        assertEquals(-1f, out.titleLetterSpacing)
        assertEquals(24f, out.upNextHeight)
    }

    @Test
    fun `values of the wrong type are ignored`() {
        val out = NpSpacingJson.parse("""{"titleSize":"24","gapArt":true,"gapTop":null,"align":3,"fullArtText":false}""")
        assertEquals(NpSpacing(), out)
    }

    @Test
    fun `choices are read by name, any case, and unknown names ignored`() {
        val out = NpSpacingJson.parse("""{"align":"START","fullArtText":"under_art"}""")
        assertEquals(NpAlign.START, out.align)
        assertEquals(FullArtTextPosition.UNDER_ART, out.fullArtText)
        val kept = NpSpacingJson.parse("""{"align":"diagonal","fullArtText":"sideways"}""", out)
        assertEquals(out, kept)
    }

    @Test
    fun `written JSON parses back to the same spacing`() {
        val tuned = NpSpacing(
            titleSize = 23.5f, titleLineHeight = 30f, titleLetterSpacing = -0.25f, artistSize = 14f,
            gapArt = 24f, controlsToUpNext = 6f, sidePadding = 20f,
            align = NpAlign.CENTER, fullArtText = FullArtTextPosition.BOTTOM,
        )
        assertEquals(tuned, NpSpacingJson.parse(NpSpacingJson.write(tuned)))
        assertEquals(NpSpacing(), NpSpacingJson.parse(NpSpacingJson.write(NpSpacing())))
    }

    @Test
    fun `a release build gets the production values and never opens the overrides`() {
        val source = NpSpacingSource.select(debug = false) { error("a release build must not create the overrides store") }
        assertSame(NpSpacingSource.Production, source)
    }

    @Test
    fun `a debug build reads the overrides`() {
        val overrides = object : NpSpacingSource {
            override val overridesJson = kotlinx.coroutines.flow.flowOf<String?>("""{"titleSize":24}""")
        }
        assertSame(overrides, NpSpacingSource.select(debug = true) { overrides })
    }

    @Test
    fun `the production source has no overrides`() = runTest {
        assertNull(NpSpacingSource.Production.overridesJson.first())
    }

    @Test
    fun `alignment follows Settings unless the tuner pins it`() {
        assertTrue(NpAlign.SETTING.alignStart(settingStart = true))
        assertFalse(NpAlign.SETTING.alignStart(settingStart = false))
        assertTrue(NpAlign.START.alignStart(settingStart = false))
        assertFalse(NpAlign.CENTER.alignStart(settingStart = true))
    }

    @Test
    fun `in full art the text stays where the layout puts it by default`() {
        assertEquals(500, npTextTop(FullArtTextPosition.LAYOUT, layoutTop = 500, bottomTop = 700, underArtTop = 300, grow = 1f))
    }

    @Test
    fun `at the bottom, the text glides there as the art grows`() {
        assertEquals(500, npTextTop(FullArtTextPosition.BOTTOM, 500, 700, null, grow = 0f))
        assertEquals(600, npTextTop(FullArtTextPosition.BOTTOM, 500, 700, null, grow = 0.5f))
        assertEquals(700, npTextTop(FullArtTextPosition.BOTTOM, 500, 700, null, grow = 1f))
    }

    @Test
    fun `under the art, the text follows the art's edge and stays on screen`() {
        assertEquals(420, npTextTop(FullArtTextPosition.UNDER_ART, 500, 700, underArtTop = 420, grow = 1f))
        // An art taller than the room left: no lower than the bottom...
        assertEquals(700, npTextTop(FullArtTextPosition.UNDER_ART, 500, 700, underArtTop = 900, grow = 1f))
        // ...and never above the top.
        assertEquals(0, npTextTop(FullArtTextPosition.UNDER_ART, 500, 700, underArtTop = -40, grow = 1f))
        // Not placed yet: the layout's own place.
        assertEquals(500, npTextTop(FullArtTextPosition.UNDER_ART, 500, 700, underArtTop = null, grow = 1f))
    }
}
