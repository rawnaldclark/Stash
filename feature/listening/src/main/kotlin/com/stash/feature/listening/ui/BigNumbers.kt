package com.stash.feature.listening.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import com.stash.core.ui.theme.SpaceGrotesk
import com.stash.feature.listening.R

/** Keeps a number and its unit on one line ("4 h", never "4 / h"). */
private const val NBSP = '\u00A0'

/**
 * A listening time the way Home's Your record card sets its hours: each number in Space Grotesk
 * Bold at [numberSize], its unit at half that in the secondary ink ("4 h 12 min"), and a small
 * "about" in front when [about] (some of it is estimated from song length). One Text, so the
 * pieces share a baseline. It wraps rather than clips: at a large font "1,234 h / 5 min" goes on
 * two lines, each number with its unit ([listenTimeParts]); there is no maxLines to cut it.
 *
 * [time] is [com.stash.feature.listening.ListeningLabels.listenTime]'s text.
 */
@Composable
internal fun ListenTimeText(
    time: String,
    about: Boolean,
    numberSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val numberStyle = SpanStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Bold,
        fontSize = numberSize,
        letterSpacing = (-0.02).em,
        color = scheme.onSurface,
    )
    val unitStyle = SpanStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = numberSize / 2,
        color = scheme.onSurfaceVariant,
    )
    val aboutStyle = MaterialTheme.typography.labelLarge.toSpanStyle().copy(color = scheme.onSurfaceVariant)
    val aboutWord = stringResource(R.string.listening_about)
    val text = buildAnnotatedString {
        if (about) {
            withStyle(aboutStyle) { append(aboutWord) }
            append(' ')
        }
        listenTimeParts(time).forEachIndexed { i, (number, unit) ->
            if (i > 0) append(' ')
            if (number.isNotEmpty()) {
                withStyle(numberStyle) { append(number) }
                if (unit.isNotEmpty()) append(NBSP)
            }
            withStyle(unitStyle) { append(unit) }
        }
    }
    Text(text = text, style = TextStyle(lineHeight = numberSize * 1.2f), modifier = modifier)
}

/**
 * A number over its word ("142" over "plays"): the number in [numberStyle] on one line, never
 * ellipsised (the callers keep it short with [CompactCount] and give it room by their layout
 * rules), the word under it in the small label style, free to wrap rather than be cut.
 */
@Composable
internal fun NumberOverLabel(
    value: String,
    label: String,
    numberStyle: TextStyle,
    modifier: Modifier = Modifier,
    labelStyle: TextStyle = MaterialTheme.typography.labelSmall,
) {
    Column(modifier) {
        Text(
            text = value,
            style = numberStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = label,
            style = labelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
