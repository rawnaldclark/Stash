package com.stash.feature.library.share

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.common.ArtUrlUpgrader
import com.stash.core.model.share.ShareConfig
import com.stash.core.model.share.SharedTrack
import com.stash.core.ui.theme.StashTheme

/**
 * A shared song's cover: the link's own art (a short link also carries the art its page found), else
 * the song's YouTube thumbnail, which for a YouTube Music upload is the album cover centred in the frame.
 * Only cover hosts are ever loaded: a link's art comes from whoever made it.
 */
internal fun SharedTrack.coverUrl(): String? =
    artUrl?.takeIf(ShareConfig::isAllowedCover)?.let(ArtUrlUpgrader::upgrade)
        ?: youtubeId?.takeIf { it.isNotBlank() }?.let(ArtUrlUpgrader::youTubeThumbnail)

/**
 * One cover, or a 2×2 mosaic when there are four (a mix's covers), on a soft gradient that also stands in
 * while they load or when there are none.
 */
@Composable
internal fun SharedCover(urls: List<String>, modifier: Modifier = Modifier, quiet: Boolean = false) {
    Box(
        // A row of songs without art reads better as quiet glass tiles than a column of purple ones.
        if (quiet) {
            modifier.background(StashTheme.extendedColors.glassBackground)
        } else {
            modifier.background(
                Brush.linearGradient(
                    listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)),
                ),
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.MusicNote,
            contentDescription = null,
            tint = if (quiet) StashTheme.extendedColors.textTertiary else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxSize(if (quiet) 0.45f else 0.3f),
        )
        when {
            urls.size >= 4 -> Column(Modifier.fillMaxSize()) {
                for (pair in urls.take(4).chunked(2)) Row(Modifier.fillMaxWidth().weight(1f)) {
                    for (url in pair) AsyncImage(url, null, Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop)
                }
            }
            urls.isNotEmpty() -> AsyncImage(urls.first(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

/**
 * The top of a shared song or mix: the cover full width, fading into the page, with a glass Back over it
 * (the Library playlist header's look).
 */
@Composable
internal fun SharedHeroCover(urls: List<String>, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
        SharedCover(urls, Modifier.fillMaxSize())
        // Top shade: Back stays legible on a bright or busy cover.
        Box(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent))),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))),
        )
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 8.dp, top = 8.dp)
                .align(Alignment.TopStart)
                .size(44.dp)
                .background(Color.Black.copy(alpha = 0.35f), CircleShape),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
    }
}
