package com.stash.core.media.output

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import com.stash.core.data.prefs.HiResOutputMode
import com.stash.core.data.prefs.HiResOutputPreference
import com.stash.core.media.equalizer.EqController
import com.stash.core.media.equalizer.LoudnessController
import com.stash.core.media.equalizer.dsp.dspActivation
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The decision for one output stream: what to write to the AudioTrack and
 * what (if anything) to ask of the USB mixer.
 *
 * [platformMixer] is the `AudioMixerAttributes` to apply (API 34+), kept as
 * `Any` so this class loads on every API level.
 */
class OutputPlan internal constructor(
    val decision: MixerDecision,
    val trackFormat: SampleFormat,
    val device: AudioDeviceInfo?,
    internal val platformMixer: Any?,
) {
    val mixerPath: MixerPath = when (decision) {
        is MixerDecision.Configure -> if (decision.bitPerfect) MixerPath.BIT_PERFECT else MixerPath.MATCHED
        is MixerDecision.Shared -> MixerPath.SHARED
    }
    val bitPerfect: Boolean get() = mixerPath == MixerPath.BIT_PERFECT

    /**
     * Two plans with equal keys configure the hardware identically. Only what
     * the hardware does counts: every shared plan shares one key, whatever its
     * [MixerDecision.Shared.reason] (STANDARD → MATCH on the speaker changes the
     * reason, not the output, and must not rebuild the stream) and whatever its
     * track format (a shared stream opens in the source's own format and Android
     * converts it, so the planned and the live format can differ; keying on it
     * rebuilt the stream on every recheck). The reason stays on [decision] for
     * the signal path.
     */
    val key: String = when (decision) {
        is MixerDecision.Configure ->
            "cfg:${device?.id}:${decision.option.index}:${decision.option.sampleRate}:$trackFormat"
        is MixerDecision.Shared -> "shared"
    }

    private val isRejected: Boolean get() = decision == MixerDecision.Shared(MixerDecision.Reason.REJECTED)

    /** Same plan, but the AudioTrack opens in [format] (a shared plan follows the source's own format). */
    internal fun withTrackFormat(format: SampleFormat): OutputPlan =
        OutputPlan(decision, format, device, platformMixer)

    /**
     * This plan's stream degraded to the shared mixer after Android or the DAC
     * refused it. [trackFormat] is what the AudioTrack is (or will be) open in.
     */
    internal fun rejected(trackFormat: SampleFormat = this.trackFormat): OutputPlan =
        OutputPlan(MixerDecision.Shared(MixerDecision.Reason.REJECTED), trackFormat, device, null)

    /**
     * Whether a live stream built for this plan must be rebuilt to follow
     * [fresh]. A stream already degraded by a refusal that still stands is
     * left alone even when its track format differs (its AudioTrack was opened
     * for the refused mixer, and the shared mixer takes that format as well):
     * the rebuild would only cost a re-seek.
     */
    internal fun needsRebuildFor(fresh: OutputPlan): Boolean =
        key != fresh.key && !(isRejected && fresh.isRejected)
}

/** Per-player facts the output stage needs but Media3's output config doesn't carry. */
class OutputSession(
    /**
     * This player is Stash's main playback (the service's master or its
     * crossfade spare): its streams are planned, applied to the USB mixer and
     * published as the signal path. False for any other player built on the
     * same output stage — the search preview — which keeps the EQ but plays
     * over the shared mixer and never touches the DAC, the signal path or
     * [HiResOutputController.bitPerfectEngaged].
     */
    val mainPlayback: Boolean,
) {
    /** What the player's decoder is being fed (from AnalyticsListener.onAudioInputFormatChanged). */
    @Volatile var source: SourceFormat? = null

    /** The player's current speed; bit-perfect is only possible at 1x. */
    @Volatile var speed: Float = 1f

    /**
     * Loudness gain for THIS player's current song, overriding the shared
     * LoudnessController value. During a crossfade the two players play
     * different songs; with one shared gain the incoming song faded in at the
     * outgoing song's level and then stepped (up to ~10 dB) at the swap.
     * Null until the player's first song has been looked up.
     */
    @Volatile var trackGainDb: Float? = null

    /**
     * AutoMix's per-deck low-shelf cut in dB for THIS player (0 = untouched;
     * negative removes low end). During a beat blend the playback service
     * keeps the incoming deck's bass out, then swaps it with the outgoing
     * deck's around the middle of the mix, so two basslines never play at
     * full level together. Written on the service thread, read by this
     * player's DSP chain on the audio thread.
     */
    @Volatile var autoMixLowShelfDb: Float = 0f
}

