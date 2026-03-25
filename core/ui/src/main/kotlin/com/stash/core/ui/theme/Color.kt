package com.stash.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val StashBackground = Color(0xFF06060C)
val StashSurface = Color(0xFF0D0D18)
val StashElevatedSurface = Color(0xFF1A1A2E)
val StashPurple = Color(0xFF8B5CF6)
val StashPurpleLight = Color(0xFFA78BFA)
val StashPurpleDark = Color(0xFF7C3AED)
val StashCyan = Color(0xFF06B6D4)
val StashCyanLight = Color(0xFF22D3EE)
val StashCyanDark = Color(0xFF0891B2)
val StashSpotifyGreen = Color(0xFF1DB954)
val StashYouTubeRed = Color(0xFFFF0033)
val StashTextPrimary = Color(0xFFE8E8F0)
val StashTextSecondary = Color(0xFFA0A0B8)
val StashTextTertiary = Color(0xFF606078)
val StashGlassBackground = Color(0x0AFFFFFF)
val StashGlassBackgroundHover = Color(0x14FFFFFF)
val StashGlassBorder = Color(0x0FFFFFFF)
val StashGlassBorderBright = Color(0x24FFFFFF)
val StashError = Color(0xFFEF4444)
val StashWarning = Color(0xFFF59E0B)
val StashSuccess = Color(0xFF10B981)

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

val LocalStashColors = staticCompositionLocalOf { StashExtendedColors() }
