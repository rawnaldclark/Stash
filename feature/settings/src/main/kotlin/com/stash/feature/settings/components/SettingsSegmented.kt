package com.stash.feature.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A horizontal segmented control. Renders [options] as equal-width segments over a
 * faint track; the segment at [selectedIndex] is filled in the theme's primary (the playing album's
 * colour, neutral with nothing playing; it was a fixed purple gradient until D9) under onPrimary,
 * which the scheme holds at 4.5:1 on it, and the fill stands well clear of the track in every theme.
 * Tapping a segment invokes [onSelect] with its index. Pure presentation — the
 * caller owns selection state.
 */
@Composable
fun SettingsSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** False greys the control out and ignores taps (its setting doesn't apply right now). */
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.38f)
            .clip(RoundedCornerShape(12.dp))
            // Theme-aware track: a faint tint of onSurface so the control reads on
            // both the dark (#06060C) and light (#F6F3FF) backgrounds.
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .padding(4.dp),
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .then(
                        if (active) {
                            Modifier.background(MaterialTheme.colorScheme.primary)
                        } else {
                            Modifier
                        },
                    )
                    .clickable(enabled = enabled) { onSelect(index) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    // The active pill's own "on" colour: readable on it in both themes.
                    color = if (active) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}
