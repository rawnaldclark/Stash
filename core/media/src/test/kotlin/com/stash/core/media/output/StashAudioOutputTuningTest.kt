package com.stash.core.media.output

import android.media.AudioDeviceInfo
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOutput
import com.google.common.truth.Truth.assertThat
import com.stash.core.media.equalizer.EqState
import com.stash.core.media.equalizer.LoudnessState
import com.stash.core.media.equalizer.dsp.FixedDelayFakeStage
import com.stash.core.media.equalizer.dsp.FloatDspChain
import com.stash.core.media.equalizer.dsp.PitchStageFactory
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tuning's delay through the output stage, with a pitch stage that only delays (by [LATENCY]
 * frames): the position is what's heard, the end of a stream drains the stage so DefaultAudioSink
 * sees every written frame played, a flush starts clean, 440 Hz never touches a sample, and
 * switching Tuning on or off mid-stream rebuilds the stream instead of jumping.
 */
class StashAudioOutputTuningTest {

    private companion object {
        const val RATE = 48_000
        const val CH = 2
        const val FRAME = 4 * CH // float stereo
        const val LATENCY = 480 // 10 ms at 48 kHz
    }

    /** The "AudioTrack": takes up to [room] bytes per write, and has played [playedFrames]. */
    private class FakeTrack(var room: Int = Int.MAX_VALUE) : AudioOutput {
        val bytes = ByteArrayOutputStream()
        var lastBuffer: ByteBuffer? = null
        var playedFrames = 0L
        var stopCalls = 0
        var framesAtStop = -1L
        val writtenFrames: Long get() = bytes.size().toLong() / FRAME

