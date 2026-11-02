package com.stash.core.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Stash's app-wide switch, full M3 anatomy, in the theme's accent (D9, 2026-11-01): the playing
 * album's colour, neutral with nothing playing (it was a fixed plum).
 *
 * ON  = filled track in primary, a thumb in onPrimary with a small check in primary (4.5:1 by the
 *       scheme's own rule, ThemeAccent.kt).
 * OFF = outlined track, small grey thumb (state reads by shape, not just hue).
 *
 * Single source of truth for every toggle (sync flow + settings).
 */
@Composable
fun StashSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val trackOn = scheme.primary
    val thumbOn = scheme.onPrimary
    // The off state is the theme's secondary text grey: visible on black, never a colour.
    val thumbOff = scheme.onSurfaceVariant
    val borderOff = scheme.onSurfaceVariant.copy(alpha = OFF_BORDER_ALPHA)
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        // null when unchecked keeps M3's small off-thumb; a non-null lambda
        // (even one that draws nothing) would inflate it to the 24dp on-size.
        thumbContent = if (checked) {
            {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize),
                )
            }
        } else {
            null
        },
        colors = SwitchDefaults.colors(
            checkedThumbColor = thumbOn,
            checkedTrackColor = trackOn,
            checkedBorderColor = Color.Transparent,
            checkedIconColor = trackOn,
            uncheckedThumbColor = thumbOff,
            uncheckedTrackColor = Color.Transparent,
            uncheckedBorderColor = borderOff,
        ),
    )
}

/** The off track's outline: the off thumb's grey, lighter, so the thumb still leads. */
private const val OFF_BORDER_ALPHA = 0.55f
