package com.stash.core.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Small icon badge that surfaces "this is a lossless track" wherever a
 * track is rendered. Visual cue is intentionally subtle — just an icon,
 * no text — so it slots into existing track-row layouts without changing
 * line heights or truncation behaviour.
 *
 * Renders nothing when the track is not lossless. Caller passes the raw
 * [com.stash.core.model.Track.fileFormat] string; no extra logic at the
 * call site.
 *
 * Lossless detection uses the same codec set as
 * `com.stash.data.download.lossless.AudioFormat.LOSSLESS_CODECS`, kept
 * duplicated here because `:core:ui` shouldn't depend on `:data:download`.
 * The codec list is short and stable.
 *
 * @param fileFormat  The track's container codec — typically
 *   `Track.fileFormat`, e.g. "flac", "opus", "m4a", "mp3".
 * @param size        Icon size. 14 dp by default — pairs with bodyMedium
 *   title text in standard track rows. Bump to 16-20 dp on Now Playing
 *   where the title is larger.
 * @param tint        Override colour. Default is the theme primary, the
 *   same accent the app uses elsewhere for emphasis.
 */
@Composable
fun FlacBadge(
    fileFormat: String?,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    if (!isLossless(fileFormat)) return
    Icon(
        imageVector = Icons.Filled.HighQuality,
        contentDescription = "Lossless",
        tint = tint,
        modifier = modifier.size(size),
    )
}

private val LOSSLESS_CODECS = setOf("flac", "alac", "wav", "ape", "tta", "wv", "aiff")

private fun isLossless(format: String?): Boolean {
    if (format.isNullOrBlank()) return false
    return format.lowercase() in LOSSLESS_CODECS
}
