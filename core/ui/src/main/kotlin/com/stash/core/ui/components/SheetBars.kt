package com.stash.core.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

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
