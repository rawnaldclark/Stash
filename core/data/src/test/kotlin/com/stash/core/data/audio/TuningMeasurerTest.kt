package com.stash.core.data.audio

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * [TuningMeasurer] with a bridge that stands in for ffmpeg: it hands over raw mono floats at 11,025 Hz, in blocks
 * whose sizes don't line up with a sample.
 */
class TuningMeasurerTest {

    /** Twelve seconds of a chord on the grid at [a4], as the little-endian floats ffmpeg would write. */
    private fun pcm(a4: Double, seconds: Int = 12): ByteArray {
        val rate = 11_025
        val notes = intArrayOf(57, 61, 64, 69, 73).map { a4 * 2.0.pow((it - 69) / 12.0) }
        val out = ByteBuffer.allocate(rate * seconds * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until rate * seconds) {
            var v = 0.0
            for (hz in notes) v += sin(2 * PI * hz * i / rate)
            out.putFloat((v / notes.size * 0.5).toFloat())
        }
        return out.array()
    }

    private class FakeBridge(
        private val bytes: ByteArray = ByteArray(0),
        private val blockSize: Int = 1_001,
        private val failure: Exception? = null,
    ) : FFmpegBridge {
        var args: List<String> = emptyList()

        override suspend fun runWithStderrCapture(args: List<String>): String = error("not used")

        override suspend fun streamStdout(args: List<String>, onBytes: (bytes: ByteArray, length: Int) -> Unit) {
            this.args = args
            val block = ByteArray(blockSize)
            var at = 0
            while (at < bytes.size) {
                val count = minOf(blockSize, bytes.size - at)
                bytes.copyInto(block, 0, at, at + count)
                onBytes(block, count)
                at += count
            }
            failure?.let { throw it }
        }
    }

    private val file = File("song.flac")

    @Test fun `a song at 432 reads 31,8 cents under the grid, whatever the block size`() = runTest {
        for (blockSize in intArrayOf(1, 3, 1_001, 4_096, 65_536)) {
            val r = TuningMeasurer(FakeBridge(pcm(432.0, seconds = 8), blockSize)).measure(file)
            r as TuningMeasurer.Result.Measured
            assertThat(abs(r.cents - (-31.77f))).isLessThan(1f)
            assertThat(r.confidence).isAtLeast(0.9f)
        }
    }

    @Test fun `a song at 442 reads above the grid`() = runTest {
        val r = TuningMeasurer(FakeBridge(pcm(442.0))).measure(file) as TuningMeasurer.Result.Measured
        assertThat(abs(r.cents - 7.85f)).isLessThan(1f)
    }

    @Test fun `ffmpeg is asked for mono floats at 11025 Hz on stdout`() = runTest {
        val bridge = FakeBridge(pcm(440.0, seconds = 2))
        TuningMeasurer(bridge).measure(file)
        assertThat(bridge.args).containsAtLeast("-i", file.absolutePath, "-ac", "1", "-ar", "11025", "-f", "f32le", "-").inOrder()
        assertThat(bridge.args).contains("-vn")
    }

    @Test fun `silence is a measurement with no confidence, not a failure`() = runTest {
        val r = TuningMeasurer(FakeBridge(ByteArray(11_025 * 4 * 6))).measure(file)
        assertThat(r).isEqualTo(TuningMeasurer.Result.Measured(cents = 0f, confidence = 0f))
    }

    @Test fun `a file ffmpeg can't decode is a failure`() = runTest {
        val r = TuningMeasurer(FakeBridge(failure = IOException("ffmpeg exited with code 1:\nInvalid data"))).measure(file)
        assertThat(r).isInstanceOf(TuningMeasurer.Result.Failed::class.java)
        assertThat((r as TuningMeasurer.Result.Failed).reason).contains("Invalid data")
    }

    @Test fun `a file with no audio in it is a failure`() = runTest {
        assertThat(TuningMeasurer(FakeBridge()).measure(file)).isEqualTo(TuningMeasurer.Result.Failed("no audio decoded"))
    }

    @Test fun `a cancelled measurement is rethrown, not stored as a broken file`() {
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking {
                TuningMeasurer(FakeBridge(pcm(440.0, seconds = 1), failure = CancellationException("stopped"))).measure(file)
            }
        }
    }
}
