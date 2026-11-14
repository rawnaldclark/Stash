package com.stash.data.download.clips

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.stash.core.data.audio.FlacAudioEnd
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Reads a downloaded file's FLAC lengths ([FlacAudioEnd]); a seam, so a test can count or stop the reads. */
fun interface FlacLengthsReader {
    /** The lengths of the file at [path]; null when it can't be read, is not a FLAC, or its end can't be settled. */
    fun read(path: String): FlacAudioEnd.Lengths?
}

/**
 * [FlacLengthsReader] for a track's `file_path`: a plain path, a `file://` URI, or a `content://`
 * document in the folder the person picked for downloads (read through its file descriptor, so
 * the same tail read works there).
 *
 * Never throws: a file that is gone (a row that says downloaded with no file behind it, as "Inner
 * City Blues" on the Pixel 5 rig), a folder whose access was taken back, or a provider that can't
 * seek all read as null, which the check passes over.
 */
@Singleton
class FlacTailReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : FlacLengthsReader {

    override fun read(path: String): FlacAudioEnd.Lengths? = try {
        when {
            path.startsWith("content://") ->
                context.contentResolver.openFileDescriptor(Uri.parse(path), "r")
                    // The stream owns the descriptor: closing it closes both.
                    ?.let(ParcelFileDescriptor::AutoCloseInputStream)
                    ?.use { FlacAudioEnd.read(it.channel) }
            path.startsWith("file://") -> Uri.parse(path).path?.let { FlacAudioEnd.read(File(it)) }
            else -> FlacAudioEnd.read(File(path))
        }
    } catch (e: Exception) {
        // FileNotFoundException, SecurityException (access taken back), IllegalArgumentException (a bad URI).
        null
    }
}
