package com.stash.feature.search

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

/**
 * Shows a rail's new first card when a refresh puts one in front of the card it started on.
 *
 * The artist page's rails are keyed, and a keyed row keeps its first visible card in place when
 * its list changes: a card added in front of it lands off-screen to the left. The page paints
 * the YouTube profile first and the enriched one a moment later (a cached one, then its refresh),
 * and the enriched one can put a newer release first: Sonic Youth's Latest releases opened on
 * "Hold That Tiger" (2025) with the Qobuz "Live in Berlin…" (2026) hidden to its left.
 *
 * A rail still at its start ([returnsToFirstCard]) goes back to card 0; one the user scrolled
 * keeps its card. Checked in a [SideEffect]: after the new cards are composed and before they're
 * laid out, so the position read is still the one on screen (as History keeps its row).
 *
 * @param firstKey the key of the rail's first card, null when it has none.
 * @param enabled false while something else places the rail (the Now Playing jump to an album).
 */
@Composable
internal fun ShowNewFirstCard(state: LazyListState, firstKey: Any?, enabled: Boolean = true) {
    // Not state: only the next SideEffect reads it, and writing it must not recompose.
    val shownFirstKey = remember { arrayOfNulls<Any>(1) }
    SideEffect {
        val previous = shownFirstKey[0]
        shownFirstKey[0] = firstKey
        if (enabled && returnsToFirstCard(previous, firstKey, state.firstVisibleItemIndex)) {
            state.requestScrollToItem(0)
        }
    }
}

/**
 * The rail's first card changed and the rail was at its start: its first card still (partly) in
 * view. Not on the first paint ([previousFirstKey] null), when there is no old card to stay on.
 */
internal fun returnsToFirstCard(previousFirstKey: Any?, firstKey: Any?, firstVisibleIndex: Int): Boolean =
    previousFirstKey != null && firstKey != null && firstKey != previousFirstKey && firstVisibleIndex == 0
