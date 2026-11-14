package com.stash.core.media.streaming

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A placeholder's resolve as [ResolveFirstMediaSource] runs it ([StashMediaSourceFactory.resolveForSource]):
 * every streamed song while a Tidal server is configured. With Tidal busy, the next song's
 * look-ahead waits and plays FLAC; a tapped song plays the fallback at once; and a fallback kept
 * only for an earlier play is never picked up from the cache by a new one. A fallback for a song
 * with no lossless version is reused as before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StashMediaSourceFactoryBusyTest {

    private val playing = 41L
    private val song = 42L
    private val uri: Uri = Uri.parse("stash-resolve://track/$song")
    private val wallAtStart = 1_800_000_000_000L

    private val dao: TrackDao = mockk {
        coEvery { getById(song) } returns TrackEntity(id = song, title = "Poor Jack", artist = "Danny Elfman", durationMs = 213_000)
    }
    private val resolver: StreamSourceRegistry = mockk()
    private val cache = StreamUrlCache()

    private val flac = StreamUrl("https://tidal.example/42.flac", System.currentTimeMillis() + 3_000_000, codec = "flac", bitsPerSample = 24, origin = "tidal")
    private fun opus(busyUntil: Long?) =
        StreamUrl("https://yt.example/42.webm", System.currentTimeMillis() + 6 * 3_600_000, codec = "opus", origin = "youtube", losslessBusyUntilMs = busyUntil)

    private fun factory(lookAhead: BusyLookAhead?) = StashMediaSourceFactory(
        ApplicationProvider.getApplicationContext(), mockk(relaxed = true),
        { null }, { false }, OkHttpClient(), resolver, cache, dao,
        resolveBeforeSourcePick = { true },
        resolveDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        busyLookAhead = lookAhead,
    )

    @Test fun `the next song's look-ahead waits out a busy Tidal and plays and keeps its FLAC`() = runTest {
        val clock = PlayingSongClock().apply { elapsedMs = { testScheduler.currentTime } }
        val wall = { wallAtStart + testScheduler.currentTime }
        clock.publish(playing, 36_000, 109_000, playing = true, speed = 1f, joinLeadMs = 0) // 73 s to go
        coEvery { resolver.resolve(any(), true, true, false) } returns opus(busyUntil = wall() + 15_000)
        coEvery { resolver.resolveLosslessOnly(any()) } returns flac

        val played = factory(BusyLookAhead(clock, wall)).resolveForSource(song, uri)

        assertThat(played).isEqualTo(flac)
        assertThat(cache.get(song)).isEqualTo(flac)
        assertThat(testScheduler.currentTime).isAtLeast(15_000)
    }

    @Test fun `a tapped song plays the busy fallback at once, kept for this play only`() = runTest {
        val clock = PlayingSongClock().apply { elapsedMs = { testScheduler.currentTime } }
        val wall = { wallAtStart + testScheduler.currentTime }
        clock.publish(song, 0, 213_000, playing = true, speed = 1f, joinLeadMs = 0) // the song itself
        val fallback = opus(busyUntil = wall() + 15_000)
        coEvery { resolver.resolve(any(), true, true, false) } returns fallback

        val played = factory(BusyLookAhead(clock, wall)).resolveForSource(song, uri)

        assertThat(played).isEqualTo(fallback)
        assertThat(testScheduler.currentTime).isEqualTo(0)
        coVerify(exactly = 0) { resolver.resolveLosslessOnly(any()) }
        // In the cache for this play's own open (and the quality badge), marked so no later play takes it.
        assertThat(cache.get(song)!!.forThisPlayOnly).isTrue()
    }

    @Test fun `an earlier play's busy fallback is not picked up - the lossless sources are asked again`() = runTest {
        cache.put(song, opus(busyUntil = System.currentTimeMillis() - 60_000))
        coEvery { resolver.resolve(any(), true, true, false) } returns flac

        val played = factory(null).resolveForSource(song, uri)

        assertThat(played).isEqualTo(flac)
        assertThat(cache.get(song)).isEqualTo(flac)
        coVerify(exactly = 1) { resolver.resolve(any(), true, true, false) }
    }

    @Test fun `a fallback for a song with no lossless version is reused as before`() = runTest {
        val noLossless = opus(busyUntil = null)
        cache.put(song, noLossless)

        val played = factory(null).resolveForSource(song, uri)

        assertThat(played).isEqualTo(noLossless)
        coVerify(exactly = 0) { resolver.resolve(any(), any(), any(), any()) }
    }
}
