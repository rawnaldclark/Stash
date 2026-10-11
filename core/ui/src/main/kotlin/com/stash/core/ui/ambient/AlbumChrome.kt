package com.stash.core.ui.ambient

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.stash.core.ui.theme.LocalIsDarkTheme
import com.stash.core.ui.theme.StashPurple

/*
 * One album colour for the whole app. The player-level source (app's AlbumColorSource) reads the
 * current song's cover into an [AmbientPalette] and says whether a song is playing; the scaffold
 * hands that to [ProvideAlbumChrome], which crossfades every colour the chrome wears and publishes
 * them through [LocalAlbumChrome]. Readers only touch the colours they draw, so a crossfade
 * recomposes (or just redraws) the wordmark, the tab bar and the mini player, nothing else.
 */

/** What the app's chrome is coloured from: the current song's palette, and whether it plays. */
@Immutable
data class AlbumColors(
    /** The current song's palette; [AmbientPalette.Default] with no song or a colourless cover. */
    val palette: AmbientPalette = AmbientPalette.Default,
    /** True only while a song is actually playing (see [tintedWhilePlaying]). */
    val tinted: Boolean = false,
) {
    companion object {
        val Idle = AlbumColors()
    }
}

/** The colour crossfade (the web's --np-* 800 ms registered-colour transition). */
private const val CROSSFADE_MS = 800

/** The crossfade with animations off (prefers-reduced-motion: 200 ms on the web). */
private const val CROSSFADE_REDUCED_MS = 200

/** The tab's selected/unselected colour change (the web's --dur-short on `.tab-i`). */
const val TAB_COLOUR_MS = 180

/**
 * The album colours the chrome wears, each already crossfading. Read the properties at draw or
 * composition time of the element that uses them.
 */
@Stable
class AlbumChrome internal constructor(
    private val vibState: State<Color>,
    private val onVibState: State<Color>,
    private val wordmarkState: State<Color>,
    private val selectedTabState: State<Color>,
    private val accentTextState: State<Color>,
) {
    /** The song's vibrant accent: the mini player's progress line and play button. Follows the song, playing or not. */
    val vib: Color get() = vibState.value

    /** Ink on [vib]: white, or near-black on a bright accent. */
    val onVib: Color get() = onVibState.value

    /** The wordmark's one ink: plain when idle or paused, the contrast-checked mood tint while a song plays. */
    val wordmark: Color get() = wordmarkState.value

    /** The selected tab: the song's text ink while playing, else the theme's neutral selected colour. */
    val selectedTab: Color get() = selectedTabState.value

    /** The song's text ink for the theme (--np-text: textDark, or textLight on light), playing or not: accents on a surface. */
    val accentText: Color get() = accentTextState.value
}

/** The idle chrome outside any [ProvideAlbumChrome] (previews, tests): Stash purple, white wordmark. */
private val IdleChrome = AlbumChrome(
    vibState = mutableStateOf(AmbientPalette.Default.vib),
    onVibState = mutableStateOf(AmbientPalette.Default.ink),
    wordmarkState = mutableStateOf(WordmarkPlainDark),
    selectedTabState = mutableStateOf(StashPurple),
    accentTextState = mutableStateOf(AmbientPalette.Default.textDark),
)

/** The app's album colours. Provided once, at the scaffold. */
val LocalAlbumChrome = staticCompositionLocalOf { IdleChrome }

/**
 * The system "Remove animations" switch (animator duration scale 0): the Android analogue of
 * prefers-reduced-motion, as the Now Playing ambient reads it.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        try {
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Publishes [colors] to [LocalAlbumChrome] for [content], crossfading every change over 800 ms
 * (200 ms with animations off; the wordmark then switches at once, as the web's does).
 * Reads the theme for the light scheme's inks, so it must sit inside StashTheme.
 */
@Composable
fun ProvideAlbumChrome(colors: AlbumColors, content: @Composable () -> Unit) {
    val light = !LocalIsDarkTheme.current
    val reduced = rememberReducedMotion()
    val fade: AnimationSpec<Color> =
        tween(if (reduced) CROSSFADE_REDUCED_MS else CROSSFADE_MS, easing = FastOutSlowInEasing)
    val wordmarkFade: AnimationSpec<Color> = if (reduced) snap() else fade

    val scheme = remember(colors.palette, light) { colors.palette.forScheme(light) }
    val wordmarkTarget = remember(colors.palette, colors.tinted, light) {
        wordmarkInk(if (colors.tinted) colors.palette else null, light)
    }
    val neutralTab = MaterialTheme.colorScheme.primary
    val tabTarget = when {
        !colors.tinted -> neutralTab
        light -> scheme.textLight
        else -> scheme.textDark
    }

    val vib = animateColorAsState(scheme.vib, fade, label = "albumVib")
    val onVib = animateColorAsState(scheme.ink, fade, label = "albumOnVib")
    val wordmark = animateColorAsState(wordmarkTarget, wordmarkFade, label = "albumWordmark")
    val tab = animateColorAsState(tabTarget, fade, label = "albumTab")
    val accentText = animateColorAsState(if (light) scheme.textLight else scheme.textDark, fade, label = "albumText")
    val chrome = remember(vib, onVib, wordmark, tab, accentText) { AlbumChrome(vib, onVib, wordmark, tab, accentText) }
    CompositionLocalProvider(LocalAlbumChrome provides chrome, content = content)
}
