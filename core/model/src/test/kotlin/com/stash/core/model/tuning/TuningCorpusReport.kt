package com.stash.core.model.tuning

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * The estimator on real songs (true tuning spec §5). Runs only with TUNING_CORPUS set to a folder of
 * `name@shift.f32` files: mono 44.1 kHz float, the same song as recorded (`@0`) and resampled by a known number of
 * cents. A real song's own tuning isn't known, but the shift is: the reading of a shifted copy must move by exactly
 * that much. Prints one line a song; asserts nothing, since the songs aren't in the repository.
 */
class TuningCorpusReport {

    /** Measured the way the app will: in blocks, never the whole file in memory. */
    private fun measure(file: File): TuningEstimator.Result {
        val meter = TuningEstimator.Meter(44_100)
        val chunk = ByteArray(1 shl 18)
        val floats = FloatArray(chunk.size / 4)
        file.inputStream().buffered().use { input ->
            while (true) {
                val got = input.readNBytes(chunk, 0, chunk.size)
                if (got <= 0) break
                ByteBuffer.wrap(chunk, 0, got - got % 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats, 0, got / 4)
                meter.add(floats, got / 4)
            }
        }
        return meter.result()
    }

    private fun apart(a: Double, b: Double): Double {
        val d = abs(a - b) % 100.0
        return if (d > 50.0) 100.0 - d else d
    }

    @Test fun `real songs, as recorded and shifted by known amounts`() {
        val dir = System.getenv("TUNING_CORPUS")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val songs = dir!!.listFiles { f -> f.name.endsWith("@0.f32") }!!.sortedBy { it.name }
        for (song in songs) {
            val name = song.name.removeSuffix("@0.f32")
            val started = System.nanoTime()
            val base = measure(song)
            val ms = (System.nanoTime() - started) / 1_000_000
            val line = StringBuilder("CORPUS %-26s %5.0f s  read %6.2f Hz (%+6.2f c)  confidence %.2f  %s".format(
                name, song.length() / 4 / 44_100.0, base.hz, base.cents, base.confidence, if (base.measured) "measured" else "NOT MEASURED"))
            for (shifted in dir.listFiles { f -> f.name.startsWith("$name@") && f != song }!!.sortedBy { it.name }) {
                val shift = shifted.name.substringAfter('@').removeSuffix(".f32").toDouble()
                val r = measure(shifted)
                line.append("  | %+.2f: error %.2f c, conf %.2f".format(shift, apart(r.cents, base.cents + shift), r.confidence))
            }
            line.append("  | %d ms".format(ms))
            println(line)
        }
    }
}
