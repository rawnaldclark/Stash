package com.stash.core.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme

/**
 * A horizontal, scrollable row of filter chips (Premium Crisp, spec §3 / mock C).
 *
 * Data-agnostic: renders [chips], marks [selected] as active, and reports taps
 * via [onSelect]. The active chip is filled with the primary accent; inactive
 * chips use the elevated glass surface. Selection *logic* lives in the caller —
 * this component only paints and reports.
 *
 * @param chips    Ordered chip labels to render.
 * @param selected The currently-active label (matched by value).
 * @param onSelect Called with the tapped label.
 * @param contentPadding Applied *inside* the scrollable content (like LazyRow's
 *   contentPadding), so the first chip starts on the 20dp header gutter while
 *   scrolled chips still bleed edge-to-edge. Sheets pass 0 — their container
 *   already pads.
 */
@Composable
fun CrispChipRow(
    chips: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp),
) {
    val extendedColors = StashTheme.extendedColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chips.forEach { chip ->
            val isActive = chip == selected
            Surface(
                onClick = { onSelect(chip) },
                shape = RoundedCornerShape(16.dp),
                color = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    extendedColors.elevatedSurface
                },
            ) {
                Text(
                    text = chip,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isActive) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
                )
            }
        }
    }
}
