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
            if (preset == EqPreset.CUSTOM) {
                // Apply stored custom gains
                val customGains = current.customGains.takeIf { it.isNotEmpty() }
                    ?: List(bandCount) { 0 }
                applyGainsToEqualizer(eq, customGains.toIntArray())
                equalizerStore.saveSettings(current.copy(preset = preset.name))
            } else {
                equalizerStore.saveSettings(
                    current.copy(
                        preset = preset.name,
                        // Also snapshot the preset gains as custom so switching
                        // to CUSTOM later starts from this curve
                    ),
                )
            }
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
            // Build the full custom gains array from current equalizer state
            val customGains = (0 until bandCount).map { b ->
                eq.getBandLevel(b.toShort()).toInt()
            }
            equalizerStore.saveSettings(
                current.copy(
                    preset = EqPreset.CUSTOM.name,
                    customGains = customGains,
                ),
            )
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
            equalizerStore.saveSettings(current.copy(bassBoostStrength = clamped))
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
            equalizerStore.saveSettings(current.copy(virtualizerStrength = clamped))
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
            equalizerStore.saveSettings(current.copy(loudnessGainMb = clamped))
        }
    }

    /**
     * Enables or disables the entire effects chain.
     * When disabled, all effects are turned off but their settings are retained.
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        try { equalizer?.enabled = enabled } catch (_: Exception) {}
        try { bassBoost?.enabled = enabled } catch (_: Exception) {}
        try { virtualizer?.enabled = enabled } catch (_: Exception) {}
        try {
            if (enabled) {
                // LoudnessEnhancer has no `enabled` setter; re-apply the gain
                scope.launch {
                    val settings = equalizerStore.getSettings()
                    loudnessEnhancer?.setTargetGain(settings.loudnessGainMb)
                }
            } else {
                loudnessEnhancer?.setTargetGain(0)
            }
        } catch (_: Exception) {}

        scope.launch {
            val current = equalizerStore.getSettings()
            equalizerStore.saveSettings(current.copy(enabled = enabled))
        }
    }

    // ---- Internal helpers ----

    /**
     * Applies a complete [EqualizerSettings] snapshot to all active effects.
     * Called during [initialize] to restore persisted state.
     */
    private fun applySettings(settings: EqualizerSettings) {
        isEnabled = settings.enabled

        // Equalizer bands
        equalizer?.let { eq ->
            eq.enabled = settings.enabled
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
            it.enabled = settings.enabled
            try { it.setStrength(settings.bassBoostStrength.toShort()) } catch (_: Exception) {}
        }

        // Virtualizer
        virtualizer?.let {
            it.enabled = settings.enabled
            try { it.setStrength(settings.virtualizerStrength.toShort()) } catch (_: Exception) {}
        }

        // LoudnessEnhancer
        loudnessEnhancer?.let {
            try {
                it.setTargetGain(if (settings.enabled) settings.loudnessGainMb else 0)
            } catch (_: Exception) {}
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
