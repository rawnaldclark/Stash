package com.stash.feature.nowplaying.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/*
 * Now Playing's inks, shared by the screen, its top row and its lyrics mode. Everything drawn
 * straight on the ambient takes its colour from here, never from colorScheme.onSurface, which
 * is tuned for surfaces rather than for the ambient ground.
 */

/** Light-ground ink for the pastel-wash Now Playing (the app's plum-black). */
internal val NpInkLight = Color(0xFF241C36)

/** True when the resolved theme is light (the pastel wash rather than the dark ambient). */
@Composable
internal fun npIsLight(): Boolean = MaterialTheme.colorScheme.background.luminance() >= 0.5f

/**
 * Foreground ink for the Now Playing surface: white on the dark ambient,
 * plum-black on the light pastel wash. Reads the *resolved* theme background
 * (not the system setting) so manual/AMOLED overrides pick the right ink.
 */
@Composable
internal fun npInk(): Color = if (npIsLight()) NpInkLight else Color.White

/**
 * Album palettes can be too pale to hold contrast on the light wash — darken
 * toward ink until they do. Dark theme passes the raw palette through.
 */
@Composable
internal fun npAccent(raw: Color): Color =
    if (npIsLight() && raw.luminance() > 0.55f) lerp(raw, NpInkLight, 0.35f) else raw
