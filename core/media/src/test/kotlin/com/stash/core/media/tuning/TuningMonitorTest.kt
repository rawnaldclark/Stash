package com.stash.core.media.tuning

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.Executor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/** [TuningMonitor] with the background thread run in place, or held, by the test. */
class TuningMonitorTest {

    private val readings = mutableListOf<TuningMonitor.Reading?>()
    private val inline = Executor { it.run() }

    private fun monitor(executor: Executor = inline) = TuningMonitor(executor) { _, r -> readings += r }

    /** [seconds] of a stereo chord on the grid at [a4], interleaved, the right channel a little quieter. */
    private fun chord(a4: Double, seconds: Double, rate: Int = 48_000): FloatArray {
        val notes = intArrayOf(57, 61, 64, 69).map { a4 * 2.0.pow((it - 69) / 12.0) }
        val frames = (seconds * rate).toInt()
        return FloatArray(frames * 2).also { out ->
            for (i in 0 until frames) {
                var v = 0.0
                for (hz in notes) v += sin(2 * PI * hz * i / rate)
                out[2 * i] = (v / 8).toFloat()
                out[2 * i + 1] = (v / 10).toFloat()
            }
        }
    }

    /** Hands [audio] over in playback-sized buffers. */
    private fun TuningMonitor.play(audio: FloatArray, rate: Int = 48_000, buffer: Int = 960) {
        var at = 0
        while (at < audio.size) {
            val frames = minOf(buffer, (audio.size - at) / 2)
            offer(audio.copyOfRange(at, at + frames * 2), frames, 2, rate)
            at += frames * 2
        }
    }

    @Test fun `output on the 432 grid reads as 432, a reading every few seconds`() {
        val m = monitor()
        m.play(chord(432.0, seconds = 13.0))

        assertThat(readings.size).isAtLeast(2)
        val last = readings.last()!!
        assertThat(last.measured).isTrue()
        assertThat(abs(TuningMonitor.offCents(last.cents, 432f))).isLessThan(1.0)
        assertThat(last.seconds).isGreaterThan(8.0)
        assertThat(readings.filterNotNull().map { it.seconds }).isInOrder()
    }

    @Test fun `output that missed says by how much`() {
        val m = monitor()
        m.play(chord(434.0, seconds = 10.0))
        assertThat(TuningMonitor.offCents(readings.last()!!.cents, 432f)).isWithin(1.0).of(8.0)
    }

    @Test fun `a restart forgets what was heard`() {
        val m = monitor()
        m.play(chord(432.0, seconds = 9.0))
        m.restart()
        assertThat(readings.last()).isNull()

        m.play(chord(444.0, seconds = 9.0))

        val last = readings.last()!!
        assertThat(abs(TuningMonitor.offCents(last.cents, 444f))).isLessThan(1.0)
        assertThat(last.seconds).isLessThan(10.0)
    }

    @Test fun `a new sample rate starts a new measurement`() {
        val m = monitor()
        m.play(chord(432.0, seconds = 9.0))
        m.play(chord(432.0, seconds = 9.0, rate = 96_000), rate = 96_000)
        val last = readings.last()!!
        assertThat(abs(TuningMonitor.offCents(last.cents, 432f))).isLessThan(1.0)
        assertThat(last.seconds).isLessThan(10.0)
    }

    @Test fun `when the background thread falls behind, blocks are dropped, not queued without end`() {
        val held = mutableListOf<Runnable>()
        val m = monitor { held += it }
        m.play(chord(432.0, seconds = 20.0))
        assertThat(held.size).isAtMost(8)
        assertThat(readings).isEmpty()

        held.forEach { it.run() } // it catches up: nothing breaks
    }

    @Test fun `work queued before a restart is thrown away`() {
        val held = mutableListOf<Runnable>()
        val m = monitor { held += it }
        m.play(chord(432.0, seconds = 1.0))
        m.restart()
        held.forEach { it.run() }
        assertThat(readings.filterNotNull()).isEmpty()
    }

    @Test fun `the distance to a target's grid wraps within a semitone`() {
        assertThat(TuningMonitor.offCents(-31.77, 432f)).isWithin(0.01).of(0.0)
        assertThat(TuningMonitor.offCents(-1.27, 415f)).isWithin(0.02).of(0.0) // 415 is a semitone and 1.3 cents down
        assertThat(TuningMonitor.offCents(-49.0, 453f)).isWithin(0.6).of(0.6)
    }
}