/**
 * Owns Stash's side of Android's audio routing for hi-res output:
 *
 * - **Plans** each new output stream ([prepare]): for a USB DAC on Android 14+
 *   with Hi-Res output on, picks the USB mixer configuration that matches the
 *   song (see [MixerSelection]); otherwise the shared mixer.
 * - **Applies** the plan when that stream starts playing ([activate]) — not
 *   when it is created, because the crossfade's spare player creates its
 *   output seconds before the seam, and reopening the USB output then would
 *   resample the end of the song still playing.
 * - **Re-plans** live streams when the route or the mode changes (a DAC is
 *   plugged in mid-song, the user flips the setting), by asking the stream to
 *   be rebuilt ([StashAudioOutput.requestReplan]).
 * - **Publishes** what is actually happening for the signal-path UI.
 *
 * Only the main playback's streams do any of this ([OutputSession.mainPlayback]);
 * the search preview's are ignored, so a preview can neither retune the DAC
 * under the paused song nor leave its own path (and a bit-perfect crossfade
 * hold) published after it ends.
 *
 * Mixer preferences are tied to Stash's uid by Android, so they only ever
 * affect Stash's own playback; they are cleared when the mode goes back to
 * Standard and dropped by Android when the DAC is unplugged.
 */
@Singleton
class HiResOutputController @Inject constructor(
    @ApplicationContext context: Context,
    preference: HiResOutputPreference,
    eqController: EqController,
    loudnessController: LoudnessController,
) {
    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile var mode: HiResOutputMode = HiResOutputMode.STANDARD
        private set

    /** Hi-res USB output needs Android 14's preferred mixer attributes. */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= 34

    private val _output = MutableStateFlow<OutputPathState?>(null)

    /** The audible output's path, or null before anything has played. */
    val output: StateFlow<OutputPathState?> = _output.asStateFlow()

    private val _source = MutableStateFlow<SourceFormat?>(null)
    val source: StateFlow<SourceFormat?> = _source.asStateFlow()

    /** Whether any EQ / normalization stage is currently shaping the sound. */
    val dspActive: StateFlow<Boolean> =
        combine(eqController.state, loudnessController.state) { e, l -> dspActivation(e, l).any }
            .stateIn(scope, SharingStarted.Eagerly, false)

    /** True while the audible output is bit-perfect: EQ, normalization and crossfade are bypassed. */
    val bitPerfectEngaged: StateFlow<Boolean> =
        output.map { it?.mixer == MixerPath.BIT_PERFECT }
            .stateIn(scope, SharingStarted.Eagerly, false)

    /** The listener-facing explanation of [output], ready for the signal-path sheet. */
    val signalPath: StateFlow<SignalVerdict?> =
        combine(source, output, dspActive) { s, o, d -> SignalPathExplainer.explain(s, o, d) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /** Listen Together holds bit-perfect off for its session; see [setBitPerfectHeld]. */
    @Volatile private var bitPerfectHeld = false

    private val live = CopyOnWriteArraySet<StashAudioOutput>()
    @Volatile private var active: StashAudioOutput? = null

    /**
     * Guards [active] together with [_output]: the two players' playback
     * threads and main all publish, and a delayed clear ([unregister]) must
     * never wipe a stream that started after it was scheduled.
     */
    private val publishLock = Any()

    /** How many times the audible stream was released; a pending clear acts only for the latest. Under [publishLock]. */
    private var activeReleases = 0L

    /** USB devices Stash has set a mixer preference on: id → what was applied. */
    private val applied = ConcurrentHashMap<Int, Applied>()

    private class Applied(val device: AudioDeviceInfo, val attributes: AudioAttributes, val mixer: Any)

    /** Configurations Android or the DAC refused; [prepare] plans the shared fallback for them. */
    private val refusals = RefusedConfigurations()

    init {
        scope.launch { preference.mode.collect(::onModeChanged) }
        audioManager.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    // A re-attached DAC gets another try at what it refused.
                    addedDevices.forEach { refusals.forgetDevice(it.id) }
                    recheckLiveOutputs()
                }
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    // Android drops the preference itself when the device goes away.
                    removedDevices.forEach {
                        applied.remove(it.id)
                        refusals.forgetDevice(it.id)
                    }
                    recheckLiveOutputs()
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    /**
     * Plans a new output stream for [session]'s player, at its current speed.
     * Side-effect free; called on the playback thread.
     */
    fun prepare(sampleRate: Int, channelCount: Int, session: OutputSession, attributes: AudioAttributes): OutputPlan {
        // A preview plays over the shared mixer whatever the mode: it must never retune the DAC.
        if (!session.mainPlayback) {
            return OutputPlan(MixerDecision.Shared(MixerDecision.Reason.NOT_MAIN_PLAYBACK), SampleFormat.FLOAT, null, null)
        }
        val m = mode
        val device = routedDevice(attributes)
        fun shared(reason: MixerDecision.Reason) =
            OutputPlan(MixerDecision.Shared(reason), SampleFormat.FLOAT, device, null)
        if (m == HiResOutputMode.STANDARD) return shared(MixerDecision.Reason.MODE_STANDARD)
        if (Build.VERSION.SDK_INT < 34) return shared(MixerDecision.Reason.UNSUPPORTED_ANDROID)
        if (attributes.usage != AudioAttributes.USAGE_MEDIA) return shared(MixerDecision.Reason.NOT_MEDIA_USAGE)
        if (device == null || !device.isUsb()) return shared(MixerDecision.Reason.NOT_USB)
        return refusals.screen(
            MixerApi34.plan(audioManager, device, m, sampleRate, channelCount, session.speed, bitPerfectHeld),
        )
    }

    /**
     * Applies [output]'s plan to the USB mixer and makes it the published,
     * audible output. Called from [StashAudioOutput.play] just before the
     * AudioTrack starts. A rejected preference degrades the stream to the
     * shared mixer rather than failing playback. A preview's stream is ignored.
     */
    fun activate(output: StashAudioOutput) {
        if (!output.session.mainPlayback) return
        val plan = output.plan
        val device = plan.device
        if (Build.VERSION.SDK_INT >= 34 && device != null) {
            val attributes = output.platformAttributes
            val ok = when (plan.decision) {
                is MixerDecision.Configure -> applyMixer(device, attributes, plan.platformMixer!!)
                is MixerDecision.Shared -> { clearMixer(device, attributes); true }
            }
            if (!ok) {
                refusals.remember(plan)
                output.onMixerRejected()
            }
        }
        synchronized(publishLock) {
            active = output
            _output.value = output.pathState()
        }
        output.session.source?.let { _source.value = it }
    }

    /** A new stream exists; only the main playback's are rechecked on route and mode changes. */
    fun register(output: StashAudioOutput) {
        if (output.session.mainPlayback) live += output
    }

    /**
     * [output] was released. If it was the audible stream, another of the
     * main playback's streams that is still playing takes over at once (the
     * outgoing song, when a crossfade is handed back and the incoming one is
     * stopped). Otherwise its path is cleared, unless a stream starts within
     * [RELEASE_GRACE_MS]: a seek, a rebuild (a route, mode or speed change) or
     * the next song at another rate releases one stream just before the next
     * starts, and clearing in between would blink the signal path and flick
     * crossfade's bit-perfect hold off and on. Before, a stopped player's path
     * stayed published for good: the Equalizer screen kept saying "bypassed
     * for bit-perfect" and crossfade stayed held off with nothing playing.
     */
    fun unregister(output: StashAudioOutput) {
        live -= output
        val release = synchronized(publishLock) {
            if (active !== output) return
            val stillPlaying = live.firstOrNull { it.playing }
            if (stillPlaying != null) {
                active = stillPlaying
                _output.value = stillPlaying.pathState()
                stillPlaying.session.source?.let { _source.value = it }
                return
            }
            active = null
            ++activeReleases
        }
        scope.launch {
            delay(RELEASE_GRACE_MS)
            synchronized(publishLock) {
                if (active == null && activeReleases == release) _output.value = null
            }
        }
    }

    /**
     * Holds bit-perfect off while a Listen Together session runs: the service
     * sets it on entering the session and clears it on leaving.
     *
     * Bit-perfect only holds at exactly 1x, and drift correction nudges the
     * speed between 0.97x and 1.03x every few seconds. Each nudge flipped the
     * plan between bit-perfect and matched, and each flip rebuilt the stream
     * (a re-seek), which made more drift and more nudges. Held, Bit-perfect
     * mode plans rate matching at every speed: the DAC still runs at the
     * song's rate, and a nudge no longer changes the plan.
     */
    fun setBitPerfectHeld(held: Boolean) {
        if (held == bitPerfectHeld) return
        bitPerfectHeld = held
        // Logged at the wiring site: unit tests prove the planning, not that the service calls this.
        Log.i(TAG, if (held) "bit-perfect held off for Listen Together" else "bit-perfect hold released")
        recheckLiveOutputs()
    }

    /** The player behind [session] started decoding a new source. */
    fun onSourceFormat(session: OutputSession, source: SourceFormat) {
        session.source = source
        if (active?.session === session) _source.value = source
    }

    /** [output]'s AudioTrack moved to another device (playback thread). */
    fun onRouted(output: StashAudioOutput) {
        if (!output.session.mainPlayback) return
        publishIfActive(output)
        recheck(output)
    }

    /** [output]'s speed changed; bit-perfect can only hold at 1x. */
    fun onSpeedChanged(output: StashAudioOutput) {
        recheck(output)
    }

    /** An AudioTrack wouldn't open in [plan]'s format (playback thread); see [StashAudioOutputProvider]. */
    internal fun onTrackRefused(plan: OutputPlan) = refusals.remember(plan)

    /** Clears every mixer preference Stash set (service shutdown). */
    fun releaseAll() {
        if (Build.VERSION.SDK_INT < 34) return
        applied.keys.toList().forEach { id -> applied[id]?.let { clearMixer(it.device) } }
    }

    private fun onModeChanged(newMode: HiResOutputMode) {
        if (newMode == mode) return
        mode = newMode
        if (newMode == HiResOutputMode.STANDARD) releaseAll()
        // A new mode asks for different things: let refused configurations be tried again.
        refusals.clear()
        recheckLiveOutputs()
    }

    private fun recheckLiveOutputs() = live.forEach(::recheck)

    private fun recheck(output: StashAudioOutput) {
        if (!output.session.mainPlayback) return
        val fresh = runCatching {
            prepare(output.streamSampleRate, output.channelCount, output.session, output.platformAttributes)
        }.getOrNull() ?: return
        val current = output.plan
        when {
            // Same hardware set-up: keep the stream, but take the fresh plan's
            // reason and device so the signal path tells today's story.
            fresh.key == current.key -> {
                output.adopt(fresh)
                publishIfActive(output)
            }
            current.needsRebuildFor(fresh) -> output.requestReplan()
        }
    }

    /** Republishes [output]'s path when it is the audible stream. */
    private fun publishIfActive(output: StashAudioOutput) {
        synchronized(publishLock) {
            if (active === output) _output.value = output.pathState()
        }
    }

    @RequiresApi(34)
    private fun applyMixer(device: AudioDeviceInfo, attributes: AudioAttributes, mixer: Any): Boolean {
        val current = applied[device.id]
        if (current != null && current.mixer == mixer) return true
        val ok = try {
            audioManager.setPreferredMixerAttributes(attributes, device, mixer as AudioMixerAttributes)
        } catch (e: RuntimeException) {
            Log.w(TAG, "setPreferredMixerAttributes failed", e)
            false
        }
        if (ok) applied[device.id] = Applied(device, attributes, mixer)
        else applied.remove(device.id)
        Log.i(TAG, "mixer preference ${if (ok) "set" else "REJECTED"}: $mixer on ${device.productName}")
        return ok
    }

    @RequiresApi(34)
    private fun clearMixer(device: AudioDeviceInfo, attributes: AudioAttributes? = null) {
        val current = applied.remove(device.id)
        // Not tracked (the app restarted since it was set): still clear whatever Android holds.
        val attrs = current?.attributes ?: attributes ?: return
        try {
            if (audioManager.getPreferredMixerAttributes(attrs, device) != null) {
                audioManager.clearPreferredMixerAttributes(attrs, device)
                Log.i(TAG, "cleared a leftover mixer preference on ${device.productName}")
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "clearPreferredMixerAttributes failed", e)
        }
    }

    private fun routedDevice(attributes: AudioAttributes): AudioDeviceInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching { audioManager.getAudioDevicesForAttributes(attributes).firstOrNull() }.getOrNull()
        } else {
            null
        }

    /** The shared mixer's rate for music right now (what Android resamples to). */
    internal fun sharedMixerSampleRate(): Int? =
        runCatching { AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC) }
            .getOrNull()?.takeIf { it > 0 }

    companion object {
        private const val TAG = "HiResOutput"

        /** How long a released stream's path stays published waiting for its successor (see [unregister]). */
        internal const val RELEASE_GRACE_MS = 1_000L

        fun AudioDeviceInfo.isUsb(): Boolean = type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_ACCESSORY

        fun AudioDeviceInfo.toOutputDevice(): OutputDevice {
            val kind = when (type) {
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputDeviceKind.USB
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> OutputDeviceKind.BLUETOOTH
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_AUX_LINE -> OutputDeviceKind.WIRED
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> OutputDeviceKind.SPEAKER
                else -> OutputDeviceKind.OTHER
            }
            val name = when (kind) {
                OutputDeviceKind.SPEAKER -> "Phone speaker"
                OutputDeviceKind.WIRED -> "Wired headphones"
                else -> productName?.toString()?.takeIf { it.isNotBlank() } ?: "Audio device"
            }
            return OutputDevice(kind, name)
        }

        internal fun platformEncodingOf(format: SampleFormat): Int = when (format) {
            SampleFormat.INT16 -> AudioFormat.ENCODING_PCM_16BIT
            SampleFormat.INT24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            SampleFormat.INT32 -> AudioFormat.ENCODING_PCM_32BIT
            SampleFormat.FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
        }
    }
}

