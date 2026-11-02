package com.stash.core.ui.ambient

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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

    /** A cover read that takes [readMs] (a cold start decodes it), as AlbumColorSource's retryingLoad. */
    private fun reads(palettes: Map<String, AmbientPalette>, readMs: Long = 600) =
        { model: String, fallback: AmbientPalette ->
            flow<AmbientPalette> {
                delay(readMs)
                emit(palettes[model] ?: fallback)
            }
        }

    /** The scheme's input as the app builds it: AlbumColorSource's covers, settled on their colours. */
    private fun schemeInput(models: Flow<String?>, seed: PaletteSeed?, palettes: Map<String, AmbientPalette>) =
        coverPalettes(models, seed, reads(palettes)).map { it.second }.settledColours(settle)

    @Test fun `a cold start with a restored queue opens in the song's colour and never shows idle`() = runTest {
        // The player reports nothing for a moment, then its restored song; the app closed on red.
        val models = flow {
            emit(null)
            delay(400)
            emit("cover-red")
            delay(10_000)
        }
        val seed = PaletteSeed("cover-red", red)
        val out = mutableListOf<Pair<Long, AmbientPalette>>()
        schemeInput(models, seed, mapOf("cover-red" to red)).collect { out += currentTime to it }
        // Red from the first value (the StateFlow starts on the seed too), and nothing after: the
        // fresh read of the same cover changes nothing.
        assertEquals(listOf(400L to red.coloursOnly()), out)
    }

    @Test fun `with no restored queue the remembered colour gives way to idle after the grace`() = runTest {
        val out = mutableListOf<Pair<Long, AmbientPalette>>()
        schemeInput(flow { emit(null); delay(10_000) }, PaletteSeed("cover-red", red), emptyMap())
            .collect { out += currentTime to it }
        assertEquals(listOf(RESTORE_GRACE_MS to idle.coloursOnly()), out)
    }

    @Test fun `without a seed a cold start is idle, then the song once its cover is read`() = runTest {
        val out = mutableListOf<Pair<Long, AmbientPalette>>()
        schemeInput(flow { emit(null); delay(400); emit("cover-red"); delay(10_000) }, null, mapOf("cover-red" to red))
            .collect { out += currentTime to it }
        assertEquals(listOf(0L to idle.coloursOnly(), (400L + 600L + settle) to red.coloursOnly()), out)
    }

    @Test fun `an offline cold start keeps the remembered colour`() = runTest {
        // The read fails (the fallback is the seed itself), so the app stays red rather than going idle.
        val out = mutableListOf<AmbientPalette>()
        schemeInput(flowOf("cover-red"), PaletteSeed("cover-red", red), emptyMap()).collect { out += it }
        assertEquals(listOf(red.coloursOnly()), out)
    }

    @Test fun `two cover files of the same colour are one accent (an imported album saves one per track)`() = runTest {
        var emitted = 0
        flow {
            // Each track of an imported album has its own cover file, so its own key: same pixels.
            repeat(12) { track ->
                emit(red.copy(key = "art:local_track_$track.jpg"))
                delay(200_000)
            }
        }.settledColours(settle).collect { emitted++ }
        assertEquals(1, emitted)
    }

    @Test fun `the remembered palette survives the snapshot unchanged`() {
        for (p in listOf(red, blue, pastel, yellow, neutralPaletteFrom(solid(0xFF777777L), key = "grey")!!)) {
            val seed = PaletteSeed("content://covers/a b,c.jpg", p)
            assertEquals(seed, PaletteSeed.decode(seed.model, p.key, seed.encodedColours()))
        }
        assertEquals(null, PaletteSeed.decode(null, "k", PaletteSeed("m", red).encodedColours()))
        assertEquals(null, PaletteSeed.decode("m", "k", "COVER,1,2"))
        assertEquals(null, PaletteSeed.decode("m", "k", "NOPE,1,2,3,4,5,6"))
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
