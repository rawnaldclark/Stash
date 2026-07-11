package com.stash.core.ui.selection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.stash.core.ui.R
import com.stash.core.ui.theme.StashTheme

/** One batch action surfaced in the [SelectionBottomBar]. */
data class SelectionAction(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/**
 * Contextual header overlaid at the top of a selection-active screen:
 * `[✕]  "N selected"  [Select all]`.
 *
 * Rendered as a translucent glass surface (matching [com.stash.core.ui.components.GlassCard])
 * so it floats over the screen content rather than reading as an opaque app bar.
 */
@Composable
fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors
    Surface(
        modifier = modifier.fillMaxWidth(),
        // Mostly-opaque dark base (cohesive with the nav bar this replaces) keeps the
        // labels readable over busy track art / headers, while ~8% translucency
        // preserves the glass feel. The faint white glass tint sits on top for depth.
        color = MaterialTheme.colorScheme.surface
            .copy(alpha = 0.92f)
            .compositeOver(extendedColors.glassBackground),
        border = BorderStroke(1.dp, extendedColors.glassBorderBright),
    ) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.selection_exit),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = "$count selected",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            TextButton(onClick = onSelectAll) {
                Icon(
                    imageVector = Icons.Default.DoneAll,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.selection_select_all),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/**
 * Bottom batch-action bar. Shows up to four labeled actions; any extras collapse
 * into a trailing `⋮` overflow [DropdownMenu].
 *
 * Rendered as a translucent glass surface to mirror the rest of the chrome.
 */
@Composable
fun SelectionBottomBar(
    actions: List<SelectionAction>,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors
    val primary = actions.take(4)
    val overflow = actions.drop(4)
    var overflowExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        // Mostly-opaque dark base (cohesive with the nav bar this replaces) keeps the
        // labels readable over busy track art / headers, while ~8% translucency
        // preserves the glass feel. The faint white glass tint sits on top for depth.
        color = MaterialTheme.colorScheme.surface
            .copy(alpha = 0.92f)
            .compositeOver(extendedColors.glassBackground),
        border = BorderStroke(1.dp, extendedColors.glassBorderBright),
    ) {
        Row(
            modifier = Modifier
                .navigationBarsPadding()
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            primary.forEach { action ->
                LabeledAction(
                    icon = action.icon,
                    label = action.label,
                    onClick = action.onClick,
                )
            }

            if (overflow.isNotEmpty()) {
                Box {
                    LabeledAction(
                        icon = Icons.Default.MoreVert,
                        label = stringResource(R.string.selection_more),
                        onClick = { overflowExpanded = true },
                    )
                    DropdownMenu(
                        expanded = overflowExpanded,
                        onDismissRequest = { overflowExpanded = false },
                    ) {
                        overflow.forEach { action ->
                            DropdownMenuItem(
                                text = { Text(action.label) },
                                onClick = {
                                    overflowExpanded = false
                                    action.onClick()
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = action.icon,
                                        contentDescription = null,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** An icon stacked over a small label — one tappable column in the [SelectionBottomBar]. */
@Composable
private fun LabeledAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── Previews ───────────────────────────────────────────────────────────────

@Preview(name = "SelectionTopBar", showBackground = true, backgroundColor = 0xFF06060C)
@Composable
private fun PreviewSelectionTopBar() {
    StashTheme {
        SelectionTopBar(count = 3, onClose = {}, onSelectAll = {})
    }
}

@Preview(name = "SelectionBottomBar", showBackground = true, backgroundColor = 0xFF06060C)
@Composable
private fun PreviewSelectionBottomBar() {
    StashTheme {
        SelectionBottomBar(
            actions = listOf(
                SelectionAction("queue", "Queue", Icons.Default.Close) {},
                SelectionAction("playlist", "Playlist", Icons.Default.Close) {},
                SelectionAction("download", "Download", Icons.Default.Close) {},
                SelectionAction("delete", "Delete", Icons.Default.Close) {},
                SelectionAction("share", "Share", Icons.Default.Close) {},
            ),
        )
    }
}
