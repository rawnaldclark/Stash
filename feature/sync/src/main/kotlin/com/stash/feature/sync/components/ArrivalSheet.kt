package com.stash.feature.sync.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.SpaceGrotesk
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.sync.R

/**
 * "How should your music arrive?": asked once, on a new install's first Sync
 * (the Sync tab overhaul, 2026-10-29). Stream everything is pre-selected: the
 * whole account shows up on Home and in Library after one sync, nothing
 * downloads. Save to this phone is Download mode, where the user picks what to
 * keep. [onChoose] gets `true` for Stream everything; the sync starts from it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArrivalSheet(
    spotifyConnected: Boolean,
    youTubeConnected: Boolean,
    onChoose: (streamEverything: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var streamEverything by rememberSaveable { mutableStateOf(true) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = StashTheme.extendedColors.elevatedSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (spotifyConnected) BrandDotLabel(StashTheme.extendedColors.spotifyGreen, "Spotify")
                    if (youTubeConnected) BrandDotLabel(StashTheme.extendedColors.youtubeRed, "YouTube Music")
                }
                Text(
                    text = stringResource(R.string.sync_arrival_title),
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            ArrivalOption(
                selected = streamEverything,
                title = stringResource(R.string.sync_arrival_stream_title),
                badge = stringResource(R.string.sync_arrival_recommended),
                body = stringResource(R.string.sync_arrival_stream_body),
                art = { SleeveFanArt() },
                onClick = { streamEverything = true },
            )
            ArrivalOption(
                selected = !streamEverything,
                title = stringResource(R.string.sync_arrival_save_title),
                badge = null,
                body = stringResource(R.string.sync_arrival_save_body),
                art = { PhoneArt() },
                onClick = { streamEverything = false },
            )

            Button(
                onClick = { onChoose(streamEverything) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Text(stringResource(R.string.sync_arrival_sync_now), style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = stringResource(R.string.sync_arrival_change_later),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun ArrivalOption(
    selected: Boolean,
    title: String,
    badge: String?,
    body: String,
    art: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) primary.copy(alpha = 0.16f) else StashTheme.extendedColors.glassBackground)
            .then(if (selected) Modifier.border(2.dp, StashTheme.extendedColors.purpleLight, shape) else Modifier)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { art() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (badge != null) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1A1033),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(StashTheme.extendedColors.purpleLight)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Three sleeves fanned: "everything shows up". Drawn, since a new user has no covers yet. */
@Composable
private fun SleeveFanArt() {
    val sleeves = listOf(
        Triple(listOf(Color(0xFFF2D06B), Color(0xFFB8862A)), -10f, 0.dp to 10.dp),
        Triple(listOf(Color(0xFFE2564B), Color(0xFF6B1B2A)), -2f, 12.dp to 6.dp),
        Triple(listOf(Color(0xFF6EC3D9), Color(0xFF1D3E66)), 7f, 24.dp to 2.dp),
    )
    Box(Modifier.size(64.dp)) {
        sleeves.forEachIndexed { i, (colors, angle, pos) ->
            val side = (40 + i * 2).dp
            Box(
                Modifier
                    .offset(pos.first, pos.second)
                    .size(side)
                    .rotate(angle)
                    .shadow(8.dp, RoundedCornerShape(7.dp))
                    .clip(RoundedCornerShape(7.dp))
                    .background(Brush.linearGradient(colors)),
            )
        }
    }
}

/** A phone outline with a download arrow: "kept on this phone". */
@Composable
private fun PhoneArt() {
    Box(
        Modifier
            .width(36.dp)
            .height(58.dp)
            .border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.ArrowDownward,
            contentDescription = null,
            tint = StashTheme.extendedColors.cyan,
            modifier = Modifier.size(20.dp),
        )
    }
}
