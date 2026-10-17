package com.stash.feature.library.allsongs

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.stash.core.model.Track
import com.stash.core.ui.components.fastScrollSectionLetter

/** Most files one share carries: every uri rides in the intent, which the system caps at about 1 MB. */
internal const val MAX_SHARE = 500

/** The scrollbar bubble's letter for the row at [index]: the first letter of that song's title. */
internal fun titleSectionLabel(songs: List<Track>): (Int) -> String? = { index ->
    songs.getOrNull(index)?.let { fastScrollSectionLetter(it.title) }
}

/**
 * The system share sheet with the song files themselves (a read grant rides along for the
 * receiver). False when there was nothing to share or no share sheet opened.
 */
internal fun shareSongs(context: Context, tracks: List<Track>): Boolean {
    val uris = tracks.mapNotNull { it.filePath?.let(Uri::parse) }
    if (uris.isEmpty()) return false
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    intent.type = "audio/*"
    intent.clipData = ClipData.newRawUri(null, uris[0]).also { clip ->
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return runCatching { context.startActivity(Intent.createChooser(intent, "Share")) }.isSuccess
}