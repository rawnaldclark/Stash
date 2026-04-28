package com.stash.feature.sync.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Small status pill used in source preferences card summaries.
 *
 * Variants:
 * - Brand-tinted (provide [brandColor]): translucent brand background, brand-color text
 * - Purple-tinted (no brandColor, primary == true): translucent primary background, primary text
 * - Muted (no brandColor, primary == false): faint white background, secondary text
 */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    brandColor: Color? = null,
    primary: Boolean = false,
) {
    val (background, foreground, border) = when {
        brandColor != null -> Triple(
            brandColor.copy(alpha = 0.15f),
            brandColor,
            Color.Transparent,
        )
        primary -> Triple(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
        )
        else -> Triple(
            Color.White.copy(alpha = 0.05f),
            MaterialTheme.colorScheme.onSurfaceVariant,
            Color.Transparent,
        )
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = foreground,
        modifier = modifier
            .background(background, RoundedCornerShape(999.dp))
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
