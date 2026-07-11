package com.stash.core.ui.components.streaming

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stash.core.ui.components.OnlineOfflinePicker
import com.stash.core.ui.theme.StashTheme

/**
 * Bottom-sheet host for the [OnlineOfflinePicker] used by the Home tab.
 * Opens when the user taps the [StreamingModeChip]; closes after a
 * tile selection so a single mode flip is one tap on the chip + one
 * tap on the tile.
 *
 * @param streamingEnabled the current pref value (mirrors what the chip
 *   reads, so the sheet renders the same selected tile).
 * @param onSelect invoked when the user picks a tile. The host
 *   ViewModel routes this through `applyStreamingMode` so workers stay
 *   in sync.
 * @param onDismiss called when the user swipes the sheet down or taps
 *   outside; the host clears the open-flag.
 * @param sheetState Material3 sheet state controlled by the host.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamingModeSheet(
    streamingEnabled: Boolean,
    onSelect: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = extendedColors.elevatedSurface,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(com.stash.core.ui.R.string.title_playback_mode),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(com.stash.core.ui.R.string.desc_streaming_mode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            OnlineOfflinePicker(
                streamingEnabled = streamingEnabled,
                onSelect = onSelect,
            )
        }
    }
}
