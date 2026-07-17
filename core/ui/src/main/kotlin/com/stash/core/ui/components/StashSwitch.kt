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
import androidx.compose.ui.graphics.luminance

/**
 * Stash's app-wide switch: plum & cream (earthy purple), full M3 anatomy.
 *
 * ON  = filled plum track, warm cream thumb with a small plum check.
 * OFF = outlined track, small muted thumb (state reads by shape, not just hue).
 *
 * Single source of truth for every toggle (sync flow + settings) — replaces
 * the old primary-violet-on-half-alpha-violet look that washed out on the
 * lavender ground.
 */
@Composable
fun StashSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // Follow the *resolved* app theme (manual/AMOLED overrides included), not
    // the system setting — dark ground lifts the plum a step so it stays
    // visible against near-black.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val trackOn = if (dark) Color(0xFF7E6A90) else Color(0xFF6E5A7E)
    val thumbOn = Color(0xFFF2ECE2)
    val borderOff = if (dark) Color(0xFF4A4160) else Color(0xFFB9AEC8)
    val thumbOff = if (dark) Color(0xFF8A7FA0) else Color(0xFF8F84A0)
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
