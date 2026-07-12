package com.stash.feature.settings.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashCyan
import com.stash.core.ui.theme.StashPurple
import com.stash.core.ui.theme.StashPurpleLight

/**
 * A premium gradient Support card for the Settings hub. Renders a one-line pitch,
 * two primary actions (Sponsor / Star), and a secondary Ko-fi link. Pure
 * presentation — the hub screen wires the URL handlers via [onSponsor], [onStar],
 * and [onKofi].
 *
 * GitHub Sponsors is the preferred path (0% platform fee, so 100% reaches the
 * maintainer); Ko-fi stays available as a lower-friction option for donors
 * without a GitHub account.
 */
@Composable
fun SupportBanner(
    onSponsor: () -> Unit,
    onStar: () -> Unit,
    onKofi: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        StashPurple.copy(alpha = 0.16f),
                        StashCyan.copy(alpha = 0.06f),
                    ),
                ),
            )
            .border(
                BorderStroke(1.dp, StashPurpleLight.copy(alpha = 0.28f)),
                RoundedCornerShape(20.dp),
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Support Stash",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "If Stash replaced a subscription for you, consider supporting the project.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onSponsor,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Sponsor", style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                onClick = onStar,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Star", style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(
            text = "also on Ko-fi ↗",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(onClick = onKofi)
                .padding(vertical = 2.dp),
        )
    }
}
