package com.stash.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.stash.core.ui.theme.StashBackground
import com.stash.core.ui.theme.StashPurpleDark
import com.stash.core.ui.util.ArtSizeInterceptor

/**
 * A card's background: its cover blurred and darkened from the left, or Stash's purple gradient with no cover.
 * Always dark in every theme, so content on it is light. Blur is a no-op below API 31; the scrim still carries it.
 */
@Composable
fun BlurredCoverBackdrop(model: Any?, modifier: Modifier = Modifier) {
    Box(modifier) {
        if (model != null) {
            // Blurred at 24dp, so a small decode is all it needs: no bigger variant (a Qobuz `_max`
            // would be up to 2 MB for detail the blur throws away), and the same URL as the crisp
            // cover beside it, so both share one download.
            val context = LocalContext.current
            val backdrop = remember(context, model) {
                ImageRequest.Builder(context)
                    .data(model)
                    .size(BACKDROP_DECODE_PX)
                    .apply { extras[ArtSizeInterceptor.SkipSizing] = true }
                    .build()
            }
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(24.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(listOf(StashPurpleDark, StashBackground))),
            )
        }
        // Scrim: darker on the left where the text sits, so it stays legible.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Black.copy(alpha = 0.6f), Color.Black.copy(alpha = 0.25f)),
                    ),
                ),
        )
    }
}

/** The backdrop decode size: the blur throws the detail away, so a small decode is enough. */
private const val BACKDROP_DECODE_PX = 256
