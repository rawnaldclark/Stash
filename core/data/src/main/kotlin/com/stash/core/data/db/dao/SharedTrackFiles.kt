package com.stash.core.data.db.dao

import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * Whether a track other than [trackId] still records [path] as its file.
 * Every place that deletes a track's file asks this first: two rows can
 * share one file (before imports picked a file name of their own, an import
 * named like a downloaded song took that song's file), and deleting the file
 * for one row would leave the other on nothing. The file then goes with the
 * last row that records it.
 *
 * A failed check answers true, so the file is kept: a leftover file is
 * harmless, another song's lost audio is not.
 */
suspend fun TrackDao.fileUsedByAnotherTrack(path: String, trackId: Long): Boolean {
    val others = try {
        countOtherTracksWithFilePath(path, trackId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "couldn't check whether another song plays from $path; keeping it", e)
        return true
    }
    if (others > 0) Log.i(TAG, "kept $path: $others other song(s) still play from it")
    return others > 0
}

private const val TAG = "SharedTrackFiles"
