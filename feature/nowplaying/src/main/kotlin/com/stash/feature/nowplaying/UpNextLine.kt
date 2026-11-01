package com.stash.feature.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.stash.feature.nowplaying.ui.LocalNpSpacing
import com.stash.feature.nowplaying.ui.npInk

/**
 * "Up next · Title — Artist" under Now Playing's transport, one line, quiet ink. A tap opens the
 * queue sheet, or the Session sheet in a Listen Together room (its Up next lives there). Empty,
 * but still holding its height, when nothing is coming ([peek] null); see [upNextPeek] and
 * [roomUpNextPeek] for what counts as next under shuffle, repeat, radio and a room.
 *
 * One TalkBack node: "Up next: Title by Artist. Opens the queue.", a button.
 */
@Composable
internal fun UpNextLine(
    peek: UpNextPeek?,
    inRoom: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    alignStart: Boolean = false,
) {
    // The line's height (NpSpacing.upNextHeight, 40 dp), taken whether or not it shows: the queue
    // running out on its last song must not grow the art under the listener's eyes. 40 dp: a
    // comfortable target for a full-width row without costing the art a full 48.
    val spacing = LocalNpSpacing.current
    val height = spacing.upNextHeight.dp
    if (peek == null) {
        Box(modifier = modifier.fillMaxWidth().height(height))
        return
    }
    // Start-aligned (as the title is): the text starts at the title's edge, so no padding before it.
    val sidePadding = spacing.upNextSidePadding.dp
    val description = upNextDescription(peek, inRoom)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = description }
            .padding(start = if (alignStart) 0.dp else sidePadding, end = sidePadding),
        contentAlignment = if (alignStart) Alignment.CenterStart else Alignment.Center,
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("Up next") }
                append(" · ")
                append(upNextDetail(peek))
            },
            style = MaterialTheme.typography.labelMedium,
            color = npInk().copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (alignStart) TextAlign.Start else TextAlign.Center,
            // The row's description says it all, and where a tap goes.
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}
