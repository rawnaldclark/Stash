package com.stash.core.ui.ambient

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * No recomposition storm: swapping Material's scheme recomposes the whole app, so [settled] (what
 * [ProvideThemeAccent] feeds the scheme from) must let through at most one palette per album
 * crossfade, never a repeat, and never a palette the user skipped past. Virtual time, so the
 * timings are exact.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThemeAccentSettleTest {

    private fun solid(argb: Long) = IntArray(400) { argb.toInt() }
    private val red = ambientPaletteFromPixels(solid(0xFFD32F2FL), key = "red")!!
    private val blue = ambientPaletteFromPixels(solid(0xFF1F3BFFL), key = "blue")!!
    private val pastel = ambientPaletteFromPixels(solid(0xFFF4C2C2L), key = "pastel")!!
    private val yellow = ambientPaletteFromPixels(solid(0xFFF5D90AL), key = "yellow")!!
    private val idle = AmbientPalette.Default
    private val settle = SCHEME_SETTLE_MS

    @Test fun `the first palette lands at once, so a cold start never waits`() = runTest {
        val out = mutableListOf<Pair<Long, AmbientPalette>>()
        flow { emit(red) }.settled(settle).collect { out += currentTime to it }
        assertEquals(listOf(0L to red), out)
    }

    @Test fun `a run of skips swaps the scheme once, after the last one settles`() = runTest {
        val out = mutableListOf<Pair<Long, AmbientPalette>>()
        flow {
            emit(idle)
            delay(5_000)
            // Four skips 300 ms apart: blue, pastel and yellow are passed over, red stays.
            for (p in listOf(blue, pastel, yellow, red)) {
                emit(p)
                delay(300)
            }
            delay(5_000)
        }.settled(settle).collect { out += currentTime to it }
        assertEquals(listOf(0L to idle, (5_900L + settle) to red), out)
    }

    @Test fun `the player re-emitting the same album, or play and pause, never rebuilds`() = runTest {
        var emitted = 0
        // The player state ticks (position, buffering, play/pause) with the same cover throughout:
        // AlbumColors changes its tint, its palette never does.
        flow {
            repeat(200) { i ->
                emit(AlbumColors(palette = red, tinted = i % 2 == 0))
                delay(250)
            }
        }.map { it.palette }.settled(settle).collect { emitted++ }
        assertEquals(1, emitted)
    }

    @Test fun `a song that comes back before it settles costs nothing`() = runTest {
        val out = mutableListOf<AmbientPalette>()
        flow {
            emit(red)
            delay(200)
            emit(blue) // skipped to...
            delay(200)
            emit(red) // ...and straight back
            delay(5_000)
        }.settled(settle).collect { out += it }
        assertEquals(listOf(red), out)
    }

    @Test fun `songs played through each land once, one crossfade after they start`() = runTest {
        val out = mutableListOf<Pair<Long, AmbientPalette>>()
        flow {
            emit(red)
            delay(180_000)
            emit(blue)
            delay(200_000)
            emit(idle) // the queue ended
            delay(10_000)
        }.settled(settle).collect { out += currentTime to it }
        assertEquals(listOf(0L to red, (180_000L + settle) to blue, (380_000L + settle) to idle), out)
    }

    @Test fun `whatever the input, the scheme rebuilds at most once per crossfade and ends on the last song`() = runTest {
        val rnd = Random(20261101)
        val pool = listOf(red, blue, pastel, yellow, idle)
        val inputs = mutableListOf<AmbientPalette>()
        val out = mutableListOf<Pair<Long, AmbientPalette>>()
        flow {
            repeat(2_000) {
                val p = pool[rnd.nextInt(pool.size)]
                inputs += p
                emit(p)
                // Mostly fast changes (skips, flaky cover reads), sometimes a whole song.
                delay(if (rnd.nextInt(10) == 0) rnd.nextLong(1_000, 240_000) else rnd.nextLong(0, 900))
            }
        }.settled(settle).collect { out += currentTime to it }
        for (i in 1 until out.size) {
            val gap = out[i].first - out[i - 1].first
            assertTrue("rebuilds $gap ms apart at ${out[i].first}", gap >= settle)
            assertTrue("a rebuild changes the palette", out[i].second != out[i - 1].second)
        }
        assertEquals("ends on the last song", inputs.last(), out.last().second)
        assertTrue("far fewer rebuilds (${out.size}) than inputs (${inputs.size})", out.size < inputs.size / 3)
    }

    @Test fun `the scheme for one palette is built the same every time`() {
        // Equal palettes give equal schemes, so a remember keyed on the palette is a real cache.
        val base = com.stash.core.ui.theme.StashAmoledColorScheme
        val a = ThemeAccent(red).scheme(base, Ground.AMOLED)
        val b = ThemeAccent(red.copy()).scheme(base, Ground.AMOLED)
        assertEquals(a.primary, b.primary)
        assertEquals(a.secondaryContainer, b.secondaryContainer)
        assertTrue(a.primary != Color.Unspecified)
    }
}
