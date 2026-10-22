package com.stash.feature.nowplaying.ui

import android.provider.Settings
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One line of text that scrolls when it doesn't fit, instead of ending in "…": "I Got You
 * (I Feel Good)" reads whole rather than "I Got You (I Fe…". Text that fits stays still.
 *
 * The scroll waits [MARQUEE_DELAY_MS] before each pass, and while the text overflows its
 * trailing edge fades out so the cut reads as deliberate. With animations off (the system's
 * reduced-motion setting) it ellipsizes instead of moving.
 *
 * Nothing here recomposes per frame: the marquee runs in layout and draw, and the overflow
 * check reads plain fields written during measure, not snapshot state. The caller passes
 * stable values, so a 250ms position tick that recomposes Now Playing skips this text.
 */
@Composable
internal fun NpMarqueeText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (rememberNpReducedMotion()) {
        Text(text = text, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
        return
    }
    val widths = remember { MarqueeWidths() }
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = modifier
            // The box the text is shown in...
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints)
                widths.box = p.width
                layout(p.width, p.height) { p.place(0, 0) }
            }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (widths.content > widths.box) {
                    val fade = MARQUEE_FADE.toPx().coerceAtMost(size.width / 4f)
                    drawRect(
                        brush = Brush.horizontalGradient(
                            0f to Color.Black,
                            (size.width - fade) / size.width to Color.Black,
                            1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
            .basicMarquee(
                iterations = Int.MAX_VALUE,
                initialDelayMillis = MARQUEE_DELAY_MS,
                repeatDelayMillis = MARQUEE_DELAY_MS,
                spacing = MarqueeSpacing.fractionOfContainer(1f / 3f),
                velocity = MARQUEE_VELOCITY,
            )
            // ...and the text's own width, measured unbounded inside the marquee.
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints)
                widths.content = p.width
                layout(p.width, p.height) { p.place(0, 0) }
            },
    )
}

/** Measured widths, written during measure and read in draw (plain fields: no recomposition). */
private class MarqueeWidths {
    var box = 0
    var content = 0
}

/** The system "animator duration scale" set to off (Remove animations): the reduced-motion case. */
@Composable
internal fun rememberNpReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        try {
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: Exception) {
            false
        }
    }
}

/** A beat to read the start before it moves, and again before each repeat. */
private const val MARQUEE_DELAY_MS = 3_800

/** Slower than the default 30dp/s: a title should be read, not chased. */
private val MARQUEE_VELOCITY = 26.dp

/** The trailing fade while the text overflows. */
private val MARQUEE_FADE = 16.dp
