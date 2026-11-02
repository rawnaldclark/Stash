package com.stash.core.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.stash.core.ui.ambient.Ground
import com.stash.core.ui.ambient.brandRoles
import com.stash.core.ui.ambient.withAlbumAccent

// ── Color schemes ────────────────────────────────────────────────────────
//
// Each scheme is its grounds (backgrounds, surfaces, text, error, outlines) wearing the neutral idle
// accent: purple and cyan are gone (owner decision D9, 2026-11-01). The accent families (primary,
// secondary, tertiary, their containers, inversePrimary, surfaceTint) come from the idle palette's
// roles through withAlbumAccent, the same function that dresses the scheme in the playing album's
// colour (ProvideThemeAccent), so the app with nothing playing, or with "Album colours: Neutral",
// is exactly these schemes. Initialisation reads the ambient maths (AccentRoles.kt), which only
// reads Color.kt: nothing there may read back from this file.

/** The dark theme's grounds, before the accent. */
private val StashDarkGrounds = darkColorScheme(
    background = StashBackground,
    onBackground = StashTextPrimary,
    surface = StashSurface,
    onSurface = StashTextPrimary,
    surfaceVariant = StashElevatedSurface,
    onSurfaceVariant = StashTextSecondary,
    error = StashError,
    onError = Color.White,
    outline = StashGlassBorder,
    outlineVariant = StashGlassBorderBright,
)

/** The light theme's grounds, before the accent (the lavender grounds are phase 2 of D9). */
private val StashLightGrounds = lightColorScheme(
    background = StashBackgroundLight,
    onBackground = StashTextPrimaryLight,
    surface = StashSurfaceLight,
    onSurface = StashTextPrimaryLight,
    surfaceVariant = StashElevatedSurfaceLight,
    onSurfaceVariant = StashTextSecondaryLight,
    error = StashError,
    onError = Color.White,
    outline = StashGlassBorderLight,
    outlineVariant = StashGlassBorderBrightLight,
)

/** Dark: near-white grey accents with near-black ink on them, on the dark grounds. */
val StashDarkColorScheme = StashDarkGrounds.withAlbumAccent(brandRoles(Ground.DARK), Ground.DARK)

/** Light: dark grey accents under white, on the light grounds. */
val StashLightColorScheme = StashLightGrounds.withAlbumAccent(brandRoles(Ground.LIGHT), Ground.LIGHT)

/**
 * AMOLED variant of the dark scheme: every ground the eye reads as "the app's
 * canvas" is pure #000000 so OLED panels switch those pixels off entirely.
 *
 * Beyond background/surface, the whole M3 surface-container family and
 * [androidx.compose.material3.ColorScheme.surfaceTint] are forced black —
 * components that default to `surfaceContainerLow` (ModalBottomSheet) or
 * apply tonal elevation would otherwise render M3's baseline dark gray on
 * top of our black. With surfaceTint black, `surfaceColorAtElevation()` is
 * a no-op by construction. Raised tiers (surfaceVariant, containerHigh /
 * Highest) keep a dim lift so chips, thumbnails, and drag targets stay
 * distinguishable on true black — hierarchy on AMOLED is carried by the
 * glass borders, not by big tonal fills.
 */
val StashAmoledColorScheme = StashDarkGrounds.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceTint = Color.Black,
    surfaceDim = Color.Black,
    surfaceBright = StashElevatedSurface,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainer = Color.Black,
    surfaceContainerHigh = StashAmoledSurfaceHigh,
    surfaceContainerHighest = StashElevatedSurface,
).withAlbumAccent(brandRoles(Ground.AMOLED), Ground.AMOLED)

// ── LocalIsDarkTheme — queried by composables that need theme-aware assets
//    (e.g. the wordmark drawable selection in HomeScreen). ────────────────
val LocalIsDarkTheme = staticCompositionLocalOf { true }

// ── LocalIsAmoledTheme — true only when the pure-black dark theme is
//    active. Queried by surfaces that paint their own canvas (e.g. Now
//    Playing's AmbientBackground) instead of sniffing colorScheme values. ──
val LocalIsAmoledTheme = staticCompositionLocalOf { false }

/**
 * Root theme for the Stash app.
 *
 * Accepts an explicit [darkTheme] flag so the caller can wire in a user
 * preference (Light/Dark/System) instead of always following the OS.
 * When the caller wants to mirror the system, pass
 * `isSystemInDarkTheme()` — that's also the default for safety.
 *
 * [amoled] upgrades the DARK scheme to pure black ([StashAmoledColorScheme]);
 * it composes with any mode — on light (or system-day) it is dormant and
 * kicks in whenever the effective theme is dark.
 *
 * Switching between schemes is done purely in Compose state — no activity
 * recreation, no resource configuration override — so the flip is instant
 * and any `animateColorAsState` wrappers can animate between the two sets.
 *
 * The system status-bar and navigation-bar icon colors are updated via a
 * [SideEffect] so they flip in sync with the in-app theme.
 */
@Composable
fun StashTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val useAmoled = darkTheme && amoled
    val colorScheme = when {
        useAmoled -> StashAmoledColorScheme
        darkTheme -> StashDarkColorScheme
        else -> StashLightColorScheme
    }
    val extendedColors = if (darkTheme) StashExtendedColorsDark else StashExtendedColorsLight

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            // No system scrim behind 3-button navigation: the glass tab bar (and Now Playing's
            // ambient) run down under the system bar and carry its contrast themselves.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            val insetsController = WindowCompat.getInsetsController(window, view)
            // Light status-bar icons on dark theme, dark icons on light theme.
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(
        LocalStashColors provides extendedColors,
        LocalIsDarkTheme provides darkTheme,
        LocalIsAmoledTheme provides useAmoled,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = StashTypography,
            shapes = StashShapes,
            content = content,
        )
    }
}

object StashTheme {
    val extendedColors: StashExtendedColors
        @Composable
        get() = LocalStashColors.current
}
