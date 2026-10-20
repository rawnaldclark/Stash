package com.stash.core.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import com.stash.core.model.isScannedSong

/** What a "Save to Google Drive" tap asks to save. */
sealed interface CloudSaveTarget {
    /** One song, or a selection. */
    data class Songs(val trackIds: List<Long>) : CloudSaveTarget

    /** Every song of an album in the library (the downloaded ones go). */
    data class Album(val name: String, val artist: String) : CloudSaveTarget

    /** Every song of a playlist (the downloaded ones go). */
    data class Playlist(val playlistId: Long) : CloudSaveTarget
}

/**
 * "Save to Google Drive" for the song, album and playlist menus. The app provides it only while a
 * Google Drive account is connected (and the build has Google Drive), so every entry hides while
 * it's null, like [LocalPostToCommunity]. [save] queues the upload and says what happened.
 */
val LocalSaveToCloud = compositionLocalOf<((CloudSaveTarget) -> Unit)?> { null }

/** The menu label, the same everywhere. */
const val SAVE_TO_CLOUD_LABEL = "Save to Google Drive"

/**
 * True when [track] can be saved from its menu: a download Stash made, not a cloud song (already
 * in the user's cloud) and not an All Songs file (Stash never copies those).
 */
fun canSaveToCloud(track: Track): Boolean =
    track.isDownloaded && track.filePath != null && track.source != MusicSource.CLOUD && !track.isScannedSong

/**
 * The "Save to Google Drive" row for a song's menu; nothing when Drive isn't connected or the song
 * can't be saved. [onDismiss] closes the sheet first.
 */
@Composable
fun SaveToCloudOption(track: Track, onDismiss: () -> Unit) {
    val save = LocalSaveToCloud.current ?: return
    if (!canSaveToCloud(track)) return
    SheetOptionRow(
        icon = Icons.Default.CloudUpload,
        label = SAVE_TO_CLOUD_LABEL,
        onClick = { onDismiss(); save(CloudSaveTarget.Songs(listOf(track.id))) },
    )
}
