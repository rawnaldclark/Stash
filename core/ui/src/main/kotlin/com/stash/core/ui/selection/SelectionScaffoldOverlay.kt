package com.stash.core.ui.selection

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import com.stash.core.ui.components.bottomChromePadding

/**
 * Overlays the contextual selection chrome (top "N selected" bar + bottom action bar)
 * on a track-list screen while [selection] is active. Call inside the screen's root Box.
 *
 * The screen owns its own [actions] list (action sets differ per screen) and any gated
 * confirm dialogs/sheets; this helper only handles the identical chrome + select-all toggle.
 */
@Composable
fun BoxScope.SelectionScaffoldOverlay(
    selection: SelectionState,
    allIds: List<Long>,
    actions: List<SelectionAction>,
) {
    AnimatedVisibility(
        visible = selection.isActive,
        modifier = Modifier.align(Alignment.TopCenter),
    ) {
        SelectionTopBar(
            count = selection.count,
            onClose = { selection.clear() },
            onSelectAll = {
                if (selection.count == allIds.size) selection.clear() else selection.selectAll(allIds)
            },
        )
    }
    AnimatedVisibility(
        visible = selection.isActive,
        // The scaffold hides the bottom chrome while a screen selects (the inset is then 0);
        // should that signal ever be missed, the bar still lifts clear of the glass chrome.
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = bottomChromePadding()),
    ) {
        SelectionBottomBar(actions = actions)
    }
}
