package com.stash.core.media.equalizer

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton that owns all audio-effect instances ([Equalizer], [BassBoost],
 * [Virtualizer], [LoudnessEnhancer]) for the lifetime of the current playback
 * session.
 *
 * ## Lifecycle
 * 1. [StashPlaybackService.onCreate] calls [initialize] with the ExoPlayer audio session ID.
 * 2. The manager creates effects, reads persisted settings from [EqualizerStore], and applies them.
 * 3. UI / ViewModel calls the setter methods; each change is saved to the store automatically.
 * 4. [StashPlaybackService.onDestroy] calls [release] to free native resources.
 *
 * ## Device compatibility
 * Not all devices support every effect. Each effect is created inside a try/catch so a
 * failure on one effect does not prevent the others from initialising.
 *
 * ## Band count mismatch
 * Preset gain arrays assume 5 bands. If the device has a different count, gains are
 * adapted via linear interpolation (see [adaptGains]).
 */
@Singleton
class EqualizerManager @Inject constructor(
    private val equalizerStore: EqualizerStore,
) {

    companion object {
        private const val TAG = "EqualizerManager"

        /** Effect priority. 0 = normal, higher = higher priority. */
        private const val EFFECT_PRIORITY = 0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- Effect instances (null when uninitialised or unsupported) ----

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    // ---- Public read-only properties (populated after initialize) ----

    /** Number of frequency bands reported by the device's equalizer. */
    var bandCount: Int = 0
        private set

    /** Center frequencies for each band, in millihertz. */
    var bandFrequencies: List<Int> = emptyList()
        private set

    /** Minimum and maximum gain the equalizer supports, in millibels. */
    var bandRange: Pair<Short, Short> = Pair(0, 0)
        private set

    /** Whether the effects chain is currently active. */
    var isEnabled: Boolean = true
        private set

    // ---- Initialisation & teardown ----

    /**
     * Creates audio-effect instances for the given [audioSessionId] and restores
     * any previously-saved settings.
     *
     * Safe to call multiple times -- any existing effects are released first.
     */
    fun initialize(audioSessionId: Int) {
        Log.i(TAG, "initialize: audioSessionId=$audioSessionId")
        release()

        // Equalizer
        try {
            equalizer = Equalizer(EFFECT_PRIORITY, audioSessionId).also { eq ->
                bandCount = eq.numberOfBands.toInt()
                bandFrequencies = (0 until bandCount).map { band ->
                    eq.getCenterFreq(band.toShort())
                }
                val range = eq.bandLevelRange
                bandRange = Pair(range[0], range[1])
                eq.enabled = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create Equalizer", e)
        }

        // BassBoost
        try {
            bassBoost = BassBoost(EFFECT_PRIORITY, audioSessionId).also { it.enabled = true }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create BassBoost", e)
        }

        // Virtualizer
        try {
            virtualizer = Virtualizer(EFFECT_PRIORITY, audioSessionId).also { it.enabled = true }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create Virtualizer", e)
        }

        // LoudnessEnhancer
        try {
            loudnessEnhancer = LoudnessEnhancer(audioSessionId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create LoudnessEnhancer", e)
        }

        Log.i(TAG, "initialize: eq=${equalizer != null}, bass=${bassBoost != null}, " +
            "virt=${virtualizer != null}, loud=${loudnessEnhancer != null}, bands=$bandCount")

        // Restore persisted settings asynchronously
        scope.launch {
            val settings = equalizerStore.getSettings()
            Log.i(TAG, "Restoring settings: preset=${settings.preset}, enabled=${settings.enabled}")
            applySettings(settings)
        }
    }

    /**
     * Releases all effect instances and frees native resources.
     * Safe to call even if [initialize] was never called.
     */
    fun release() {
        try { equalizer?.release() } catch (_: Exception) {}
        try { bassBoost?.release() } catch (_: Exception) {}
        try { virtualizer?.release() } catch (_: Exception) {}
        try { loudnessEnhancer?.release() } catch (_: Exception) {}

        equalizer = null
        bassBoost = null
        virtualizer = null
        loudnessEnhancer = null
        bandCount = 0
        bandFrequencies = emptyList()
        bandRange = Pair(0, 0)
    }

    // ---- Public setters (each persists the change) ----

    /**
     * Applies a predefined [EqPreset]. For [EqPreset.CUSTOM] this applies the
     * currently stored custom gains; for all others it applies the preset's
     * built-in gain curve.
     */
    fun applyPreset(preset: EqPreset) {
        Log.d(TAG, "applyPreset: ${preset.name}, eq=${equalizer != null}")
        val eq = equalizer ?: return
        val gains = if (preset == EqPreset.CUSTOM) {
            // Use the last-known custom gains; fall back to flat if none stored
            null // will be loaded from store below
        } else {
            adaptGains(preset.gains, bandCount)
        }

        if (gains != null) {
            applyGainsToEqualizer(eq, gains)
        }

        scope.launch {
            val current = equalizerStore.getSettings()
            val updated = if (preset == EqPreset.CUSTOM) {
                val customGains = current.customGains.takeIf { it.isNotEmpty() }
                    ?: List(bandCount) { 0 }
                applyGainsToEqualizer(eq, customGains.toIntArray())
                current.copy(preset = preset.name)
            } else {
                current.copy(preset = preset.name)
            }
            equalizerStore.saveSettings(updated)
            if (isEnabled) reEvaluateBypass(updated)
        }
    }

    /**
     * Sets the gain for a single band. Automatically switches the active preset
     * to [EqPreset.CUSTOM].
     *
     * @param band   Zero-based band index (must be < [bandCount]).
     * @param gainMb Gain in millibels, clamped to [bandRange].
     */
    fun setBandGain(band: Int, gainMb: Int) {
        Log.d(TAG, "setBandGain: band=$band, gain=$gainMb mB, eq=${equalizer != null}")
        val eq = equalizer ?: return
        if (band < 0 || band >= bandCount) return

        val clampedGain = gainMb.coerceIn(bandRange.first.toInt(), bandRange.second.toInt())
        try {
            eq.setBandLevel(band.toShort(), clampedGain.toShort())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set band $band gain", e)
            return
        }

        scope.launch {
            val current = equalizerStore.getSettings()
            val customGains = (0 until bandCount).map { b ->
                eq.getBandLevel(b.toShort()).toInt()
            }
            val updated = current.copy(
                preset = EqPreset.CUSTOM.name,
                customGains = customGains,
            )
            equalizerStore.saveSettings(updated)
            // Re-evaluate smart bypass — activate hardware if no longer flat
            if (isEnabled) reEvaluateBypass(updated)
        }
    }

    /**
     * Sets the bass boost strength.
     *
     * @param strength Value in 0..1000. 0 disables the effect.
     */
    fun setBassBoost(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        try {
            bassBoost?.setStrength(clamped.toShort())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set BassBoost strength", e)
            return
        }

        scope.launch {
            val current = equalizerStore.getSettings()
            val updated = current.copy(bassBoostStrength = clamped)
            equalizerStore.saveSettings(updated)
            if (isEnabled) reEvaluateBypass(updated)
        }
    }

    /**
     * Sets the virtualizer (spatial audio) strength.
     *
     * @param strength Value in 0..1000. 0 disables the effect.
     */
    fun setVirtualizer(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        try {
            virtualizer?.setStrength(clamped.toShort())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set Virtualizer strength", e)
            return
        }

        scope.launch {
            val current = equalizerStore.getSettings()
            val updated = current.copy(virtualizerStrength = clamped)
            equalizerStore.saveSettings(updated)
            if (isEnabled) reEvaluateBypass(updated)
        }
    }

    /**
     * Sets the loudness enhancer target gain.
     *
     * @param gainMb Gain in millibels. 0 effectively disables the enhancer.
     */
    fun setLoudnessGain(gainMb: Int) {
        val clamped = gainMb.coerceAtLeast(0)
        try {
            loudnessEnhancer?.setTargetGain(clamped)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set LoudnessEnhancer gain", e)
            return
        }

        scope.launch {
            val current = equalizerStore.getSettings()
            val updated = current.copy(loudnessGainMb = clamped)
            equalizerStore.saveSettings(updated)
            if (isEnabled) reEvaluateBypass(updated)
        }
    }

    /**
     * Enables or disables the entire effects chain.
     * When disabled, all effects are turned off but their settings are retained.
     *
     * When enabled, checks if all effects are at neutral (flat EQ, zero bass/
     * virtualizer/loudness). If so, keeps the native effects disabled to avoid
     * unnecessary DSP processing, latency, and potential resampling artifacts.
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled

        scope.launch {
            val settings = equalizerStore.getSettings()

            if (enabled) {
                // Smart bypass: if everything is at neutral, keep effects off
                // to avoid unnecessary signal processing overhead.
                val effectivelyFlat = isSettingsFlat(settings)
                val hardwareEnabled = enabled && !effectivelyFlat

                try { equalizer?.enabled = hardwareEnabled } catch (_: Exception) {}
                try { bassBoost?.enabled = hardwareEnabled } catch (_: Exception) {}
                try { virtualizer?.enabled = hardwareEnabled } catch (_: Exception) {}
                try {
                    loudnessEnhancer?.setTargetGain(
                        if (hardwareEnabled) settings.loudnessGainMb else 0,
                    )
                } catch (_: Exception) {}

                if (effectivelyFlat) {
                    Log.d(TAG, "Smart bypass: all effects at neutral, hardware effects disabled")
                }
            } else {
                try { equalizer?.enabled = false } catch (_: Exception) {}
                try { bassBoost?.enabled = false } catch (_: Exception) {}
                try { virtualizer?.enabled = false } catch (_: Exception) {}
                try { loudnessEnhancer?.setTargetGain(0) } catch (_: Exception) {}
            }

            equalizerStore.saveSettings(settings.copy(enabled = enabled))
        }
    }

    /**
     * Checks whether all effect settings are at their neutral/zero positions.
     * Used by smart bypass to skip hardware effect processing when nothing
     * would actually change the audio signal.
     */
    private fun isSettingsFlat(settings: EqualizerSettings): Boolean {
        val preset = EqPreset.fromName(settings.preset)
        val gainsFlat = when {
            preset == EqPreset.FLAT -> true
            preset == EqPreset.CUSTOM ->
                settings.customGains.isEmpty() || settings.customGains.all { it == 0 }
            else -> preset.gains.all { it == 0 }
        }
        return gainsFlat &&
            settings.bassBoostStrength == 0 &&
            settings.virtualizerStrength == 0 &&
            settings.loudnessGainMb == 0
    }

    /**
     * Re-evaluates whether hardware effects should be on or off based on
     * current settings. Called after any individual effect value changes.
     */
    private fun reEvaluateBypass(settings: EqualizerSettings) {
        val shouldBeActive = !isSettingsFlat(settings)
        try { equalizer?.enabled = shouldBeActive } catch (_: Exception) {}
        try { bassBoost?.enabled = shouldBeActive } catch (_: Exception) {}
        try { virtualizer?.enabled = shouldBeActive } catch (_: Exception) {}
        try {
            loudnessEnhancer?.setTargetGain(if (shouldBeActive) settings.loudnessGainMb else 0)
        } catch (_: Exception) {}
        Log.d(TAG, "reEvaluateBypass: hardwareActive=$shouldBeActive")
    }

    // ---- Internal helpers ----

    /**
     * Applies a complete [EqualizerSettings] snapshot to all active effects.
     * Called during [initialize] to restore persisted state.
     *
     * Uses smart bypass: when the user has EQ "enabled" but all values are
     * neutral, the hardware effects stay off to keep the signal path clean.
     */
    private fun applySettings(settings: EqualizerSettings) {
        isEnabled = settings.enabled

        // Smart bypass: if enabled but flat, skip hardware activation
        val hardwareEnabled = settings.enabled && !isSettingsFlat(settings)

        // Equalizer bands — always set values so they're ready if enabled later
        equalizer?.let { eq ->
            eq.enabled = hardwareEnabled
            val preset = EqPreset.fromName(settings.preset)
            val gains = if (preset == EqPreset.CUSTOM) {
                if (settings.customGains.isNotEmpty()) {
                    adaptGains(settings.customGains.toIntArray(), bandCount)
                } else {
                    IntArray(bandCount) { 0 }
                }
            } else {
                adaptGains(preset.gains, bandCount)
            }
            applyGainsToEqualizer(eq, gains)
        }

        // BassBoost
        bassBoost?.let {
            it.enabled = hardwareEnabled
            try { it.setStrength(settings.bassBoostStrength.toShort()) } catch (_: Exception) {}
        }

        // Virtualizer
        virtualizer?.let {
            it.enabled = hardwareEnabled
            try { it.setStrength(settings.virtualizerStrength.toShort()) } catch (_: Exception) {}
        }

        // LoudnessEnhancer
        loudnessEnhancer?.let {
            try {
                it.setTargetGain(if (hardwareEnabled) settings.loudnessGainMb else 0)
            } catch (_: Exception) {}
        }

        if (settings.enabled && !hardwareEnabled) {
            Log.d(TAG, "Smart bypass on restore: all effects neutral, hardware off")
        }
    }

    /**
     * Writes an [IntArray] of gain values to the [Equalizer] bands.
     * The array length must equal [bandCount].
     */
    private fun applyGainsToEqualizer(eq: Equalizer, gains: IntArray) {
        for (band in 0 until bandCount.coerceAtMost(gains.size)) {
            try {
                val clamped = gains[band].coerceIn(
                    bandRange.first.toInt(),
                    bandRange.second.toInt(),
                )
                eq.setBandLevel(band.toShort(), clamped.toShort())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set band $band", e)
            }
        }
    }

    /**
     * Adapts a gain array from one band count to another using linear interpolation.
     *
     * If the source and target counts match, the array is returned as-is.
     * Otherwise, each target band position is mapped proportionally to the source
     * array and the gain is linearly interpolated between the two nearest source bands.
     *
     * @param sourceGains The original gain values (e.g., from an [EqPreset]).
     * @param targetBandCount The number of bands on the current device.
     * @return An [IntArray] of length [targetBandCount] with interpolated gains.
     */
    internal fun adaptGains(sourceGains: IntArray, targetBandCount: Int): IntArray {
        if (targetBandCount <= 0) return IntArray(0)
        if (sourceGains.size == targetBandCount) return sourceGains.copyOf()
        if (sourceGains.isEmpty()) return IntArray(targetBandCount) { 0 }
        if (sourceGains.size == 1) return IntArray(targetBandCount) { sourceGains[0] }

        return IntArray(targetBandCount) { targetBand ->
            // Map target band index to a fractional position in the source array
            val srcPos = targetBand.toFloat() * (sourceGains.size - 1) / (targetBandCount - 1).coerceAtLeast(1)
            val lower = srcPos.toInt().coerceIn(0, sourceGains.size - 2)
            val upper = (lower + 1).coerceAtMost(sourceGains.size - 1)
            val fraction = srcPos - lower

            // Linear interpolation between the two nearest source bands
            (sourceGains[lower] * (1f - fraction) + sourceGains[upper] * fraction).toInt()
        }
    }
}
