package com.stash.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stash.core.ui.R

/**
 * v0.9.13: Per-track override sheet. Pre-checked according to the
 * user's Settings defaults; subtitled with "✓ Already saved" for
 * destinations whose `*_saved_at` timestamp is already populated.
 *
 * The selection is ephemeral — saving doesn't update Settings
 * defaults. Defaults stay where they are.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LikeDestinationSheet(
    state: LikeDestinationSheetState,
    onDismiss: () -> Unit,
    onSave: (Set<DestinationCheckbox>) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var stashChecked by remember { mutableStateOf(state.stashChecked) }
    var spotifyChecked by remember { mutableStateOf(state.spotifyChecked) }
    var ytChecked by remember { mutableStateOf(state.ytChecked) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.action_save_to),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))

            DestinationRow(
                label = stringResource(R.string.like_dest_stash),
                subtitle = if (state.stashAlreadySaved) "✓ Already saved" else null,
                checked = stashChecked,
                onCheckedChange = { stashChecked = it },
            )

            if (state.spotifyVisible) {
                DestinationRow(
                    label = stringResource(R.string.like_dest_spotify),
                    subtitle = if (state.spotifyAlreadySaved) "✓ Already saved" else null,
                    checked = spotifyChecked,
                    onCheckedChange = { spotifyChecked = it },
                )
            }

            if (state.ytVisible) {
                DestinationRow(
                    label = stringResource(R.string.like_dest_youtube),
                    subtitle = if (state.ytAlreadySaved) "✓ Already saved" else null,
                    checked = ytChecked,
                    onCheckedChange = { ytChecked = it },
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val selected = buildSet {
                        if (stashChecked) add(DestinationCheckbox.STASH)
                        if (spotifyChecked) add(DestinationCheckbox.SPOTIFY)
                        if (ytChecked) add(DestinationCheckbox.YT_MUSIC)
                    }
                    onSave(selected)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_save_btn))
            }
        }
    }
}

@Composable
private fun DestinationRow(
    label: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * View state for [LikeDestinationSheet]. The sheet is purely a UI
 * concern; the caller (e.g., NowPlayingScreen) computes this from
 * the user's Settings defaults + the current track's `*_saved_at`
 * timestamps.
 */
data class LikeDestinationSheetState(
    val spotifyVisible: Boolean,
    val ytVisible: Boolean,
    val stashChecked: Boolean,
    val spotifyChecked: Boolean,
    val ytChecked: Boolean,
    val stashAlreadySaved: Boolean,
    val spotifyAlreadySaved: Boolean,
    val ytAlreadySaved: Boolean,
)

enum class DestinationCheckbox { STASH, SPOTIFY, YT_MUSIC }
