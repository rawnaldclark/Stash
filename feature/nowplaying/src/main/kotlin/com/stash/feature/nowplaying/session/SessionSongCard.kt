package com.stash.feature.nowplaying.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stash.core.ui.components.MinTouch
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.nowplaying.R

/** The phrase that also opens the song's card for whoever said it (UX spec §5: "Who is this?"). */
internal const val PHRASE_WHO = "who"

/** What the song card says about the room's song. */
internal data class SongCard(val title: String, val artist: String, val album: String?)

/** The room's song as its card shows it: the album only when there is one, and it isn't just the title again (a single). */
internal fun songCard(title: String, artist: String, album: String?): SongCard =
    SongCard(title, artist, album?.trim()?.takeIf { it.isNotEmpty() && !it.equals(title.trim(), ignoreCase = true) })

/**
 * The song's card, for the one who asked "Who is this?": the cover, the song, its artist and album, Save, and
 * Go to artist. [liked] null: the song can't be saved on this phone, and Save isn't offered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SongCardSheet(
    card: SongCard,
    art: Any?,
    liked: Boolean?,
    onSave: () -> Unit,
    onGoToArtist: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ground = StashTheme.extendedColors.elevatedSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ground,
        properties = com.stash.core.ui.components.sheetBarsFollow(ground),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(StashTheme.extendedColors.glassBackground),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = muted)
                    if (art != null) AsyncImage(art, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        card.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(card.artist, style = MaterialTheme.typography.bodyLarge, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    card.album?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Column(Modifier.padding(top = 14.dp)) {
                if (liked != null) {
                    CardAction(
                        icon = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        label = stringResource(if (liked) R.string.room_saved else R.string.room_save),
                        enabled = !liked,
                        onClick = onSave,
                    )
                }
                if (card.artist.isNotBlank()) {
                    CardAction(Icons.Rounded.Person, stringResource(R.string.song_card_artist), enabled = true, onClick = { onGoToArtist(); onDismiss() })
                }
            }
        }
    }
}

@Composable
private fun CardAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouch)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
