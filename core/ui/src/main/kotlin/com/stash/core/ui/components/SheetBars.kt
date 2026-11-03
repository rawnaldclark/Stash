package com.stash.core.ui.components

import android.os.Build
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * A ModalBottomSheet's window properties with its status and navigation bars following the sheet's
 * own [containerColor]: light bars (dark icons, a light navigation bar scrim) on a light sheet, dark
 * ones on a dark sheet. For a sheet on the app's elevatedSurface, which isn't one of the scheme's
 * sheet surfaces.
 *
 * Material3 (1.4) sets the sheet window's bars from the sheet's contentColor, and for a container
 * outside the scheme that is [androidx.compose.material3.contentColorFor]'s pick. The dark themes'
 * elevatedSurface is their surfaceVariant, so the pick is onSurfaceVariant: a grey (#A0A0B8) under
 * Material's 0.5 luminance cut, so "dark", so light bars on a dark sheet. With 3-button navigation
 * that drew a white strip under Now Playing's sheets, where Library's Sort & filter (the default
 * container, whose content colour is onSurface) has a black one; and dark status icons.
 */
@OptIn(ExperimentalMaterial3Api::class)
fun sheetBarsFollow(containerColor: Color): ModalBottomSheetProperties {
    val light = lightBarsOn(containerColor)
    return ModalBottomSheetProperties(isAppearanceLightStatusBars = light, isAppearanceLightNavigationBars = light)
}

/** A sheet of [containerColor] takes light bars: it is light, by Material's own cut (luminance over 0.5). */
internal fun lightBarsOn(containerColor: Color): Boolean = containerColor.luminance() > 0.5f

/**
 * Call first inside a ModalBottomSheet's content: the sheet's own colour under the navigation bar, with no system scrim
 * over it.
 *
 * With 3-button navigation Android lays a translucent scrim over a see-through navigation bar so its buttons stay
 * readable: near-white with light bars, dark with dark ones. Under a light sheet that was a white strip below a lavender
 * sheet (#814, the update sheet in the light theme; the dark one came out a shade off too). [sheetBarsFollow] already
 * picks buttons that read on the sheet's colour, so the scrim isn't needed. Gesture navigation draws no scrim either way.
 */
@Composable
fun SheetNavigationBarUnscrimmed() {
    val view = LocalView.current
    DisposableEffect(view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val window = generateSequence(view.parent) { it.parent }.filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
            window?.isNavigationBarContrastEnforced = false
        }
        onDispose { }
    }
}
