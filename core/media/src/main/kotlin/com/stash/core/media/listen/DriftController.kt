package com.stash.core.media.listen

import kotlin.math.abs

sealed interface DriftAction {
    data object None : DriftAction
    /** Pitch-preserving speed (Sonic, at the end of the StashRenderersFactory chain). */
    data class Speed(val speed: Float) : DriftAction
    /** Seek to the expected position. */
    data object Seek : DriftAction
}

data class DriftResult(val action: DriftAction, val drifting: Boolean)

/**
 * Once-a-second drift correction (spec §4). `errorMs = actual − expected`:
 * - under 40 ms: nothing;
 * - 40 ms to 1 s: speed `1 − clamp(e / 2000, −0.03, 0.03)` until the error is under 20 ms, then 1.0;
 * - over 1 s: seek.
 * [DriftResult.drifting] turns true once the error has stayed above 250 ms for 10 s.
 */
class DriftController {
    private var correcting = false
    private var overSinceMs: Long? = null

    fun onTick(errorMs: Long, nowMs: Long): DriftResult {
        val size = abs(errorMs)
        overSinceMs = if (size > DRIFTING_MS) overSinceMs ?: nowMs else null
        val drifting = overSinceMs?.let { nowMs - it >= DRIFTING_FOR_MS } ?: false
        val action = when {
            size > SEEK_MS -> { correcting = false; DriftAction.Seek }
            size >= START_MS -> { correcting = true; DriftAction.Speed(speedFor(errorMs)) }
            correcting && size >= STOP_MS -> DriftAction.Speed(speedFor(errorMs))
            correcting -> { correcting = false; DriftAction.Speed(1f) }
            else -> DriftAction.None
        }
        return DriftResult(action, drifting)
    }

    fun reset() {
        correcting = false
        overSinceMs = null
    }

    companion object {
        const val START_MS = 40L
        const val STOP_MS = 20L
        const val SEEK_MS = 1_000L
        const val DRIFTING_MS = 250L
        const val DRIFTING_FOR_MS = 10_000L
        private const val MAX_ADJUST = 0.03f

        fun speedFor(errorMs: Long): Float = 1f - (errorMs / 2000f).coerceIn(-MAX_ADJUST, MAX_ADJUST)
    }
}
