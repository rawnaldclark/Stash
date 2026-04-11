package com.stash.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ── Brand constants (shared by both themes) ────────────────────────────────
val StashPurple = Color(0xFF8B5CF6)
val StashPurpleLight = Color(0xFFA78BFA)
val StashPurpleDark = Color(0xFF7C3AED)
val StashCyan = Color(0xFF06B6D4)
val StashCyanLight = Color(0xFF22D3EE)
val StashCyanDark = Color(0xFF0891B2)
val StashSpotifyGreen = Color(0xFF1DB954)
val StashYouTubeRed = Color(0xFFFF0033)
val StashError = Color(0xFFEF4444)
val StashWarning = Color(0xFFF59E0B)
val StashSuccess = Color(0xFF10B981)

// ── Dark theme palette ─────────────────────────────────────────────────────
val StashBackground = Color(0xFF06060C)
val StashSurface = Color(0xFF0D0D18)
val StashElevatedSurface = Color(0xFF1A1A2E)
val StashTextPrimary = Color(0xFFE8E8F0)
val StashTextSecondary = Color(0xFFA0A0B8)
val StashTextTertiary = Color(0xFF606078)
val StashGlassBackground = Color(0x0AFFFFFF)
val StashGlassBackgroundHover = Color(0x14FFFFFF)
val StashGlassBorder = Color(0x0FFFFFFF)
val StashGlassBorderBright = Color(0x24FFFFFF)

// ── Light theme palette (cream / lavender, preserves brand purple) ─────────
//
// Design goal: not a generic "white background + colored accents" light mode.
// Instead, a warm cream/lavender base that echoes the dark theme's purple
// saturation and keeps the premium feel.
val StashBackgroundLight = Color(0xFFF6F3FF)      // soft lavender-tinted white
val StashSurfaceLight = Color(0xFFFFFFFF)          // pure white cards stand out
val StashElevatedSurfaceLight = Color(0xFFEEE7FC)  // subtle purple wash for raised elements
val StashTextPrimaryLight = Color(0xFF1A0B2E)      // deep aubergine, high contrast
val StashTextSecondaryLight = Color(0xFF5B4680)    // muted purple-gray for secondary text
val StashTextTertiaryLight = Color(0xFF8B7AB0)     // lightest tier, for metadata
val StashGlassBackgroundLight = Color(0x0F7C3AED)  // 6% purple tint on white
val StashGlassBackgroundHoverLight = Color(0x1A7C3AED)
val StashGlassBorderLight = Color(0x1F7C3AED)      // 12% purple for subtle borders
val StashGlassBorderBrightLight = Color(0x337C3AED)

@Immutable
data class StashExtendedColors(
    val spotifyGreen: Color = StashSpotifyGreen,
    val youtubeRed: Color = StashYouTubeRed,
    val cyan: Color = StashCyan,
    val cyanLight: Color = StashCyanLight,
    val cyanDark: Color = StashCyanDark,
    val purpleLight: Color = StashPurpleLight,
    val purpleDark: Color = StashPurpleDark,
    val elevatedSurface: Color = StashElevatedSurface,
    val glassBackground: Color = StashGlassBackground,
    val glassBackgroundHover: Color = StashGlassBackgroundHover,
    val glassBorder: Color = StashGlassBorder,
    val glassBorderBright: Color = StashGlassBorderBright,
    val textTertiary: Color = StashTextTertiary,
    val warning: Color = StashWarning,
    val success: Color = StashSuccess,
)

/** Dark-theme instance of extended colors (current default). */
val StashExtendedColorsDark = StashExtendedColors()

/** Light-theme instance of extended colors — cream/lavender palette. */
val StashExtendedColorsLight = StashExtendedColors(
    elevatedSurface = StashElevatedSurfaceLight,
    glassBackground = StashGlassBackgroundLight,
    glassBackgroundHover = StashGlassBackgroundHoverLight,
    glassBorder = StashGlassBorderLight,
    glassBorderBright = StashGlassBorderBrightLight,
    textTertiary = StashTextTertiaryLight,
    // Brand colors (spotify/youtube/cyan/purple variants) stay identical —
    // they're identity markers, not theme-dependent.
)

val LocalStashColors = staticCompositionLocalOf { StashExtendedColorsDark }
