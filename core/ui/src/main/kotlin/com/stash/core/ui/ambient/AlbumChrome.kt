package com.stash.core.ui.ambient

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.stash.core.ui.theme.LocalIsAmoledTheme
import com.stash.core.ui.theme.LocalIsDarkTheme
import com.stash.core.ui.theme.StashPurple

/*
 * One album colour for the whole app. The player-level source (app's AlbumColorSource) reads the
 * current song's cover into an [AmbientPalette] (through the shared [PaletteCache]) and says whether a
 * song is playing; the scaffold hands that to [ProvideAlbumChrome], which turns the palette into its
 * contrast-safe [AccentRoles] for the theme, crossfades them with one [AccentAnimator], and publishes
 * them through [LocalAlbumChrome]. The mini player, Now Playing (its play button, progress, ambient)
 * and the chrome all read this one holder, so they always show the same shade. Readers only touch
 * the colours they draw, so a crossfade recomposes (or just redraws) what wears the colour, nothing else.
 */

/** What the app's chrome is coloured from: the current song's palette, and whether it plays. */
@Immutable
data class AlbumColors(
    /** The current song's palette; [AmbientPalette.Default] with no song or no readable cover. */
    val palette: AmbientPalette = AmbientPalette.Default,
    /** True only while a song is actually playing (see [tintedWhilePlaying]). */
    val tinted: Boolean = false,
) {
    companion object {
        val Idle = AlbumColors()
    }
}

/** The colour crossfade (the web's --np-* 800 ms registered-colour transition). */
const val ALBUM_CROSSFADE_MS = 800

/** The crossfade with animations off (prefers-reduced-motion: 200 ms on the web). */
const val ALBUM_CROSSFADE_REDUCED_MS = 200

/** The tab's selected/unselected colour change (the web's --dur-short on `.tab-i`). */
const val TAB_COLOUR_MS = 180

/**
 * The album colours the app wears, each already crossfading. Read the properties at draw or
 * composition time of the element that uses them; the holder itself never changes identity.
 *
 * Song-bound colours ([accent], [onAccent], [ink], [wash], [pageTint], [dom], [vib], [mut]) follow
 * the current song whether it plays or not. [wordmark] and [selectedTab] are tinted only while a
 * song plays (see [tintedWhilePlaying]).
 */
@Stable
class AlbumChrome internal constructor(
    /** The current crossfade (a fresh one per theme); read through snapshot state, so a swap is tracked. */
    private val animatorRef: () -> AccentAnimator,
    private val wordmarkRef: () -> Color,
    private val selectedTabRef: () -> Color,
) {
    private val animator: AccentAnimator get() = animatorRef()

    /** The song's accent (3:1 on every base): play buttons, progress lines, active icons. */
    val accent: Color get() = animator.role { it.accent }

    /** Ink on [accent]: white or near-black, 4.5:1 (the play/pause glyph, a spinner). */
    val onAccent: Color get() = animator.role { it.onAccent }

    /** Text and small glyphs in the song's colour (4.5:1 on every base and on [wash]). */
    val ink: Color get() = animator.role { it.ink }

    /** The accent as a translucent fill behind text (a playing row). Not worn yet. */
    val wash: Color get() = animator.role { it.wash }

    /** The dominant colour as a translucent page glow; transparent on AMOLED. Not worn yet. */
    val pageTint: Color get() = animator.role { it.pageTint }

    /** The Now Playing ambient's dominant colour (lifted on light). */
    val dom: Color get() = animator.role { it.dom }

    /**
     * The Now Playing ambient's vibrant colour (lifted on light). The raw cover colour, not
     * contrast-checked: for anything drawn on a surface use [accent].
     */
    val vib: Color get() = animator.role { it.vib }

    /** The Now Playing ambient's muted colour (lifted on light). */
    val mut: Color get() = animator.role { it.mut }

    /** The wordmark's one ink: plain when idle or paused, the contrast-checked mood tint while a song plays. */
    val wordmark: Color get() = wordmarkRef()

    /** The selected tab: the song's [ink] while playing, else the theme's neutral selected colour. */
    val selectedTab: Color get() = selectedTabRef()

    /** Every song-bound role as displayed now (one snapshot; prefer the single getters per frame). */
    val roles: AccentRoles get() = animator.displayed()

    /** The old name of [onAccent]. */
    @Deprecated("The ink on the accent is onAccent now", ReplaceWith("onAccent"))
    val onVib: Color get() = onAccent

    /** The old name of [ink]. */
    @Deprecated("The song's text colour is ink now", ReplaceWith("ink"))
    val accentText: Color get() = ink
}

