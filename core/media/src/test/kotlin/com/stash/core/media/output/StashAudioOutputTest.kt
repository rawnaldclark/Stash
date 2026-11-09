package com.stash.core.media.output

import android.media.AudioDeviceInfo
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOutput
import com.google.common.truth.Truth.assertThat
import com.stash.core.media.equalizer.EqState
import com.stash.core.media.equalizer.LoudnessState
import com.stash.core.media.equalizer.dsp.FloatDspChain
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The output stage against a fake platform output: zero-copy passthrough when
 * idle, exact conversion and accounting otherwise, bit-perfect volume handled
 * in software, and replan delivered as a recoverable write error.
 */
class StashAudioOutputTest {

    /** Records what reaches the "AudioTrack"; accepts up to [capacity] bytes per write. */
    private class FakeOutput(var capacity: Int = Int.MAX_VALUE) : AudioOutput {
        val bytes = ByteArrayOutputStream()
        var lastBuffer: ByteBuffer? = null
        var lastVolume = -1f
        override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
            lastBuffer = buffer
            val n = minOf(capacity, buffer.remaining())
            repeat(n) { bytes.write(buffer.get().toInt()) }
            return !buffer.hasRemaining()
        }
        override fun play() = Unit
        override fun pause() = Unit
        override fun flush() = Unit
        override fun stop() = Unit
        override fun release() = Unit
        override fun setVolume(volume: Float) { lastVolume = volume }
        override fun isOffloadedPlayback() = false
        override fun getAudioSessionId() = 0
        override fun getSampleRate() = 48_000
        override fun getBufferSizeInFrames() = 0L
        override fun getPositionUs() = 0L
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
    private var eq = EqState()
    private var loudness = LoudnessState(enabled = true, currentTrackGainDb = 0f)

    private fun sharedPlan() =
        OutputPlan(MixerDecision.Shared(MixerDecision.Reason.MODE_STANDARD), SampleFormat.FLOAT, null, null)

    private fun bitPerfectPlan(format: SampleFormat): OutputPlan {
        val option = MixerOption(format, 48_000, 2, bitPerfect = true, index = 0)
        return OutputPlan(MixerDecision.Configure(option, format), format, null, Any())
    }

    private fun output(
        inner: FakeOutput,
        inFormat: SampleFormat,
        plan: OutputPlan,
        speed: Float = 1f,
    ) = StashAudioOutput(
        inner = inner,
        inFormat = inFormat,
        outFormat = plan.trackFormat,
        channelCount = 2,
        streamSampleRate = 48_000,
        plan = plan,
        dsp = FloatDspChain({ eq }, { loudness }),
        controller = controller,
        session = OutputSession(mainPlayback = true).also { it.speed = speed },
        platformAttributes = mockk(relaxed = true),
        bufferTier = TrackBufferSizing.tierFor(speed),
    )

