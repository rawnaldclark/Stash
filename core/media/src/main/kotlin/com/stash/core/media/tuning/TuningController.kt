package com.stash.core.media.tuning

import android.os.Looper
import com.stash.core.data.prefs.TuningPreference
import com.stash.core.media.equalizer.dsp.PitchStage
import com.stash.core.media.equalizer.dsp.SignalsmithPitchStage
import com.stash.core.model.TuningMath
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** The pitch engine behind Tuning. One implementation ships ([SignalsmithTuningEngine]); tests fake it. */
interface TuningEngine {
    /** Whether this phone has the engine (its native library loaded). */
    val available: Boolean

    /** A stage for one stream, or null without the engine. */
    fun newStage(sampleRate: Int, channels: Int): PitchStage?
}

/** [TuningEngine] on Signalsmith Stretch. */
object SignalsmithTuningEngine : TuningEngine {
    override val available: Boolean get() = SignalsmithPitchStage.isAvailable
    override fun newStage(sampleRate: Int, channels: Int): PitchStage? = SignalsmithPitchStage.create(sampleRate, channels)
}

/**
 * Tuning: the concert pitch (A4, in Hz) everything Stash plays is retuned to, at the same speed.
 *
 * The listener's choice lives in [TuningPreference]; this mirrors it for playback. Every output
 * stage reads [outputHz] on each buffer (its [com.stash.core.media.equalizer.dsp.FloatDspChain]
 * puts the pitch stage first while the reference isn't 440), and [HiResOutputController] reads
 * [isRetuned] to plan the output (a retuned stream can't be bit-perfect) and to say so on the signal
 * path. Global, not per song; local to this phone (not part of a Listen Together room).
 *
 * Moving between two non-standard values (432 to 444) changes the pitch live. Turning Tuning on or
 * off changes the stage's delay, so each output rebuilds itself at its current position (a seek),
 * the way it does for a route change.
 */
@Singleton
class TuningController internal constructor(
    private val preference: TuningPreference,
    private val engine: TuningEngine,
) {
    @Inject constructor(preference: TuningPreference) : this(preference, SignalsmithTuningEngine)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stored = MutableStateFlow(TuningMath.STANDARD_HZ)
    @Volatile private var loaded = false

    /** Stream formats (rate and channels) the engine failed to build a stage for, since the last change. */
    private val failedFormats: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val _stageFailures = MutableStateFlow(0)

    /** Counts stages that couldn't be built; [HiResOutputController] replans when it moves. */
    val stageFailures: StateFlow<Int> = _stageFailures.asStateFlow()

    /** Whether this phone can retune at all; false shows Tuning as unavailable. */
    val available: Boolean get() = engine.available

    /** The listener's chosen reference (A4, Hz). Standard until the store has been read. */
    val referenceHz: StateFlow<Float> = stored.asStateFlow()

    /** What playback is retuned to: the chosen reference, or 440 where the engine is missing. */
    val effectiveHz: StateFlow<Float> = stored
        .map { if (engine.available) it else TuningMath.STANDARD_HZ }
        .stateIn(scope, SharingStarted.Eagerly, TuningMath.STANDARD_HZ)

    init {
        scope.launch {
            preference.referenceHz.collect {
                val hz = TuningMath.sanitize(it)
                if (hz != stored.value) failedFormats.clear() // a new choice: let every format try again
                stored.value = hz
                loaded = true
            }
        }
    }

    /**
     * The store's value read directly, once, for a stream that starts before the collection above
     * has delivered it (playback resumed with the app's launch). Without it that stream would open
     * at 440 and rebuild itself a moment later when the real value arrived.
     */
    private val firstRead: Float? by lazy {
        // A read that fails (a disk error the store doesn't absorb) falls back to what's held; once.
        runCatching {
            runBlocking { withTimeoutOrNull(FIRST_READ_TIMEOUT_MS) { preference.referenceHz.first() } }
        }.getOrNull()?.let(TuningMath::sanitize)
    }

    /**
     * The reference the output stage retunes to, read per buffer on a playback thread. Before the
     * store's value has arrived it reads the store itself (briefly blocking, never on main).
     */
    fun outputHz(): Float {
        if (!engine.available) return TuningMath.STANDARD_HZ
        if (!loaded && !onMainThread()) firstRead?.let { return it }
        return stored.value
    }

    /** True while music is retuned: a non-standard reference, and an engine to do it. */
    fun isRetuned(): Boolean = TuningMath.isRetuned(outputHz())

    /**
     * Whether a stream of this format will be retuned, for planning its output: [isRetuned], unless
     * the engine already failed to build a stage for the format (then it plays untuned, and can
     * have bit-perfect back).
     */
    fun isRetunedFor(sampleRate: Int, channels: Int): Boolean =
        isRetuned() && formatKey(sampleRate, channels) !in failedFormats

    /** The pitch stage for one stream, or null without the engine or when it can't be built. */
    fun newStage(sampleRate: Int, channels: Int): PitchStage? {
        if (!engine.available) return null
        val stage = runCatching { engine.newStage(sampleRate, channels) }.getOrNull()
        if (stage == null && failedFormats.add(formatKey(sampleRate, channels))) _stageFailures.update { it + 1 }
        return stage
    }

    private fun formatKey(sampleRate: Int, channels: Int): Long = (sampleRate.toLong() shl 16) or channels.toLong()

    /** Stores a new reference (sanitized); playback follows within a buffer of the store's update. */
    fun setReferenceHz(hz: Float) {
        scope.launch { preference.setReferenceHz(TuningMath.sanitize(hz)) }
    }

    private fun onMainThread(): Boolean = Looper.getMainLooper()?.isCurrentThread == true

    private companion object {
        const val FIRST_READ_TIMEOUT_MS = 1_000L
    }
}