/**
 * The idle chrome outside any [ProvideAlbumChrome] (previews, tests): Stash purple, white wordmark,
 * on the dark ground. A CompositionLocal's default is built without a composition, so it can't
 * see the theme; everything the app draws sits under ProvideAlbumChrome, which does.
 */
private val IdleAnimator = AccentAnimator(brandRoles(Ground.DARK))
private val IdleChrome = AlbumChrome(
    animatorRef = { IdleAnimator },
    wordmarkRef = { WordmarkPlainDark },
    selectedTabRef = { StashPurple },
)

/** The app's album colours. Provided once, at the scaffold. */
val LocalAlbumChrome = staticCompositionLocalOf { IdleChrome }

/** The [Ground] the current theme draws on: light, AMOLED black, or dark. Must sit inside StashTheme. */
@Composable
@ReadOnlyComposable
fun currentGround(): Ground = when {
    !LocalIsDarkTheme.current -> Ground.LIGHT
    LocalIsAmoledTheme.current -> Ground.AMOLED
    else -> Ground.DARK
}

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
 * Publishes [colors] to [LocalAlbumChrome] for [content]: the palette's roles for the current
 * theme, crossfading every song change over 800 ms (200 ms with animations off; the wordmark then
 * switches at once, as the web's does). A theme change does not crossfade: MaterialTheme switches
 * at once, and a dark theme's accent fading out over the light ground would sit near 2:1 for half
 * a second, so the colours switch with it. Reads the theme, so it must sit inside StashTheme.
 */
@Composable
fun ProvideAlbumChrome(colors: AlbumColors, content: @Composable () -> Unit) {
    val ground = currentGround()
    val light = ground.isLight
    val reduced = rememberReducedMotion()
    val fadeMs = if (reduced) ALBUM_CROSSFADE_REDUCED_MS else ALBUM_CROSSFADE_MS
    val fade: AnimationSpec<Color> = tween(fadeMs, easing = FastOutSlowInEasing)
    val wordmarkFade: AnimationSpec<Color> = if (reduced) snap() else fade

    val target = remember(colors.palette, ground) { colors.palette.roles(ground) }
    // Keyed by the ground: a theme flip starts a fresh animator already at the new theme's roles,
    // in the same frame the theme changes (a snap from an effect would show one wrong frame).
    val animator = remember(ground) { AccentAnimator(target) }
    LaunchedEffect(animator, target) {
        // From whatever is on screen now (an interrupted crossfade included) to the new roles.
        if (animator.retarget(target) || animator.mix < 1f) {
            animate(
                initialValue = animator.mix,
                targetValue = 1f,
                animationSpec = tween(fadeMs, easing = FastOutSlowInEasing),
            ) { value, _ -> animator.mix = value }
        }
    }

    val wordmarkTarget = remember(colors.palette, colors.tinted, light) {
        wordmarkInk(if (colors.tinted) colors.palette else null, light)
    }
    val neutralTab = MaterialTheme.colorScheme.primary
    val tabTarget = if (colors.tinted) target.ink else neutralTab
    // Keyed by the ground too, so they switch with the theme rather than fading across it.
    val wordmark = key(ground) { animateColorAsState(wordmarkTarget, wordmarkFade, label = "albumWordmark") }
    val tab = key(ground) { animateColorAsState(tabTarget, fade, label = "albumTab") }
    // One holder for the app's lifetime (its identity never changes, so nothing holding it goes
    // stale); it reads the current animator and inks through these references.
    val animatorRef = rememberUpdatedState(animator)
    val wordmarkRef = rememberUpdatedState(wordmark)
    val tabRef = rememberUpdatedState(tab)
    val chrome = remember {
        AlbumChrome(
            animatorRef = { animatorRef.value },
            wordmarkRef = { wordmarkRef.value.value },
            selectedTabRef = { tabRef.value.value },
        )
    }
    CompositionLocalProvider(LocalAlbumChrome provides chrome, content = content)
}
