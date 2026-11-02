package com.stash.core.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/** A badge's fill and its text. */
@Immutable
data class BadgeColors(val container: Color, val content: Color)

/** Material's baseline schemes: only their tertiary containers are read, once. */
private val BaselineDark = darkColorScheme()
private val BaselineLight = lightColorScheme()

/** The dark themes' badge: Material's baseline rose tertiary container under its ink. */
private val BadgeDark = BadgeColors(BaselineDark.tertiaryContainer, BaselineDark.onTertiaryContainer)

/** The light theme's badge, the same rose family. */
private val BadgeLight = BadgeColors(BaselineLight.tertiaryContainer, BaselineLight.onTertiaryContainer)

/**
 * The fixed colours of the app's small status badges (FLAC / Hi-Res, Beta): the rose they have always
 * worn, which was Material's baseline tertiary container. Since D9 the scheme's tertiary container is
 * the playing album's colour, and a quality badge must not change colour with every song, so the badges
 * read these instead. Rose, not purple (hue ~340).
 */
@Composable
@ReadOnlyComposable
fun badgeColors(): BadgeColors = if (LocalIsDarkTheme.current) BadgeDark else BadgeLight