/** Everything that touches API 34 classes, isolated so older devices never load it. */
@RequiresApi(34)
private object MixerApi34 {
    fun plan(
        audioManager: AudioManager,
        device: AudioDeviceInfo,
        mode: HiResOutputMode,
        sampleRate: Int,
        channelCount: Int,
        speed: Float,
        bitPerfectHeld: Boolean,
    ): OutputPlan {
        val platform: List<AudioMixerAttributes> =
            runCatching { audioManager.getSupportedMixerAttributes(device) }.getOrDefault(emptyList())
        val options = platform.mapIndexed { i, a ->
            val f = a.format
            MixerOption(
                format = when (f.encoding) {
                    AudioFormat.ENCODING_PCM_16BIT -> SampleFormat.INT16
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> SampleFormat.INT24
                    AudioFormat.ENCODING_PCM_32BIT -> SampleFormat.INT32
                    AudioFormat.ENCODING_PCM_FLOAT -> SampleFormat.FLOAT
                    else -> null // e.g. 8.24 fixed point: not something an AudioTrack takes from us
                },
                sampleRate = f.sampleRate,
                channelCount = f.channelCount,
                bitPerfect = a.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT,
                index = i,
            )
        }
        return when (val d = MixerSelection.choose(options, mode, sampleRate, channelCount, speed, bitPerfectHeld)) {
            is MixerDecision.Configure -> OutputPlan(d, d.trackFormat, device, platform[d.option.index])
            is MixerDecision.Shared -> OutputPlan(d, SampleFormat.FLOAT, device, null)
        }
    }
}
