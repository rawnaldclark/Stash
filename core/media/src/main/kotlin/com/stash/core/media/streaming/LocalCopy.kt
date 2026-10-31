package com.stash.core.media.streaming

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.stash.core.common.constants.StashConstants
import com.stash.core.data.db.entity.TrackEntity
import java.io.File

/**
 * True when [path] (a plain path, a `file://` URI or a SAF `content://` URI) holds
 * at least [StashConstants.MIN_PLAYABLE_LOCAL_BYTES]: a failed download can leave a
 * few hundred bytes of error body behind under a row marked downloaded.
 */
internal fun isUsableLocalFile(context: Context, path: String): Boolean {
    val bytes = try {
        if (path.startsWith("content://")) {
            DocumentFile.fromSingleUri(context, path.toUri())?.length() ?: 0L
        } else {
            File(if (path.startsWith("file://")) path.toUri().path ?: path.removePrefix("file://") else path).length()
        }
    } catch (e: Exception) {
        0L
    }
    return bytes >= StashConstants.MIN_PLAYABLE_LOCAL_BYTES
}

/** [path] as the URI the player opens: a plain path gets `file://`, a URI stays as it is. */
internal fun localPlaybackUri(path: String): Uri = if (path.startsWith("/")) "file://$path".toUri() else path.toUri()

/** The row's own downloaded file, when it has one worth playing; null otherwise. */
internal fun TrackEntity.usableDownload(context: Context): Uri? =
    filePath?.takeIf { isDownloaded && it.isNotBlank() && isUsableLocalFile(context, it) }?.let(::localPlaybackUri)
