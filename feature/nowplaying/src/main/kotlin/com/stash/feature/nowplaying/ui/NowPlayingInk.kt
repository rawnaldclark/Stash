package com.stash.feature.nowplaying.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.stash.core.ui.ambient.AlbumChrome
import com.stash.core.ui.ambient.LocalAlbumChrome

/*
 * Now Playing's inks, shared by the screen, its top row and its lyrics mode. Everything drawn
 * straight on the ambient takes its colour from here, never from colorScheme.onSurface, which
 * is tuned for surfaces rather than for the ambient ground. The album's own colours come from
 * LocalAlbumChrome (see [WithAlbumChrome]).
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
 * Runs [content] with the app's album colours ([LocalAlbumChrome]) in its own recompose scope: a
 * colour read inside it during the 800 ms crossfade recomposes [content] alone, not the screen
 * around it. The colours themselves are contrast-checked roles (AccentRoles), the same the mini
 * player wears, so nothing here adjusts them for the theme any more.
 */
@Composable
internal fun WithAlbumChrome(content: @Composable (AlbumChrome) -> Unit) {
    content(LocalAlbumChrome.current)
}