        override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
            lastBuffer = buffer
            val n = minOf(room, buffer.remaining())
            repeat(n) { bytes.write(buffer.get().toInt()) }
            return !buffer.hasRemaining()
        }
        override fun stop() {
            stopCalls++
            framesAtStop = writtenFrames
        }
        override fun getPositionUs(): Long = playedFrames * 1_000_000L / RATE
        override fun getSampleRate() = RATE
        override fun play() = Unit
        override fun pause() = Unit
        override fun flush() = Unit
        override fun release() = Unit
        override fun setVolume(volume: Float) = Unit
        override fun isOffloadedPlayback() = false
        override fun getAudioSessionId() = 0
        override fun getBufferSizeInFrames() = 0L
        override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
        override fun isStalled() = false
        override fun addListener(listener: AudioOutput.Listener) = Unit
        override fun removeListener(listener: AudioOutput.Listener) = Unit
        override fun setPlaybackParameters(playbackParams: PlaybackParameters) = Unit
        override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = Unit
        override fun setOffloadEndOfStream() = Unit
        override fun attachAuxEffect(effectId: Int) = Unit
        override fun setAuxEffectSendLevel(level: Float) = Unit
        override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) = Unit
    }

    private val controller = mockk<HiResOutputController>(relaxed = true)
    private var hz = 432f
    private val stages = mutableListOf<FixedDelayFakeStage>()

    private fun sharedPlan() =
        OutputPlan(MixerDecision.Shared(MixerDecision.Reason.MODE_STANDARD), SampleFormat.FLOAT, null, null)

    private fun bitPerfectPlan(): OutputPlan {
        val option = MixerOption(SampleFormat.INT24, RATE, CH, bitPerfect = true, index = 0)
        return OutputPlan(MixerDecision.Configure(option, SampleFormat.INT24), SampleFormat.INT24, null, Any())
    }

    private fun output(track: FakeTrack, plan: OutputPlan = sharedPlan()) = StashAudioOutput(
        inner = track,
        inFormat = SampleFormat.FLOAT,
        outFormat = plan.trackFormat,
        channelCount = CH,
        streamSampleRate = RATE,
        plan = plan,
        dsp = FloatDspChain(
            { EqState() },
            { LoudnessState(enabled = true, currentTrackGainDb = 0f) },
            { hz },
            PitchStageFactory { _, ch -> FixedDelayFakeStage(ch, LATENCY).also { stages += it } },
        ),
        controller = controller,
        session = OutputSession(mainPlayback = true),
        platformAttributes = mockk(relaxed = true),
        bufferTier = 1,
    )

    /** [frames] stereo frames of a small ramp (well under the limiter), distinct per sample. */
    private fun ramp(frames: Int, from: Int = 0): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * FRAME).order(ByteOrder.nativeOrder())
        for (i in 0 until frames * CH) b.putFloat(sample(from * CH + i))
        b.flip()
        return b
    }

    private fun sample(i: Int): Float = ((i % 1000) + 1) / 4000f

    private fun FakeTrack.floats(): FloatArray {
        val b = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.nativeOrder())
        return FloatArray(bytes.size() / 4) { b.getFloat(it * 4) }
    }

    private fun writeAll(out: StashAudioOutput, buf: ByteBuffer) {
        var guard = 0
        while (!out.write(buf, 1, 0L)) check(++guard < 10_000) { "write never completed" }
    }

    /** DefaultAudioSink.hasAudioOutputPendingData: written frames beyond the reported position. */
    private fun sinkHasPendingData(out: StashAudioOutput, sinkWrittenFrames: Long): Boolean =
        sinkWrittenFrames > out.getPositionUs() * RATE / 1_000_000L

    @Test fun `the position is what is heard - the track's, less the stage's delay`() {
        val track = FakeTrack()
        val out = output(track)
        writeAll(out, ramp(2048))
        track.playedFrames = RATE.toLong() // one second through the track
        assertThat(out.getPositionUs()).isEqualTo(1_000_000L - LATENCY * 1_000_000L / RATE)
        track.playedFrames = 100 // still inside the stage's fill-up
        assertThat(out.getPositionUs()).isEqualTo(0L)
    }

    @Test fun `at 440 the stream passes through bit for bit, uncopied, and the position is the track's`() {
        hz = 440f
        val track = FakeTrack()
        val out = output(track)
        val buf = ramp(256)
        assertThat(out.write(buf, 1, 0L)).isTrue()
        assertThat(track.lastBuffer).isSameInstanceAs(buf)
        assertThat(stages).isEmpty()
        track.playedFrames = 4_800
        assertThat(out.getPositionUs()).isEqualTo(100_000L)
    }

    @Test fun `end of stream drains the tail - every written frame is played, the last ones intact`() {
        val track = FakeTrack()
        val out = output(track)
        val n = 3000
        writeAll(out, ramp(n))
        track.playedFrames = track.writtenFrames
        // Without the tail the sink would wait for ever: what it wrote is LATENCY ahead of the ear.
        assertThat(sinkHasPendingData(out, n.toLong())).isTrue()

        out.stop()

        assertThat(track.writtenFrames).isEqualTo((n + LATENCY).toLong())
        assertThat(track.stopCalls).isEqualTo(1)
        assertThat(track.framesAtStop).isEqualTo((n + LATENCY).toLong()) // stopped only once all of it was in
        val got = track.floats()
        for (i in 0 until LATENCY * CH) assertThat(got[i]).isEqualTo(0f)
        for (i in 0 until n * CH) assertThat(got[LATENCY * CH + i]).isWithin(1e-7f).of(sample(i))

        track.playedFrames = track.writtenFrames
        assertThat(sinkHasPendingData(out, n.toLong())).isFalse() // the sink reaches ENDED
    }

    @Test fun `a full track takes the tail as room frees up, then stops once`() {
        val track = FakeTrack()
        val out = output(track)
        val n = 2048
        writeAll(out, ramp(n))
        track.room = 0 // full

        out.stop()
        assertThat(track.stopCalls).isEqualTo(0) // the tail is still waiting
        out.stop() // a second stop doesn't start another tail
        assertThat(track.stopCalls).isEqualTo(0)

        track.room = 100 * FRAME + 3 // room for a little more each poll, not frame-aligned
        var polls = 0
        while (track.stopCalls == 0) {
            out.getPositionUs()
            check(++polls < 1_000) { "tail never finished" }
        }
        assertThat(polls).isGreaterThan(1)
        assertThat(track.writtenFrames).isEqualTo((n + LATENCY).toLong())
        assertThat(track.framesAtStop).isEqualTo((n + LATENCY).toLong())
        repeat(3) { out.getPositionUs() }
        assertThat(track.stopCalls).isEqualTo(1)
        assertThat(track.writtenFrames).isEqualTo((n + LATENCY).toLong())
    }

    @Test fun `without tuning, stop is the plain stop`() {
        hz = 440f
        val track = FakeTrack()
        val out = output(track)
        writeAll(out, ramp(1024))
        out.stop()
        assertThat(track.stopCalls).isEqualTo(1)
        assertThat(track.writtenFrames).isEqualTo(1024L)
    }

    @Test fun `a flush drops a pending tail and empties the stage`() {
        val track = FakeTrack()
        val out = output(track)
        writeAll(out, ramp(2048))
        track.room = 0
        out.stop()
        val resetsBefore = stages.single().resets

        out.flush()
        track.room = Int.MAX_VALUE
        val before = track.writtenFrames
        out.getPositionUs()
        assertThat(track.writtenFrames).isEqualTo(before) // nothing more from the old tail
        assertThat(track.stopCalls).isEqualTo(0)
        assertThat(stages.single().resets).isEqualTo(resetsBefore + 1)

        // After the flush the stage starts empty again: its delay of silence comes first.
        writeAll(out, ramp(LATENCY + 10, from = 77))
        val got = track.floats()
        val fresh = got.copyOfRange(before.toInt() * CH, got.size)
        for (i in 0 until LATENCY * CH) assertThat(fresh[i]).isEqualTo(0f)
        assertThat(fresh[LATENCY * CH]).isWithin(1e-7f).of(sample(77 * CH))
    }

    @Test fun `tuning switched on mid-stream rebuilds the stream rather than jump`() {
        hz = 440f
        val out = output(FakeTrack())
        writeAll(out, ramp(64))
        hz = 432f
        val e = assertThrows(AudioOutput.WriteException::class.java) { out.write(ramp(64), 1, 0L) }
        assertThat(e.isRecoverable).isTrue()
    }

    @Test fun `tuning switched off mid-stream rebuilds the stream too`() {
        val out = output(FakeTrack())
        writeAll(out, ramp(64))
        hz = 440f
        val e = assertThrows(AudioOutput.WriteException::class.java) { out.write(ramp(64), 1, 0L) }
        assertThat(e.isRecoverable).isTrue()
    }

    @Test fun `moving between non-standard references just changes the pitch`() {
        val out = output(FakeTrack())
        writeAll(out, ramp(64))
        hz = 444f
        assertThat(out.write(ramp(64), 1, 0L)).isTrue()
        assertThat(stages.single().factor.toDouble()).isWithin(1e-7).of(444.0 / 440.0)
    }

    @Test fun `before the first frame tuning just steps in`() {
        hz = 440f
        val track = FakeTrack()
        val out = output(track)
        hz = 432f
        writeAll(out, ramp(LATENCY + 1))
        assertThat(stages).hasSize(1)
        assertThat(track.floats()[LATENCY * CH]).isWithin(1e-7f).of(sample(0)) // delayed: the stage is in
    }

    @Test fun `bit-perfect never takes the stage`() {
        val track = FakeTrack()
        val out = output(track, bitPerfectPlan())
        out.setVolume(1f)
        assertThat(out.write(ramp(16), 1, 0L)).isTrue()
        assertThat(stages).isEmpty()
        out.stop()
        assertThat(track.stopCalls).isEqualTo(1)
    }

    @Test fun `release frees the engine`() {
        val out = output(FakeTrack())
        writeAll(out, ramp(64))
        out.release()
        assertThat(stages.single().released).isTrue()
    }
}
