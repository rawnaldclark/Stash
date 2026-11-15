package com.stash.core.data.radio

import com.stash.core.data.prefs.RadioEnginePreference
import com.stash.core.model.Track
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [RadioEngines] is the only place that decides which engine builds a station, and
 * both of its rules exist to keep a YouTube Music default safe: a build without the
 * extension, and an install with no account, must both land on the Stash engine
 * rather than on a station that can't start.
 */
class RadioEnginesTest {

    private fun engine(
        id: RadioEngineId,
        available: Boolean = true,
    ): RadioEngine = object : RadioEngine {
        override val id: RadioEngineId = id
        override suspend fun available(): Boolean = available
        override suspend fun start(seed: RadioSeed): Pair<RadioStation, List<Track>> =
            object : RadioStation {
                override suspend fun nextBatch(): List<Track> = emptyList()
            } to emptyList()
    }

    private fun preference(id: RadioEngineId): RadioEnginePreference = object : RadioEnginePreference {
        override val engine = flowOf(id)
        override suspend fun current(): RadioEngineId = id
        override suspend fun setEngine(id: RadioEngineId) = Unit
    }

    /** The default is YouTube Music, but a build without the extension has only the Stash engine. */
    @Test
    fun `an absent chosen engine falls back to Stash`() = runTest {
        val stash = engine(RadioEngineId.STASH)
        val engines = RadioEngines(setOf(stash), preference(RadioEngineId.YOUTUBE_MUSIC))
        assertEquals(RadioEngineId.STASH, engines.active().id)
    }

    @Test
    fun `the chosen engine wins when it is bound and available`() = runTest {
        val stash = engine(RadioEngineId.STASH)
        val yt = engine(RadioEngineId.YOUTUBE_MUSIC)
        val engines = RadioEngines(setOf(stash, yt), preference(RadioEngineId.YOUTUBE_MUSIC))
        assertEquals(RadioEngineId.YOUTUBE_MUSIC, engines.active().id)
    }

    /** No linked account (or a dead cookie): the default must not mean "couldn't start radio". */
    @Test
    fun `a bound but unavailable engine falls back to Stash`() = runTest {
        val stash = engine(RadioEngineId.STASH)
        val yt = engine(RadioEngineId.YOUTUBE_MUSIC, available = false)
        val engines = RadioEngines(setOf(stash, yt), preference(RadioEngineId.YOUTUBE_MUSIC))
        assertEquals(RadioEngineId.STASH, engines.active().id)
    }

    /** Choosing Stash explicitly must not be overridden by a YouTube Music engine that happens to be available. */
    @Test
    fun `an explicit Stash choice stays on Stash`() = runTest {
        val stash = engine(RadioEngineId.STASH)
        val yt = engine(RadioEngineId.YOUTUBE_MUSIC)
        val engines = RadioEngines(setOf(stash, yt), preference(RadioEngineId.STASH))
        assertEquals(RadioEngineId.STASH, engines.active().id)
    }

    @Test
    fun `all lists the bound engines in a stable order`() = runTest {
        val engines = RadioEngines(
            setOf(engine(RadioEngineId.YOUTUBE_MUSIC), engine(RadioEngineId.STASH)),
            preference(RadioEngineId.STASH),
        )
        assertEquals(listOf(RadioEngineId.STASH, RadioEngineId.YOUTUBE_MUSIC), engines.all().map { it.id })
    }

    /** `single` is how hand-built players and station tests get an engine with no store. */
    @Test
    fun `single serves the one engine it was given`() = runTest {
        val engines = RadioEngines.single(engine(RadioEngineId.STASH))
        assertEquals(RadioEngineId.STASH, engines.active().id)
        assertNull(engines.of(RadioEngineId.YOUTUBE_MUSIC))
    }
}
