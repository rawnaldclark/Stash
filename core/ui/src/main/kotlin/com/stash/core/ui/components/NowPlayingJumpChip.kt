package com.stash.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.stash.core.ui.ambient.LocalAlbumChrome
import com.stash.core.ui.ambient.rememberReducedMotion
import com.stash.core.ui.theme.StashMotion
import kotlinx.coroutines.launch

/**
 * "Now playing ↑ / ↓": a small pill that floats at the top of a track list while the playing
 * song's row is in the list but scrolled out of sight, and scrolls back to it on tap. Scroll a
 * 2,000-song library to the Ms while an R song plays and the way back is one tap, not a fling
 * hunt. It hides by itself once the row is on screen again.
 *
 * Call it inside the Box that holds the list, after the list (it draws on top). The decisions
 * (which item is the playing row, can the listener see it, which way it is) live in
 * [nowPlayingItemIndex] / [nowPlayingJumpDirection] so they are unit-tested; this composable
 * only feeds them the layout and paints the result. They run inside a derivedStateOf, so
 * scrolling recomposes nothing until the answer actually changes.
 *
 * A row under the glass mini player / tab bar ([LocalBottomChromeInset]) is laid out but not
 * seen, so it doesn't count as visible. The chip sits at the top, clear of that chrome and of
 * the snackbars that rise above it.
 *
 * TalkBack reads it as "Now playing, below, Button", with "double-tap to scroll to it". With
 * animations off (the system "Remove animations" switch) it appears and jumps without motion.
 *
 * @param listState  The list's state; the chip reads its layout and scrolls it.
 * @param trackIndex The playing track's position in the screen's track list, -1 when it isn't
 *   there (nothing playing, or filtered out by a search).
 * @param trackCount How many track rows the list has. The track rows must be the list's LAST
 *   items (bar [trailingItemCount] footers): see [nowPlayingItemIndex].
 * @param enabled    False hides the chip: multi-select, where a tap selects rows and the top
 *   edge belongs to the selection bar.
 */
@Composable
fun BoxScope.NowPlayingJumpChip(
    listState: LazyListState,
    trackIndex: Int,
    trackCount: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailingItemCount: Int = 0,
) {
    val bottomInsetPx = with(LocalDensity.current) { LocalBottomChromeInset.current.roundToPx() }
    // derivedStateOf only re-reads State objects, so the plain parameters go through one.
    val currentTrackIndex by rememberUpdatedState(trackIndex)
    val currentTrackCount by rememberUpdatedState(trackCount)
    val currentTrailing by rememberUpdatedState(trailingItemCount)
    val currentBottomInset by rememberUpdatedState(bottomInsetPx)

    val direction by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            nowPlayingJumpDirection(
                targetIndex = nowPlayingItemIndex(
                    trackIndex = currentTrackIndex,
                    trackCount = currentTrackCount,
                    totalItemCount = layout.totalItemsCount,
                    trailingItemCount = currentTrailing,
                ),
                visibleRows = layout.visibleItemsInfo.map { ListRowSpan(it.index, it.offset, it.size) },
                windowStart = layout.viewportStartOffset,
                windowEnd = layout.viewportEndOffset - currentBottomInset,
            )
        }
    }

    // The fade-out runs after direction has gone null (the row came into view): keep the arrow
    // pointing the way it last did instead of flipping mid-fade.
    var lastDirection by remember { mutableStateOf(NowPlayingJumpDirection.DOWN) }
    SideEffect { direction?.let { lastDirection = it } }

    val reducedMotion = rememberReducedMotion()
    val scope = rememberCoroutineScope()
    val jump: () -> Unit = {
        val target = nowPlayingItemIndex(
            trackIndex = currentTrackIndex,
            trackCount = currentTrackCount,
            totalItemCount = listState.layoutInfo.totalItemsCount,
            trailingItemCount = currentTrailing,
        )
        if (target >= 0) {
            scope.launch {
                val to = nowPlayingScrollIndex(target)
                // animateScrollToItem snaps most of a long distance and animates the last
                // stretch, so a jump across thousands of rows is still quick.
                if (reducedMotion) listState.scrollToItem(to) else listState.animateScrollToItem(to)
            }
        }
    }

    AnimatedVisibility(
        visible = enabled && direction != null,
        modifier = modifier
            .align(Alignment.TopCenter)
            .padding(top = 8.dp),
        enter = if (reducedMotion) {
            EnterTransition.None
        } else {
            fadeIn(tween(StashMotion.DUR_SHORT_MS)) +
                slideInVertically(tween(StashMotion.DUR_SHORT_MS)) { -it / 2 }
        },
        exit = if (reducedMotion) {
            ExitTransition.None
        } else {
            fadeOut(tween(StashMotion.DUR_SHORT_MS)) +
                slideOutVertically(tween(StashMotion.DUR_SHORT_MS)) { -it / 2 }
        },
    ) {
        val pointing = direction ?: lastDirection
        val up = pointing == NowPlayingJumpDirection.UP
        val chrome = LocalAlbumChrome.current
        val shape = RoundedCornerShape(50)
        // Small see-through capsule in the playing cover's colour: the playing row's own glyph + which way it is.
        Row(
            modifier = Modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                .background(chrome.accent.copy(alpha = 0.16f))
                .border(1.dp, chrome.accent.copy(alpha = 0.45f), shape)
                .clickable(onClickLabel = "Scroll to it", role = Role.Button, onClick = jump)
                .semantics(mergeDescendants = true) {
                    contentDescription = if (up) "Now playing, above" else "Now playing, below"
                }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = null,
                tint = chrome.accent,
                modifier = Modifier.size(16.dp),
            )
            Icon(
                imageVector = if (up) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                contentDescription = null,
                tint = chrome.accent,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
