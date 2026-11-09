package com.stash.core.media.output

import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import com.stash.core.media.equalizer.dsp.FloatDspChain
import com.stash.core.media.equalizer.dsp.GainRamp
import com.stash.core.media.output.HiResOutputController.Companion.toOutputDevice
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stash's own output stage: the last thing the audio passes through before
 * Android's `AudioTrack`.
 *
 * Media3's `DefaultAudioSink` hands us the decoded audio — float for almost
 * everything, since float output is on — and this stage:
 *
 * 1. runs Stash's Tuning / EQ / bass / loudness / limiter on it in float
 *    ([FloatDspChain]), only while a stage is actually doing something;
 * 2. converts to whatever the AudioTrack was opened with: float for the
 *    shared or rate-matched mixer, the DAC's exact integer format for
 *    bit-perfect ([ConvertingWriter] — exact for ≤24-bit sources, TPDF
 *    dither only where a sample genuinely doesn't fit);
 * 3. in bit-perfect mode, applies the player's volume itself (Android applies
 *    none there), so a duck or a sleep-timer fade still works and unity volume
 *    stays bit-perfect.
 *
 * With nothing to do (the default: EQ off, normalization unmeasured, Tuning at
 * 440 Hz, float in and out) the buffer goes straight to the AudioTrack,
 * untouched and uncopied.
 *
 * Frame accounting is exact (see [ConvertingWriter]). Only Tuning adds latency
 * (its pitch stage delays the audio by 200 ms, frames still 1:1), and only
 * while it is in the path, so this stage owns the consequences:
 * - [getPositionUs] reports what is being heard, not what was written, so
 *   lyrics and Listen Together's drift measurement stay in time;
 * - [stop] (end of stream) feeds the stage that much silence so the last
 *   200 ms of the song reach the AudioTrack (DefaultAudioSink waits until
 *   the position reaches what it wrote: without the tail the queue's last
 *   song would never end);
 * - Tuning switched on or off mid-stream rebuilds the stream at its current
 *   position ([requestReplan]), since its delay can't change without a jump.
 * Otherwise position, gapless and end-of-stream behave as without us. The
 * AudioTrack's own buffer is kept at Media3's 1x size up to 2x speed
 * ([TrackBufferSizing]).
 */
