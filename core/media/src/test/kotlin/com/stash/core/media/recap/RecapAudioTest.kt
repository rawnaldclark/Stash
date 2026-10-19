package com.stash.core.media.recap

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.Track
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The Recap story audio state machine ([RecapAudio]) against a fake preview engine and a fake main
 * player: begin / play / switch / hold / end / restore, double begin and end, offline skip,
 * nothing-was-playing, Listen Together, interruptions and errors.
 *
 * What these prove about "story audio never counts": the main player is only ever paused and
 * played — never given a song, a queue or a seek (the fake fails the test if anything else is
 * asked of it) — so ListeningRecorder, the scrobblers, autoplay radio and Recently played, which
 * all observe the main player, have nothing to see. Every song goes to the separate engine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecapAudioTest {

    // ── Fakes ────────────────────────────────────────────────────────────────────────────────

    /** Records every engine call, in order, as text — easy to assert on sequences. */
    private class FakeEngine : RecapPreviewEngine {
        val calls = mutableListOf<String>()
        val volumes = mutableListOf<Float>()
        var engineListener: RecapPreviewEngine.Listener? = null
        var loaded: RecapSource? = null
        var loadedStartMs: Long = -1
        var playing = false
        var gain = 1f

        override fun setListener(listener: RecapPreviewEngine.Listener?) {
            engineListener = listener
        }

        override fun load(source: RecapSource, startPositionMs: Long) {
            calls += "load:${source.describe()}@$startPositionMs"
            loaded = source
            loadedStartMs = startPositionMs
            playing = false
        }

        override fun play() {
            calls += "play"
            playing = true
        }

        override fun pause() {
            calls += "pause"
            playing = false
        }

        override fun stop() {
            calls += "stop"
            loaded = null
            playing = false
        }

        override fun release() {
            calls += "release"
            loaded = null
            playing = false
        }

        override fun setVolume(volume: Float) {
            gain = volume
            volumes += volume
        }

        fun loads() = calls.filter { it.startsWith("load:") }

        private fun RecapSource.describe() = when (this) {
            is RecapSource.Local -> "local:$path"
            is RecapSource.Stream -> "stream:$trackId"
        }
    }

    /**
     * The main player. Only pause() and play() exist on [RecapMainPlayback] at all; counting them
     * is the whole surface the story can touch.
     */
    private class FakeMain(
        var meantToPlay: Boolean,
        var listenTogether: Boolean = false,
        /** false = the pause hasn't reached the state yet (the race right after begin). */
        private val pauseUpdatesState: Boolean = true,
    ) : RecapMainPlayback {
        var pauses = 0
        var plays = 0
        override fun isMeantToPlay() = meantToPlay
        override fun inListenTogether() = listenTogether
        override suspend fun pause() {
            pauses++
            if (pauseUpdatesState) meantToPlay = false
        }
        override suspend fun play() {
            plays++
            meantToPlay = true
        }
    }

    private class World(scope: TestScope, main: FakeMain, fadeMs: Long = RecapAudio.DEFAULT_FADE_MS) {
        val engine = FakeEngine()
        val main = main
        var online = true
        val log = mutableListOf<String>()
        val audio = RecapAudio(
            engine = engine,
            main = main,
            chooseSource = { track ->
                recapSourceFor(
                    track = track,
                    localPlayable = track.filePath != null,
                    online = online,
                    streamAllowedOnThisNetwork = true,
                )
            },
            mainDispatcher = StandardTestDispatcher(scope.testScheduler),
            fadeMs = fadeMs,
            log = { log += it },
        )
    }

    private fun downloaded(id: Long, durationMs: Long = 200_000L) = Track(
        id = id, title = "Song $id", artist = "Artist", durationMs = durationMs,
        filePath = "/music/$id.flac", isDownloaded = true,
    )

    private fun streamed(id: Long, durationMs: Long = 200_000L) = Track(
        id = id, title = "Song $id", artist = "Artist", durationMs = durationMs,
        youtubeId = "yt$id", isStreamable = true,
    )

    // ── begin / play / end / restore ─────────────────────────────────────────────────────────

    @Test
    fun `begin pauses a playing main player, end plays it again`() = runTest {
        val w = World(this, FakeMain(meantToPlay = true))

        w.audio.begin()
        assertThat(w.main.pauses).isEqualTo(1)
        assertThat(w.audio.isActive).isTrue()

        w.audio.play(downloaded(1))
        advanceUntilIdle()
        assertThat(w.engine.loads()).containsExactly("load:local:/music/1.flac@60000")
        assertThat(w.engine.playing).isTrue()
        assertThat(w.engine.gain).isEqualTo(1f)

        w.audio.end()
        assertThat(w.engine.calls.last()).isEqualTo("release")
        assertThat(w.main.plays).isEqualTo(1)
        assertThat(w.main.meantToPlay).isTrue()
        assertThat(w.audio.isActive).isFalse()
    }

    @Test
    fun `a song fades in from silence to full gain`() = runTest {
        val w = World(this, FakeMain(meantToPlay = false))
        w.audio.begin()
        w.audio.play(downloaded(1))
        advanceUntilIdle()

        assertThat(w.engine.volumes.first()).isEqualTo(0f)
        assertThat(w.engine.volumes).isInOrder()
        assertThat(w.engine.volumes.last()).isEqualTo(1f)
        // ~400 ms of steps, not a jump.
        assertThat(w.engine.volumes.size).isAtLeast(10)
    }

    @Test
    fun `switching cards fades the old song out before the new one loads`() = runTest {
        val w = World(this, FakeMain(meantToPlay = true))
        w.audio.begin()
        w.audio.play(downloaded(1))
        advanceUntilIdle()
        val volumesBeforeSwitch = w.engine.volumes.size

        w.audio.play(streamed(2))
        advanceUntilIdle()

        assertThat(w.engine.loads()).containsExactly(
            "load:local:/music/1.flac@60000",
            "load:stream:2@60000",
        ).inOrder()
        // After the switch began: down to 0 (fade out), then up to 1 (fade in).
        val switchVolumes = w.engine.volumes.drop(volumesBeforeSwitch)
        val bottom = switchVolumes.indexOf(0f)
        assertThat(bottom).isGreaterThan(0)
        assertThat(switchVolumes.subList(0, bottom + 1)).isInOrder(reverseOrder<Float>())
        assertThat(switchVolumes.subList(bottom, switchVolumes.size)).isInOrder()
        assertThat(w.engine.gain).isEqualTo(1f)
    }

    @Test
    fun `a card change arriving mid-switch wins, the superseded song never loads`() = runTest {
        val w = World(this, FakeMain(meantToPlay = false))
        w.audio.begin()
        w.audio.play(downloaded(1))
        advanceUntilIdle()

        w.audio.play(downloaded(2))
        testScheduler.advanceTimeBy(100) // mid fade-out of song 1
        w.audio.play(downloaded(3))
        advanceUntilIdle()

        assertThat(w.engine.loads()).containsExactly(
            "load:local:/music/1.flac@60000",
            "load:local:/music/3.flac@60000",
        ).inOrder()
    }

    @Test
    fun `the same song under the next card keeps playing`() = runTest {
        val w = World(this, FakeMain(meantToPlay = false))
        w.audio.begin()
        w.audio.play(downloaded(1))
        advanceUntilIdle()
        w.audio.play(downloaded(1))
        advanceUntilIdle()

        assertThat(w.engine.loads()).hasSize(1)
    }

    @Test
    fun `start point skips the intro but leaves at least 25 seconds`() = runTest {
        val w = World(this, FakeMain(meantToPlay = false))
        w.audio.begin()
        w.audio.play(downloaded(1, durationMs = 30_000L))
        advanceUntilIdle()
        assertThat(w.engine.loadedStartMs).isEqualTo(5_000L)
    }

    // ── idempotency ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `end twice restores once`() = runTest {
        val w = World(this, FakeMain(meantToPlay = true))
        w.audio.begin()
        w.audio.play(downloaded(1))
        advanceUntilIdle()

        w.audio.end()
        w.audio.end()

        assertThat(w.main.plays).isEqualTo(1)
        assertThat(w.engine.calls.count { it == "release" }).isEqualTo(1)
    }

    @Test
    fun `begin twice keeps the first snapshot, so the music still comes back`() = runTest {
        val w = World(this, FakeMain(meantToPlay = true))
        w.audio.begin()
        // The first begin paused it: a fresh snapshot here would read "not playing".
        w.audio.begin()
        w.audio.end()

        assertThat(w.main.pauses).isEqualTo(1)
        assertThat(w.main.plays).isEqualTo(1)
    }

    @Test
    fun `end without begin does nothing`() = runTest {
        val w = World(this, FakeMain(meantToPlay = true))
        w.audio.end()
        assertThat(w.main.plays).isEqualTo(0)
        assertThat(w.main.pauses).isEqualTo(0)
        assertThat(w.engine.calls).isEmpty()
    }

    @Test
    fun `end right after begin restores even if the pause hasn't reached the player state`() = runTest {
        val w = World(this, FakeMain(meantToPlay = true, pauseUpdatesState = false))
        w.audio.begin()
        w.audio.end()
        assertThat(w.main.plays).isEqualTo(1)
    }

    @Test
    fun `a new story after an ended one snapshots afresh`() = runTest {
        val w = World(this, FakeMain(meantToPlay = true))
        w.audio.begin()
        w.audio.end()
        w.main.meantToPlay = false // the user paused afterwards
        w.audio.begin()
        w.audio.end()

        assertThat(w.main.plays).isEqualTo(1) // only the first story's restore
    }

    @Test
    fun `play outside a story is ignored`() = runTest {
        val w = World(this, FakeMain(meantToPlay = true))
        w.audio.play(downloaded(1))
        advanceUntilIdle()
        assertThat(w.engine.calls).isEmpty()
        assertThat(w.main.pauses).isEqualTo(0)
    }

    @Test
    fun `play after end is ignored`() = runTest {
        val w = World(this, FakeMain(meantToPlay = false))
        w.audio.begin()
        w.audio.end()
        w.audio.play(downloaded(1))
        advanceUntilIdle()
        assertThat(w.engine.loads()).isEmpty()
    }

    // ── nothing was playing ──────────────────────────────────────────────────────────────────

    @Test
    fun `nothing playing before means nothing playing after`() = runTest {
        val w = World(this, FakeMain(meantToPlay = false))
        w.audio.begin()
        w.audio.play(streamed(1))
        advanceUntilIdle()
        w.audio.end()

        assertThat(w.main.pauses).isEqualTo(0)
        assertThat(w.main.plays).isEqualTo(0)
        assertThat(w.main.meantToPlay).isFalse()
    }

    // ── offline ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `offline, a streamed song is silently skipped and a downloaded one still plays`() = runTest {
        val w = World(this, FakeMain(meantToPlay = true))
        w.online = false
        w.audio.begin()

        w.audio.play(streamed(1))
        advanceUntilIdle()
        assertThat(w.engine.loads()).isEmpty()
        assertThat(w.log.any { "OFFLINE" in it }).isTrue()

        w.audio.play(downloaded(2))
        advanceUntilIdle()
        assertThat(w.engine.loads()).containsExactly("load:local:/music/2.flac@60000")

        w.audio.end()
        assertThat(w.main.plays).isEqualTo(1)
    }

    @Test
    fun `a quiet card stops the previous card's song`() = runTest {
        val w = World(this, FakeMain(meantToPlay = false))
        w.audio.begin()
        w.audio.play(downloaded(1))
        advanceUntilIdle()

        w.online = false
        w.audio.play(streamed(2))
        advanceUntilIdle()

        assertThat(w.engine.calls.last()).isEqualTo("stop")
        assertThat(w.engine.gain).isEqualTo(0f)
        // And coming back to song 1 plays it again (the quiet card cleared "current").
        w.audio.play(downloaded(1))
        advanceUntilIdle()
        assertThat(w.engine.loads()).hasSize(2)
    }

    // ── hold to pause ────────────────────────────────────────────────────────────────────────

    @Test
    fun `hold pauses the song and letting go resumes it`() = runTest {
        val w = World(this, FakeMain(meantToPlay = false))
        w.audio.begin()
        w.audio.play(downloaded(1))
        advanceUntilIdle()

        w.audio.pause()
        advanceUntilIdle()
        assertThat(w.engine.playing).isFalse()

        w.audio.resume()
        advanceUntilIdle()
        assertThat(w.engine.playing).isTrue()
        assertThat(w.engine.gain).isEqualTo(1f)
    }

    @Test
    fun `a song that loads during a hold waits, then fades in on release`() = runTest {
        val w = World(this, FakeMain(meantToPlay = false))
        w.audio.begin()
        w.audio.pause()
        w.audio.play(downloaded(1))
        advanceUntilIdle()
        assertThat(w.engine.loads()).hasSize(1)
        assertThat(w.engine.playing).isFalse()

        w.audio.resume()
        advanceUntilIdle()
        assertThat(w.engine.playing).isTrue()
        assertThat(w.engine.gain).isEqualTo(1f)
    }

    // ── interruptions, errors, Listen Together, background ───────────────────────────────────

    @Test
    fun `headphones out or focus lost silences the story and leaves the main player paused`() = runTest {
        val w = World(this, FakeMain(meantToPlay = true))
        w.audio.begin()
        w.audio.play(downloaded(1))
        advanceUntilIdle()

        w.engine.engineListener!!.onInterrupted()
        w.audio.play(downloaded(2))
        advanceUntilIdle()
        w.audio.end()

        assertThat(w.engine.loads()).hasSize(1)
        assertThat(w.main.plays).isEqualTo(0) // never resumes onto the speaker
    }

    @Test
    fun `a song that fails leaves its card quiet and the next card plays`() = runTest {
        val w = World(this, FakeMain(meantToPlay = false))
        w.audio.begin()
        w.audio.play(streamed(1))
        advanceUntilIdle()

        w.engine.engineListener!!.onError("resolve failed")
        assertThat(w.engine.calls.last()).isEqualTo("stop")

        w.audio.play(downloaded(2))
        advanceUntilIdle()
        assertThat(w.engine.loads().last()).isEqualTo("load:local:/music/2.flac@60000")
        assertThat(w.engine.playing).isTrue()
    }

    @Test
    fun `the outgoing song failing during a card change doesn't silence the new card`() = runTest {
        val w = World(this, FakeMain(meantToPlay = false))
        w.audio.begin()
        w.audio.play(streamed(1))
        advanceUntilIdle()

        w.audio.play(downloaded(2))
        testScheduler.advanceTimeBy(100) // fading song 1 out
        w.engine.engineListener!!.onError("song 1 connection dropped")
        advanceUntilIdle()

        assertThat(w.engine.loads().last()).isEqualTo("load:local:/music/2.flac@60000")
        assertThat(w.engine.playing).isTrue()
    }

    @Test
    fun `Listen Together keeps the story quiet and the room untouched`() = runTest {
        val w = World(this, FakeMain(meantToPlay = true, listenTogether = true))
        w.audio.begin()
        w.audio.play(downloaded(1))
        advanceUntilIdle()
        w.audio.end()

        assertThat(w.main.pauses).isEqualTo(0)
        assertThat(w.main.plays).isEqualTo(0)
        assertThat(w.engine.loads()).isEmpty()
    }

    @Test
    fun `going to the background ends the story and restores the music`() = runTest {
        val w = World(this, FakeMain(meantToPlay = true))
        w.audio.begin()
        w.audio.play(downloaded(1))
        advanceUntilIdle()

        w.audio.onBackground()
        advanceUntilIdle()

        assertThat(w.audio.isActive).isFalse()
        assertThat(w.engine.calls.last()).isEqualTo("release")
        assertThat(w.main.plays).isEqualTo(1)
    }

    @Test
    fun `end fades the song out before releasing the player`() = runTest {
        val w = World(this, FakeMain(meantToPlay = true))
        w.audio.begin()
        w.audio.play(downloaded(1))
        advanceUntilIdle()
        val before = w.engine.volumes.size

        w.audio.end()

        val endVolumes = w.engine.volumes.drop(before)
        assertThat(endVolumes).isNotEmpty()
        assertThat(endVolumes).isInOrder(reverseOrder<Float>())
        assertThat(endVolumes.last()).isEqualTo(0f)
    }
}
