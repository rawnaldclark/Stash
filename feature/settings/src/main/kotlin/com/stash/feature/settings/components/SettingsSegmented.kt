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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTextPrimary

/**
 * A horizontal segmented control. Renders [options] as equal-width segments over a
 * faint track; the segment at [selectedIndex] gets a purple-tinted gradient fill.
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
                            Modifier.background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF3A2D6B), Color(0xFF2A2150)),
                                ),
                            )
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
                    // The active pill is always a dark purple gradient, so its label
                    // must stay light in BOTH themes (onSurface would go dark — and
                    // illegible — in light mode).
                    color = if (active) {
                        StashTextPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}
