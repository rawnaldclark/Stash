package com.stash.core.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.stash.core.ui.R

/**
 * "FLAC" pill that surfaces wherever a track row is rendered. Renders a
 * small Material3 Surface with a one-line label. Returns nothing for
 * non-lossless tracks so existing call sites can blindly drop one in
 * next to a title and let the badge gate itself.
 *
 * Two display variants:
 *   - Plain `FLAC` — codec is lossless but bit-depth/sample-rate are
 *     unknown OR the track is at CD quality (16-bit/44.1 kHz). Hi-Res
 *     gets the qualifier; CD doesn't, on purpose — the visual is "this
 *     is FLAC" first, "this is Hi-Res FLAC" second, without crowding
 *     row layouts that already carry artist + album text.
 *   - `FLAC 24/96` / `FLAC 24/192` — Hi-Res variants.
 *
 * Optional [size] retained on the API for backwards compatibility
 * (some callers pass a larger value on Now Playing). Not currently
 * applied to the pill — the Surface uses fixed `padding(horizontal =
 * 6.dp, vertical = 2.dp)`. Wired-or-removed in a follow-up pass once
 * all callers are audited.
 *
 * @param fileFormat   Track codec — gates whether the badge renders at all.
 * @param bitsPerSample Track bit-depth (16/24/32) or null when unknown.
 * @param sampleRateHz Track sample rate (44100/96000/192000) or null when unknown.
 * @param size         Legacy parameter retained on the API for call-site
 *                     compatibility. Not currently applied to the pill — see
 *                     the description above.
 * @param tint         Optional override for the text color. Default is
 *                     `onTertiaryContainer` from the theme.
 */
@Composable
fun FlacBadge(
    fileFormat: String?,
    modifier: Modifier = Modifier,
    bitsPerSample: Int? = null,
    sampleRateHz: Int? = null,
    size: Dp = 14.dp,
    tint: Color? = null,
) {
    if (!isLossless(fileFormat)) return
    val text = flacBadgeText(bitsPerSample, sampleRateHz)
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tint ?: MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * Pure-function text generator for the badge — broken out so the Now
 * Playing quality line can reuse the same "Hi-Res qualifier or not"
 * rule without duplicating the threshold logic.
 */
@Composable
internal fun flacBadgeText(bitsPerSample: Int?, sampleRateHz: Int?): String = when {
    bitsPerSample == null || sampleRateHz == null -> stringResource(R.string.badge_flac)
    bitsPerSample <= 16 && sampleRateHz <= 44_100 -> stringResource(R.string.badge_flac)
    else -> stringResource(R.string.badge_flac_format, bitsPerSample, sampleRateHz / 1000)
}

private val LOSSLESS_CODECS = setOf("flac", "alac", "wav", "ape", "tta", "wv", "aiff")

private fun isLossless(format: String?): Boolean {
    if (format.isNullOrBlank()) return false
    return format.lowercase() in LOSSLESS_CODECS
}
