package com.stash.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stash.core.ui.components.CrispChipRow

/**
 * The single Library "sort & filter" sheet (spec §2) — absorbs the two old
 * permanent chip rows. Sort options apply live; the source/FLAC filter uses the
 * shared [CrispChipRow]. Duration is offered only when the Songs chip is active
 * ([showDuration]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySortFilterSheet(
    sortOrder: SortOrder,
    sourceFilter: SourceFilter,
    showDuration: Boolean,
    onSortSelected: (SortOrder) -> Unit,
    onFilterSelected: (SourceFilter) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    val sorts = buildList {
        add(SortOrder.RECENT to "Recently added")
        add(SortOrder.ALPHABETICAL to "A – Z")
        add(SortOrder.MOST_PLAYED to "Most played")
        if (showDuration) add(SortOrder.DURATION to "Duration")
    }
    val filters = listOf(
        "All" to SourceFilter.ALL,
        "YouTube" to SourceFilter.YOUTUBE,
        "Spotify" to SourceFilter.SPOTIFY,
        "FLAC" to SourceFilter.FLAC,
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 28.dp)) {
            Text(
                text = "Sort & filter",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
            )

            SheetLabel("SORT BY")
            sorts.forEach { (order, label) ->
                val selected = order == sortOrder
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSortSelected(order) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    if (selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            SheetLabel("SHOW ONLY")
            CrispChipRow(
                chips = filters.map { it.first },
                selected = filters.first { it.second == sourceFilter }.first,
                onSelect = { label -> onFilterSelected(filters.first { it.first == label }.second) },
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                // Sheet column already pads horizontally — don't add the gutter.
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            )
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp),
    )
}
