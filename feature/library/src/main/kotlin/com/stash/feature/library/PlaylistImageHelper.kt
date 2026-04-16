package com.stash.feature.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistImageHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Saves the picked image as a 512x512 JPEG and returns a file:// URI
     * with a cache-busting query param for Coil.
     */
    fun savePlaylistCoverImage(playlistId: Long, imageUri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(imageUri) ?: return null
            val original = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            // Center-crop to square
            val size = minOf(original.width, original.height)
            val x = (original.width - size) / 2
            val y = (original.height - size) / 2
            val cropped = Bitmap.createBitmap(original, x, y, size, size)

            // Scale to 512x512
            val scaled = Bitmap.createScaledBitmap(cropped, 512, 512, true)
            if (cropped !== original) original.recycle()
            if (scaled !== cropped) cropped.recycle()

            // Save to app-internal storage
            val dir = java.io.File(context.filesDir, "playlist_covers")
            dir.mkdirs()
            val file = java.io.File(dir, "$playlistId.jpg")
            file.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            scaled.recycle()

            // Return file:// URI with cache-busting timestamp
            "file://${file.absolutePath}?v=${System.currentTimeMillis()}"
        } catch (e: Exception) {
            null
        }
    }

    /** Deletes the cover image file for a playlist. */
    fun deletePlaylistCoverFile(playlistId: Long) {
        val file = java.io.File(context.filesDir, "playlist_covers/$playlistId.jpg")
        file.delete()
    }
}
