package com.stash.core.ui.icons

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * A tab glyph's animated values and their timings: the prototype's CSS transitions, one for one
 * (stash-tab-bar-v2.html, the `.g-*` rules). Every move runs on a [ClampedFrameClock], so the
 * frame that lays out a newly picked tab's screen pauses the move instead of skipping it.
 */

private val CssEase = StashTabMotion.CssEase
private val PopEase = StashTabMotion.PopEase
private val RiseOnEase: Easing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)
private val RiseOffEase: Easing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)
private val RecordEase: Easing = CubicBezierEasing(0.25f, 1.25f, 0.4f, 1f)
private val GlintEase: Easing = CubicBezierEasing(0.25f, 0.8f, 0.3f, 1f)
private val KnobEase: Easing = StashTabMotion.LiftEase // the same curve: cubic-bezier(.3, 1.5, .5, 1)

private const val COLOUR_MS = 240
private const val POP_MS = 420
private const val POP_FADE_MS = 160
private const val RISE_ON_MS = 520
private const val RISE_OFF_MS = 300
private const val SQUASH_MS = 560
private const val RECORD_MS = 700
private const val TILT_MS = 560
private const val GLINT_MS = 620
private const val LOOP_MS = 720
private const val KNOB_MS = 560
private const val KNOB_FILL_MS = 200
private const val KNOB_STAGGER_MS = 45

/** A replay first runs the move back toward rest this long (the prototype's replay tile). */
private const val REPLAY_RETREAT_MS = 220L

/**
 * A glyph's animated values. Each is 0 at rest and 1 picked (the easing may overshoot past 1),
 * except [oneShot], a keyframe clock that runs 0 → 1 once per pick and rests at 1 (identity).
 */
@Stable
internal class TabGlyphMotion(
    private val glyph: StashTabGlyph,
    selected: Boolean,
    initialReplay: Int,
) {
    private val start = if (selected) 1f else 0f

    /** Rest colour → ink (`.tab { transition: color 240ms }`). */
    val colour = Animatable(start)

    /** The solid part's scale (0 → 55 %, 1 → 100 %). */
    val pop = Animatable(start)

    /** The solid part's opacity. */
    val popAlpha = Animatable(start)

    /** The glyph's main move: Home's fill level, Library's record, Search's glint, Sync's loop. */
    val primary = Animatable(start)

    /** Home's squash / Search's tilt keyframe clock. */
    val oneShot = Animatable(1f)

    /** Settings: each knob's travel and fill. */
    val knobs = Array(3) { Animatable(start) }
    val knobFill = Array(3) { Animatable(start) }

    /** The state last asked for. */
    var target: Boolean = selected
        private set

    /** The last replay trigger handled, so a recomposition never replays twice. */
    var seenReplay: Int = initialReplay

    /** Moves to picked or rest; snaps when [animate] is false. */
    suspend fun settle(selected: Boolean, animate: Boolean) {
        val wasSelected = target
        target = selected
        if (!animate) {
            snapTo(selected)
            return
        }
        withClampedFrameClock {
            run(on = selected, includeColour = true, restartOneShot = selected && !wasSelected)
        }
    }

    /**
     * Replays the pick move while picked: runs it back toward rest for a moment, then forward
     * again (the colour stays the ink throughout). Snapped glyphs (reduced motion) have nothing to replay.
     */
    suspend fun replay(animate: Boolean) {
        if (!animate) return
        withClampedFrameClock {
            coroutineScope {
                val retreat = launch { run(on = false, includeColour = false, restartOneShot = false) }
                // Scaled like every Compose animation, so the system's animator duration scale slows it too.
                val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
                delay((REPLAY_RETREAT_MS * scale).toLong())
                retreat.cancel()
            }
            run(on = true, includeColour = false, restartOneShot = true)
        }
    }

    private suspend fun snapTo(selected: Boolean) {
        val v = if (selected) 1f else 0f
        colour.snapTo(v)
        pop.snapTo(v)
        popAlpha.snapTo(v)
        primary.snapTo(v)
        oneShot.snapTo(1f)
        knobs.forEach { it.snapTo(v) }
        knobFill.forEach { it.snapTo(v) }
    }

    private suspend fun run(on: Boolean, includeColour: Boolean, restartOneShot: Boolean) = coroutineScope {
        val v = if (on) 1f else 0f
        if (includeColour) launch { colour.animateTo(v, tween(COLOUR_MS, easing = CssEase)) }
        launch { pop.animateTo(v, tween(POP_MS, easing = PopEase)) }
        launch { popAlpha.animateTo(v, tween(POP_FADE_MS, easing = CssEase)) }
        primarySpec(on)?.let { spec -> launch { primary.animateTo(v, spec) } }
        oneShotMs()?.let { ms ->
            launch {
                if (on && restartOneShot) {
                    oneShot.snapTo(0f)
                    oneShot.animateTo(1f, tween(ms, easing = LinearEasing))
                } else if (!on) {
                    // CSS drops the keyframe animation with the class: the glyph is at identity at once.
                    oneShot.snapTo(1f)
                }
            }
        }
        if (glyph == StashTabGlyph.Settings) {
            for (i in 0 until 3) {
                val delayMs = i * KNOB_STAGGER_MS
                launch { knobs[i].animateTo(v, tween(KNOB_MS, delayMs, KnobEase)) }
                launch { knobFill[i].animateTo(v, tween(KNOB_FILL_MS, delayMs, CssEase)) }
            }
        }
    }

    private fun primarySpec(on: Boolean): AnimationSpec<Float>? = when (glyph) {
        StashTabGlyph.Home -> if (on) tween(RISE_ON_MS, easing = RiseOnEase) else tween(RISE_OFF_MS, easing = RiseOffEase)
        StashTabGlyph.Library -> tween(RECORD_MS, easing = RecordEase)
        StashTabGlyph.Search -> tween(GLINT_MS, easing = GlintEase)
        StashTabGlyph.Sync -> tween(LOOP_MS, easing = RecordEase)
        StashTabGlyph.Settings -> null
    }

    private fun oneShotMs(): Int? = when (glyph) {
        StashTabGlyph.Home -> SQUASH_MS
        StashTabGlyph.Search -> TILT_MS
        else -> null
    }
}
