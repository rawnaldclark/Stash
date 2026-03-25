package com.stash.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stash.core.model.MusicSource
import com.stash.core.ui.theme.StashTheme

@Composable
fun SourceIndicator(source: MusicSource, modifier: Modifier = Modifier, size: Dp = 6.dp) {
    val extendedColors = StashTheme.extendedColors
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        if (source == MusicSource.SPOTIFY || source == MusicSource.BOTH) {
            Box(Modifier.size(size).clip(CircleShape).background(extendedColors.spotifyGreen))
        }
        if (source == MusicSource.YOUTUBE || source == MusicSource.BOTH) {
            Box(Modifier.size(size).clip(CircleShape).background(extendedColors.youtubeRed))
        }
    }
}
