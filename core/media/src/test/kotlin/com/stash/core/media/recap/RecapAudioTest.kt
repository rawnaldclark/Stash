package com.stash.core.media.recap

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The Recap story audio state machine ([RecapAudio]) against a fake preview engine and a fake main
 * player: begin / play / switch / hold / end / restore, double begin and end, offline skip,
 * nothing-was-playing, Listen Together, interruptions, errors, the app going to the background,
 * and closing without restoring.
 *
 * What these prove about "story audio never counts": the main player is only ever paused and
 * played — never given a song, a queue or a seek ([RecapMainPlayback] has nothing else) — so
 * ListeningRecorder, the scrobblers, autoplay radio and Recently played, which all observe the
 * main player, have nothing to see. Every song goes to the separate engine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecapAudioTest {

    // ── Fakes ────────────────────────────────────────────────────────────────────────────────

    /** Records every engine call, in order, as text — easy to assert on sequences. */
    private class FakeEngine : RecapPreviewEngine {
        val calls = mutableListOf<String>()
        val volumes = mutableListOf<Float>()
        var engineListener: RecapPreviewEngine.Listener? = null
        var loadedStartMs: Long = -1
        var playing = false
        var gain = 1f

        override fun setListener(listener: RecapPreviewEngine.Listener?) {
            engineListener = listener
        }

        override fun load(source: RecapSource, startPositionMs: Long) {
            calls += "load:${source.describe()}@$startPositionMs"
            loadedStartMs = startPositionMs
            playing = false
            gain = 0f // the contract: load leaves the player silent
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
            playing = false
        }

        override fun release() {
            calls += "release"
            playing = false
        }

        override fun setVolume(volume: Float) {
            gain = volume
            volumes += volume
        }

        fun loads() = calls.filter { it.startsWith("load:") }

        private fun RecapSource.describe() = when (this) {
            is RecapSource.Local -> "local:$path"
            is RecapSource.Url -> "url:$url"
        }
    }

    /** The main player. pause() and play() are the whole surface the story can touch. */
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

    private class World(scope: TestScope, val main: FakeMain) {
        val engine = FakeEngine()
        var online = true
        /** Track ids [lookupStream] has a stream for (a missing id = no preview stream). */
        val streamable = mutableSetOf<Long>()
        val lookups = mutableListOf<Long>()
        /** When set, a lookup suspends until cancelled (a slow network); records the cancellation. */
        var lookupHangs = false
        var lookupCancelled = false
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
            lookupStream = { track ->
                lookups += track.id
                if (lookupHangs) {
                    try {
                        awaitCancellation()
                    } catch (e: CancellationException) {
                        lookupCancelled = true
                        throw e
                    }
                }
                if (track.id in streamable) RecapSource.Url("https://yt/${track.id}") else null
            },
            mainDispatcher = StandardTestDispatcher(scope.testScheduler),
            log = { log += it },
        )

        fun streamed(id: Long, durationMs: Long = 200_000L): Track {
            streamable += id
            return Track(
                id = id, title = "Song $id", artist = "Artist", durationMs = durationMs,
                youtubeId = "yt$id", isStreamable = true,
            )
        }
    }

    private fun downloaded(id: Long, durationMs: Long = 200_000L) = Track(
        id = id, title = "Song $id", artist = "Artist", durationMs = durationMs,
        filePath = "/music/$id.flac", isDownloaded = true,
    )

    // ── begin / play / end / restore ─────────────────────────────────────────────────────────

    @Test
    fun `begin pauses a playing main player, end plays it again`() = runTest {
        val w = World(this, FakeMain(meantToPlay = true))

        w.audio.begin()
        assertThat(w.main.pauses).isEqualTo(1)
        assertThat(w.audio.active.value).isTrue()

        w.audio.play(downloaded(1))
        advanceUntilIdle()
        assertThat(w.engine.loads()).containsExactly("load:local:/music/1.flac@60000")
        assertThat(w.engine.playing).isTrue()
        assertThat(w.engine.gain).isEqualTo(1f)

        w.audio.end()
        assertThat(w.engine.calls.last()).isEqualTo("release")
        assertThat(w.main.plays).isEqualTo(1)
        assertThat(w.main.meantToPlay).isTrue()
        assertThat(w.audio.active.value).isFalse()
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
    fun `the first card's song is never played before it is loaded silent`() = runTest {
        val w = World(this, FakeMain(meantToPlay = false))
        w.audio.begin()
        w.audio.play(downloaded(1))
        advanceUntilIdle()

        // load (which leaves the player at 0) comes before play; no full-gain volume before play.
        val loadAt = w.engine.calls.indexOfFirst { it.startsWith("load:") }
        assertThat(w.engine.calls.indexOf("play")).isGreaterThan(loadAt)
    }

    @Test
    fun `switching cards fades the old song out before the new one loads`() = runTest {
        val w = World(this, FakeMain(meantToPlay = true))
        w.audio.begin()
        w.audio.play(downloaded(1))
        advanceUntilIdle()
        val volumesBeforeSwitch = w.engine.volumes.size

        w.audio.play(w.streamed(2))
        advanceUntilIdle()

        assertThat(w.engine.loads()).containsExactly(
            "load:local:/music/1.flac@60000",
            "load:url:https://yt/2@60000",
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

    // ── streams ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a downloaded song never looks for a stream`() = runTest {
        val w = World(this, FakeMain(meantToPlay = false))
        w.audio.begin()
        w.audio.play(downloaded(1))
        advanceUntilIdle()
        assertThat(w.lookups).isEmpty()
    }

    @Test
    fun `a song with no preview stream leaves its card quiet`() = runTest {
        val w = World(this, FakeMain(meantToPlay = false))
        w.audio.begin()
        val noStream = Track(id = 9, title = "T", artist = "A", youtubeId = "yt9", isStreamable = true)
        w.audio.play(noStream)
        advanceUntilIdle()

        assertThat(w.lookups).containsExactly(9L)
        assertThat(w.engine.loads()).isEmpty()
        assertThat(w.log.any { "NO_PREVIEW_STREAM" in it }).isTrue()
    }

    @Test
    fun `swiping on cancels the previous card's stream lookup`() = runTest {
        val w = World(this, FakeMain(meantToPlay = false))
        w.audio.begin()
        w.lookupHangs = true
        w.audio.play(w.streamed(1))
        advanceUntilIdle()
        assertThat(w.lookups).containsExactly(1L)

        w.lookupHangs = false
        w.audio.play(downloaded(2))
        advanceUntilIdle()

        assertThat(w.lookupCancelled).isTrue()
        assertThat(w.engine.loads()).containsExactly("load:local:/music/2.flac@60000")
    }

    @Test
    fun `end cancels a stream lookup in flight`() = runTest {
        val w = World(this, FakeMain(meantToPlay = true))
        w.audio.begin()
        w.lookupHangs = true
        w.audio.play(w.streamed(1))
        advanceUntilIdle()

        w.audio.end()
        advanceUntilIdle()

        assertThat(w.lookupCancelled).isTrue()
        assertThat(w.engine.loads()).isEmpty()
        assertThat(w.main.plays).isEqualTo(1)
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

    // ── closing without restoring ────────────────────────────────────────────────────────────

    @Test
    fun `end without restore stops the story and leaves the main player paused`() = runTest {
        val w = World(this, FakeMain(meantToPlay = true))
        w.audio.begin()
        w.audio.play(downloaded(1))
        advanceUntilIdle()

        w.audio.end(restoreMain = false)

        assertThat(w.engine.calls.last()).isEqualTo("release")
        assertThat(w.main.plays).isEqualTo(0)
        assertThat(w.main.meantToPlay).isFalse()
        assertThat(w.audio.active.value).isFalse()
        // A later end (the screen's onDispose) can't bring the music back either.
        w.audio.end()
        assertThat(w.main.plays).isEqualTo(0)
    }

    @Test
    fun `active follows the story`() = runTest {
        val w = World(this, FakeMain(meantToPlay = false))
        assertThat(w.audio.active.value).isFalse()
        w.audio.begin()
        assertThat(w.audio.active.value).isTrue()
        w.audio.end(restoreMain = false)
        assertThat(w.audio.active.value).isFalse()
    }

    // ── nothing was playing ──────────────────────────────────────────────────────────────────

    @Test
    fun `nothing playing before means nothing playing after`() = runTest {
        val w = World(this, FakeMain(meantToPlay = false))
        w.audio.begin()
        w.audio.play(w.streamed(1))
        advanceUntilIdle()
        w.audio.end()

        assertThat(w.main.pauses).isEqualTo(0)
        assertThat(w.main.plays).isEqualTo(0)
        assertThat(w.main.meantToPlay).isFalse()
    }

    // ── offline ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `offline, a streamed song is silently skipped without a lookup and a downloaded one still plays`() = runTest {
        val w = World(this, FakeMain(meantToPlay = true))
        w.online = false
        w.audio.begin()

        w.audio.play(w.streamed(1))
        advanceUntilIdle()
        assertThat(w.engine.loads()).isEmpty()
        assertThat(w.lookups).isEmpty()
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
        w.audio.play(w.streamed(2))
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
    fun `letting go while the next card's song is on its way never sounds the old song`() = runTest {
        // The quiz pauses the story's song; the #1 reveal then plays a new song and lets go at once.
        val w = World(this, FakeMain(meantToPlay = false))
        w.audio.begin()
        w.audio.play(downloaded(1))
        advanceUntilIdle()
        w.audio.pause()
        advanceUntilIdle()
        val mark = w.engine.calls.size

        w.audio.play(downloaded(2))
        w.audio.resume()
        advanceUntilIdle()

        val after = w.engine.calls.drop(mark)
        val secondLoad = after.indexOfFirst { it.startsWith("load:") }
        assertThat(secondLoad).isAtLeast(0)
        // No "play" before the new song is loaded: the old one stayed silent.
        assertThat(after.take(secondLoad)).doesNotContain("play")
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

    // ── the app in the background ────────────────────────────────────────────────────────────

    @Test
    fun `background pauses the story's song but keeps the story open and the music paused`() = runTest {
        val w = World(this, FakeMain(meantToPlay = true))
        w.audio.begin()
        w.audio.play(downloaded(1))
        advanceUntilIdle()

        w.audio.onAppBackground()
        advanceUntilIdle()

        assertThat(w.engine.playing).isFalse()
        assertThat(w.audio.active.value).isTrue()
        assertThat(w.main.plays).isEqualTo(0)
        assertThat(w.engine.calls).doesNotContain("release")
    }

    @Test
    fun `back in the foreground the current card's song carries on`() = runTest {
        val w = World(this, FakeMain(meantToPlay = true))
        w.audio.begin()
        w.audio.play(downloaded(1))
        advanceUntilIdle()
        w.audio.onAppBackground()
        advanceUntilIdle()

        w.audio.onAppForeground()
        advanceUntilIdle()

        assertThat(w.engine.playing).isTrue()
        assertThat(w.engine.gain).isEqualTo(1f)
        assertThat(w.engine.loads()).hasSize(1) // same song, not reloaded
        // The music comes back only when the story is closed.
        assertThat(w.main.plays).isEqualTo(0)
        w.audio.end()
        assertThat(w.main.plays).isEqualTo(1)
    }

    @Test
    fun `a song that loads while in the background waits for the foreground`() = runTest {
        val w = World(this, FakeMain(meantToPlay = false))
        w.audio.begin()
        w.audio.onAppBackground()
        w.audio.play(downloaded(1))
        advanceUntilIdle()
        assertThat(w.engine.loads()).hasSize(1)
        assertThat(w.engine.playing).isFalse()

        w.audio.onAppForeground()
        advanceUntilIdle()
        assertThat(w.engine.playing).isTrue()
        assertThat(w.engine.gain).isEqualTo(1f)
    }

    @Test
    fun `a story held before going to the background stays held when it comes back`() = runTest {
        val w = World(this, FakeMain(meantToPlay = false))
        w.audio.begin()
        w.audio.play(downloaded(1))
        advanceUntilIdle()
        w.audio.pause()
        w.audio.onAppBackground()
        w.audio.onAppForeground()
        advanceUntilIdle()
        assertThat(w.engine.playing).isFalse()

        w.audio.resume()
        advanceUntilIdle()
        assertThat(w.engine.playing).isTrue()
    }

    @Test
    fun `another app taking focus while we're in the background doesn't silence the story`() = runTest {
        val w = World(this, FakeMain(meantToPlay = true))
        w.audio.begin()
        w.audio.play(downloaded(1))
        advanceUntilIdle()
        w.audio.onAppBackground()
        advanceUntilIdle()

        w.engine.engineListener!!.onInterrupted()
        w.audio.onAppForeground()
        advanceUntilIdle()

        assertThat(w.engine.playing).isTrue()
        w.audio.end()
        assertThat(w.main.plays).isEqualTo(1)
    }

    @Test
    fun `background and foreground outside a story do nothing`() = runTest {
        val w = World(this, FakeMain(meantToPlay = true))
        w.audio.onAppBackground()
        w.audio.onAppForeground()
        advanceUntilIdle()
        assertThat(w.engine.calls).isEmpty()
        assertThat(w.main.plays).isEqualTo(0)
        assertThat(w.main.pauses).isEqualTo(0)
    }

    // ── interruptions, errors, Listen Together ───────────────────────────────────────────────

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
        w.audio.play(w.streamed(1))
        advanceUntilIdle()

        w.engine.engineListener!!.onError("connection dropped")
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
        w.audio.play(w.streamed(1))
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