@OptIn(UnstableApi::class)
class StashAudioOutput(
    private val inner: AudioOutput,
    private val inFormat: SampleFormat,
    private val outFormat: SampleFormat,
    val channelCount: Int,
    /** The stream's sample rate (named apart from AudioOutput.getSampleRate, the track's). */
    val streamSampleRate: Int,
    plan: OutputPlan,
    private val dsp: FloatDspChain,
    private val controller: HiResOutputController,
    val session: OutputSession,
    val platformAttributes: android.media.AudioAttributes,
    /** The speed tier the AudioTrack's buffer was sized for ([TrackBufferSizing.tierFor]). */
    private val bufferTier: Int,
) : ForwardingAudioOutput(inner) {

    /** The plan this stream was built for; degraded to shared if Android rejects the mixer request. */
    @Volatile var plan: OutputPlan = plan
        private set

    private val writer = ConvertingWriter(inFormat, outFormat, channelCount)
    private val inFrameSize = inFormat.frameSize(channelCount)
    private val volume = GainRamp().apply { configure(streamSampleRate) }
    private var playerVolume = 1f
    private var framesWritten = 0L
    private var lastPresentationTimeUs = 0L

    /**
     * Silence still to be pushed through Tuning's stage at end of stream, carrying out the
     * song's last frames; null otherwise. See [stop].
     */
    private var tail: ByteBuffer? = null

    /** [stop] came for this stream (end of stream); only a flush starts it again. */
    private var stopping = false

    @Volatile private var replanRequested = false

    /** The reference this stream is audibly retuned to (null: not retuned), as last published. */
    @Volatile private var publishedTuningHz: Float? = null

    /** Between play() and pause() or release(); see [HiResOutputController.unregister]. */
    @Volatile internal var playing = false
        private set

    private val audioTrack: AudioTrack? = (inner as? AudioTrackAudioOutput)?.audioTrack
    private val routingListener = AudioRouting.OnRoutingChangedListener { controller.onRouted(this) }

    init {
        dsp.configure(streamSampleRate, channelCount)
        dsp.engageTuning(tuningWantedHere())
        publishedTuningHz = if (bitPerfect) null else dsp.retunedToHz
        try {
            controller.register(this)
            Looper.myLooper()?.let { audioTrack?.addOnRoutingChangedListener(routingListener, Handler(it)) }
        } catch (t: Throwable) {
            dsp.release() // nobody will release a stream that never finished being built
            throw t
        }
    }

    private val bitPerfect: Boolean get() = plan.bitPerfect

    /** Tuning belongs in this stream: wanted, and not bit-perfect (whose plan never has it). */
    private fun tuningWantedHere(): Boolean = !bitPerfect && dsp.tuningWanted()

    /** How far behind what was written the listener is, from Tuning's stage (0 without it). */
    private val latencyUs: Long
        get() = dsp.latencyFrames.let { if (it == 0) 0L else it * 1_000_000L / streamSampleRate }

    /**
     * Asks for this stream to be rebuilt with a fresh plan (route or mode
     * changed under it). Honoured on the next write while it plays by failing
     * it as "recoverable": ExoPlayer then recreates the audio output in place
     * (a seek to the current position — the same path it uses when Android
     * kills an AudioTrack), and [StashAudioOutputProvider] plans the new one.
     *
     * Only while playing: ExoPlayer turns a second recoverable error into a
     * fatal one until the player is READY again, and a paused or buffering
     * player (one re-buffering after the last rebuild, or a seek) still writes
     * to pre-fill its track. A fatal error would skip the song or switch it to
     * streaming. play() comes only once READY, so the rebuild waits for it.
     */
    fun requestReplan() {
        replanRequested = true
    }

    /** Android refused the mixer preference: carry on, honestly, on the shared mixer. */
    internal fun onMixerRejected() {
        plan = plan.rejected()
        inner.setVolume(playerVolume)
    }

    /**
     * Takes [fresh] in place of the current plan when it configures the
     * hardware identically (equal [OutputPlan.key]), so the signal path shows
     * the current reason and device without rebuilding the stream. Equal keys
     * mean the same mixer path, so volume and DSP handling don't change.
     */
    internal fun adopt(fresh: OutputPlan) {
        if (fresh.key == plan.key) plan = fresh
    }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        lastPresentationTimeUs = presentationTimeUs
        // Tuning switched on or off under this stream. Before its first frame (or after a
        // flush) the stage just steps in or out; mid-stream its delay would skip or repeat a
        // moment, so the stream is rebuilt at its position instead, like a route change: once it
        // plays (see requestReplan). Until then it carries on as it is, and a change undone in the
        // meantime (432, then back to 440, while re-buffering) rebuilds nothing.
        if (dsp.tuningEngaged != tuningWantedHere()) {
            if (framesWritten == 0L) dsp.engageTuning(!dsp.tuningEngaged) else if (playing) replanRequested = true
        }
        // DefaultAudioSink only treats the error as recoverable once audio has
        // been written; before that it would be fatal, so wait one buffer.
        if (replanRequested && framesWritten > 0 && playing) {
            replanRequested = false
            throw AudioOutput.WriteException(AudioTrack.ERROR_DEAD_OBJECT, /* isRecoverable= */ true)
        }
        val dspOn = !bitPerfect && dsp.isActive()
        val softVolume = bitPerfect && (playerVolume != 1f || volume.isActive)
        val before = buffer.position()
        val handled = if (!writer.hasPending && !dspOn && !softVolume && inFormat == outFormat) {
            inner.write(buffer, encodedAccessUnitCount, presentationTimeUs)
        } else {
            writer.write(
                buffer,
                process = { samples, frames ->
                    if (dspOn) dsp.process(samples, frames)
                    if (softVolume) volume.apply(samples, frames, channelCount)
                },
                sink = { out -> inner.write(out, 1, presentationTimeUs) },
            )
        }
        framesWritten += (buffer.position() - before) / inFrameSize
        publishTuning()
        return handled
    }

    /** Tells the controller when what this stream is retuned to changed (the signal path says it). */
    private fun publishTuning() {
        val hz = if (bitPerfect) null else dsp.retunedToHz
        if (hz != publishedTuningHz) {
            publishedTuningHz = hz
            controller.onTuningApplied(this)
        }
    }

    override fun play() {
        playing = true
        controller.activate(this)
        super.play()
    }

    override fun pause() {
        playing = false
        super.pause()
    }

    override fun flush() {
        // Mirrors DefaultAudioSink, which zeroes its written-frame count on
        // flush: a replan thrown before any new audio is written after a seek
        // would reach it as NON-recoverable and stop playback.
        framesWritten = 0
        tail = null
        stopping = false
        writer.flush()
        dsp.reset()
        volume.snapTo(playerVolume)
        super.flush()
    }

    override fun setVolume(volume: Float) {
        playerVolume = volume
        if (bitPerfect) {
            // Android applies no volume to a bit-perfect stream: do it here,
            // ramped, and keep the track itself at unity.
            this.volume.setTarget(volume)
            inner.setVolume(1f)
        } else {
            inner.setVolume(volume)
        }
    }

    override fun setPlaybackParameters(playbackParams: PlaybackParameters) {
        super.setPlaybackParameters(playbackParams)
        val speed = playbackParams.speed
        if (session.speed != speed) {
            session.speed = speed
            // Crossing 2x either way: a track sized for its new speed. Above
            // that this buffer would run thin; below, the doubled one only
            // delays the EQ. Listen Together's nudges and 0.5–2x never cross.
            if (TrackBufferSizing.tierFor(speed) != bufferTier) requestReplan()
            controller.onSpeedChanged(this)
        }
    }

    /**
     * The listener's position: the AudioTrack's, less Tuning's delay while its stage is in the
     * path. DefaultAudioSink uses this for the playback position (lyrics, seeking, Listen
     * Together's drift) and for "has everything played", so it must be the audible one.
     *
     * At end of stream it also feeds the AudioTrack the rest of the tail as room frees up
     * ([stop]); the sink polls this until the position reaches what it wrote.
     */
    override fun getPositionUs(): Long {
        if (tail != null && pushTail()) super.stop()
        val position = super.getPositionUs()
        val lag = latencyUs
        return if (lag == 0L || position <= 0L) position else maxOf(0L, position - lag)
    }

    /**
     * End of stream: with Tuning in the path, the song's last 200 ms are still inside its stage.
     * Feed it as much silence as its delay, through the same writer, so they reach the AudioTrack
     * and the written and played frame counts meet; only then stop the AudioTrack (which plays out
     * what it holds). What the AudioTrack has no room for yet goes in from [getPositionUs], which
     * the sink keeps polling. Without Tuning this is the plain stop.
     */
    override fun stop() {
        if (stopping) return
        stopping = true
        val lag = dsp.latencyFrames
        if (lag > 0 && !bitPerfect) {
            tail = ByteBuffer.allocateDirect(lag * inFrameSize).order(ByteOrder.nativeOrder())
            if (!pushTail()) return
        }
        super.stop()
    }

    /** Writes what the AudioTrack takes of [tail]; true once all of it is in. */
    private fun pushTail(): Boolean {
        val t = tail ?: return true
        val done = try {
            writer.write(
                t,
                process = { samples, frames -> dsp.process(samples, frames) },
                sink = { out -> inner.write(out, 1, lastPresentationTimeUs) },
            )
        } catch (e: Exception) {
            // The AudioTrack is gone (or refused the write): nothing more can be played from it.
            Log.w(TAG, "tuning tail not written: ${e.javaClass.simpleName}")
            true
        }
        if (done) tail = null
        return done
    }

    override fun release() {
        try {
            playing = false
            tail = null
            audioTrack?.removeOnRoutingChangedListener(routingListener)
            controller.unregister(this)
            super.release()
        } finally {
            dsp.release() // the pitch stage's native memory, whatever happened above
        }
    }

    private companion object {
        const val TAG = "StashAudioOutput"
    }

    /** What this stream looks like right now, for the signal path. */
    internal fun pathState(): OutputPathState {
        val p = plan
        val decision = p.decision
        val routed = audioTrack?.routedDevice ?: p.device
        return OutputPathState(
            sampleRate = streamSampleRate,
            channelCount = channelCount,
            decodedFormat = inFormat,
            trackFormat = outFormat,
            mixer = p.mixerPath,
            mixerSampleRate = when (decision) {
                is MixerDecision.Configure -> decision.option.sampleRate
                is MixerDecision.Shared -> controller.sharedMixerSampleRate()
            },
            mixerFormat = (decision as? MixerDecision.Configure)?.option?.format,
            device = routed?.toOutputDevice(),
            mode = controller.mode,
            bitPerfectMiss = (decision as? MixerDecision.Configure)?.bitPerfectMiss,
            sharedReason = (decision as? MixerDecision.Shared)?.reason,
            tuningHz = publishedTuningHz,
        )
    }
}
