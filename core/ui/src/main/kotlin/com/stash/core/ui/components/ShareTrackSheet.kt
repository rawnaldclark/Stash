package com.stash.core.ui.components

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme

/**
 * Fork issue ParaliyzedEvo/Stash#40: share the song as a link your friends
 * can actually open — Spotify or YouTube Music when the track carries those
 * identities, plain "Artist — Title" text always. Fires the system share
 * chooser; no in-app social plumbing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareTrackSheet(
    title: String,
    artist: String,
    spotifyUri: String?,
    youtubeId: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()
    val spotifyUrl = spotifyShareUrl(spotifyUri)
    val youtubeUrl = youtubeShareUrl(youtubeId)

    fun send(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share \"$title\""))
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 8.dp),
        ) {
            Text(
                text = "Share \"$title\"",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (spotifyUrl != null) {
            ShareRow(
                label = "Spotify link",
                tint = StashTheme.extendedColors.spotifyGreen,
                onClick = { send("$artist — $title\n$spotifyUrl") },
            )
        }
        if (youtubeUrl != null) {
            ShareRow(
                label = "YouTube Music link",
                tint = StashTheme.extendedColors.youtubeRed,
                onClick = { send("$artist — $title\n$youtubeUrl") },
            )
        }
        ShareRow(
            label = "Song info only",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = { send("$artist — $title") },
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ShareRow(label: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (label.startsWith("Song")) Icons.Default.Share else Icons.Default.MusicNote,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * `spotify:track:ID` (or a full open.spotify.com URL, passed through) →
 * a shareable https link. Null when the track has no Spotify identity.
 */
fun spotifyShareUrl(spotifyUri: String?): String? = when {
    spotifyUri.isNullOrBlank() -> null
    spotifyUri.startsWith("https://open.spotify.com/") -> spotifyUri
    spotifyUri.startsWith("spotify:track:") ->
        "https://open.spotify.com/track/${spotifyUri.removePrefix("spotify:track:")}"
    else -> null
}

/** YouTube video id → a YouTube Music watch link. Null when absent. */
fun youtubeShareUrl(youtubeId: String?): String? =
    youtubeId?.takeIf { it.isNotBlank() }?.let { "https://music.youtube.com/watch?v=$it" }
