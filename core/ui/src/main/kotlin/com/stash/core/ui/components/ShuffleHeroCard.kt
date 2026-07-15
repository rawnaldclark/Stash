package com.stash.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stash.core.ui.theme.SpaceGrotesk
import com.stash.core.ui.theme.StashElevation

/**
 * The Library "listen now" hero (Premium Crisp) — a violet-gradient card that
 * shuffles the whole offline library. Sibling of [DiscoverHeroCard]; the whole
 * card and the round button both fire [onShuffle].
 *
 * @param songCount total downloaded songs, shown as the headline numeral.
 */
@Composable
fun ShuffleHeroCard(
    songCount: Int,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(StashElevation.Hero, shape, clip = false)
            .clip(shape)
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF7C3AED), Color(0xFF5B21B6), Color(0xFF3B1671)),
                ),
            )
            .clickable(onClick = onShuffle)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "SHUFFLE YOUR LIBRARY",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                    color = Color(0xFFC7B8FF),
                )
                Text(
                    text = "$songCount songs",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = SpaceGrotesk,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = Color.White,
                )
                Text(
                    text = "all offline · lossless where available",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f),
                )
            }
            Surface(
                onClick = onShuffle,
                shape = CircleShape,
                color = Color.White,
                modifier = Modifier.size(50.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = "Shuffle your library",
                    tint = Color(0xFF1A1030),
                    modifier = Modifier.padding(13.dp),
                )
            }
        }
    }
}