    private fun floats(vararg v: Float): ByteBuffer =
        ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).apply { v.forEach { putFloat(it) }; flip() }

    private fun ByteArrayOutputStream.asBuffer(): ByteBuffer =
        ByteBuffer.wrap(toByteArray()).order(ByteOrder.nativeOrder())

    @Test fun `idle float stream goes to the AudioTrack untouched and uncopied`() {
        val inner = FakeOutput()
        val out = output(inner, SampleFormat.FLOAT, sharedPlan())
        val buf = floats(0.1f, -0.2f, 0.3f, -0.4f)
        assertThat(out.write(buf, 1, 0L)).isTrue()
        assertThat(inner.lastBuffer).isSameInstanceAs(buf)
    }

    @Test fun `16-bit decoder output is widened to float exactly`() {
        val inner = FakeOutput()
        val out = output(inner, SampleFormat.INT16, sharedPlan())
        val buf = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
            .putShort(16384).putShort(-32768).putShort(1).putShort(0).apply { flip() }
        assertThat(out.write(buf, 1, 0L)).isTrue()
        val got = inner.bytes.asBuffer()
        assertThat(got.getFloat(0)).isEqualTo(0.5f)
        assertThat(got.getFloat(4)).isEqualTo(-1f)
        assertThat(got.getFloat(8)).isEqualTo(1f / 32768f)
    }

    @Test fun `active EQ shapes the stream, partial writes keep exact accounting`() {
        eq = EqState(enabled = true, preampDb = -6.0206f)
        val inner = FakeOutput(capacity = 12) // 1.5 float frames per call
        val out = output(inner, SampleFormat.FLOAT, sharedPlan())
        val buf = floats(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f)
        var calls = 0
        while (!out.write(buf, 1, 0L)) {
            calls++
            // never consumes a frame the "AudioTrack" didn't take
            assertThat(buf.position() % 8).isEqualTo(0)
        }
        assertThat(calls).isGreaterThan(0)
        val got = inner.bytes.asBuffer()
        assertThat(got.getFloat(0)).isWithin(1e-4f).of(0.25f)
    }

    @Test fun `bit-perfect at unity volume is the exact integer image of the source`() {
        eq = EqState(enabled = true, gainsDb = floatArrayOf(6f, 6f, 6f, 6f, 6f)) // must be bypassed
        val inner = FakeOutput()
        val out = output(inner, SampleFormat.FLOAT, bitPerfectPlan(SampleFormat.INT24))
        out.setVolume(1f)
        val x = 1234567 / 8388608f
        assertThat(out.write(floats(x, -x), 1, 0L)).isTrue()
        val b = inner.bytes.toByteArray()
        val left = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or (b[2].toInt() shl 16)
        assertThat(left).isEqualTo(1234567)
        assertThat(inner.lastVolume).isEqualTo(1f)
    }

    @Test fun `bit-perfect applies the player's volume itself, keeping the track at unity`() {
        val inner = FakeOutput()
        val out = output(inner, SampleFormat.FLOAT, bitPerfectPlan(SampleFormat.INT32))
        out.setVolume(0.25f)
        assertThat(inner.lastVolume).isEqualTo(1f)
        out.flush() // snaps the ramp to the target
        out.write(floats(0.5f, 0.5f), 1, 0L)
        val v = inner.bytes.asBuffer().getInt(0) / 2147483648.0
        assertThat(v).isWithin(1e-6).of(0.125)
    }

    @Test fun `replan waits for written audio, then fails once as recoverable`() {
        val inner = FakeOutput()
        val out = output(inner, SampleFormat.FLOAT, sharedPlan())
        out.play()
        out.requestReplan()
        // Nothing written yet: a failure now would be fatal, so it waits.
        assertThat(out.write(floats(0f, 0f), 1, 0L)).isTrue()
        val e = assertThrows(AudioOutput.WriteException::class.java) { out.write(floats(0f, 0f), 1, 0L) }
        assertThat(e.isRecoverable).isTrue()
        assertThat(out.write(floats(0f, 0f), 1, 0L)).isTrue() // only once
    }

    @Test fun `after a seek, replan again waits for fresh audio`() {
        // DefaultAudioSink zeroes its written count on flush; a replan thrown
        // before new audio would reach it as non-recoverable (fatal).
        val inner = FakeOutput()
        val out = output(inner, SampleFormat.FLOAT, sharedPlan())
        out.play()
        out.write(floats(0f, 0f), 1, 0L)
        out.flush()
        out.requestReplan()
        assertThat(out.write(floats(0f, 0f), 1, 0L)).isTrue()
        assertThrows(AudioOutput.WriteException::class.java) { out.write(floats(0f, 0f), 1, 0L) }
    }

    @Test fun `replan waits for play - a paused or buffering player only pre-fills`() {
        // ExoPlayer makes a second recoverable error fatal until READY, and play() comes after READY.
        val out = output(FakeOutput(), SampleFormat.FLOAT, sharedPlan())
        out.write(floats(0f, 0f), 1, 0L)
        out.requestReplan()
        repeat(3) { assertThat(out.write(floats(0f, 0f), 1, 0L)).isTrue() } // pre-filling: no rebuild
        out.play()
        assertThrows(AudioOutput.WriteException::class.java) { out.write(floats(0f, 0f), 1, 0L) }
        assertThat(out.write(floats(0f, 0f), 1, 0L)).isTrue() // once
    }

    @Test fun `play activates the stream with the controller`() {
        val out = output(FakeOutput(), SampleFormat.FLOAT, sharedPlan())
        out.play()
        verify { controller.activate(out) }
    }

    @Test fun `Listen Together's speed nudges keep the track`() {
        // Drift correction steers between 0.97x and 1.03x: a rebuild per nudge was a re-seek per nudge.
        val out = output(FakeOutput(), SampleFormat.FLOAT, sharedPlan())
        out.write(floats(0f, 0f), 1, 0L)
        out.setPlaybackParameters(PlaybackParameters(1.03f))
        out.setPlaybackParameters(PlaybackParameters(0.97f))
        out.setPlaybackParameters(PlaybackParameters(1f))
        assertThat(out.write(floats(0f, 0f), 1, 0L)).isTrue() // no replan thrown
        verify(exactly = 3) { controller.onSpeedChanged(out) }
    }

    @Test fun `everyday speeds up to 2x keep the 1x-sized track`() {
        val out = output(FakeOutput(), SampleFormat.FLOAT, sharedPlan())
        out.write(floats(0f, 0f), 1, 0L)
        out.setPlaybackParameters(PlaybackParameters(1.5f))
        out.setPlaybackParameters(PlaybackParameters(2f))
        assertThat(out.write(floats(0f, 0f), 1, 0L)).isTrue()
    }

    @Test fun `a speed past 2x rebuilds the track at the larger size`() {
        val out = output(FakeOutput(), SampleFormat.FLOAT, sharedPlan())
        out.play()
        out.write(floats(0f, 0f), 1, 0L)
        out.setPlaybackParameters(PlaybackParameters(3f))
        val e = assertThrows(AudioOutput.WriteException::class.java) { out.write(floats(0f, 0f), 1, 0L) }
        assertThat(e.isRecoverable).isTrue()
    }

    @Test fun `back from 3x to 1x the doubled track is rebuilt, so the EQ answers quickly again`() {
        val out = output(FakeOutput(), SampleFormat.FLOAT, sharedPlan(), speed = 3f)
        out.play()
        out.write(floats(0f, 0f), 1, 0L)
        out.setPlaybackParameters(PlaybackParameters(4f)) // same tier: kept
        assertThat(out.write(floats(0f, 0f), 1, 0L)).isTrue()
        out.setPlaybackParameters(PlaybackParameters(1f))
        assertThrows(AudioOutput.WriteException::class.java) { out.write(floats(0f, 0f), 1, 0L) }
    }
}
