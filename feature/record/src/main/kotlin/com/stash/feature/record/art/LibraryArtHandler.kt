package com.stash.feature.record.art

import android.util.Log
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.FileInputStream
import java.io.IOException

/**
 * `/art/` on the record's origin: a library cover by the name [LibraryArtFiles] gave the page (`v1/<32 hex>.jpg`), from its
 * own folder only. Any other path, or a name never given, is null, so the WebView finds nothing (a 404). The page may keep a
 * cover for a year: its name changes whenever the cover does.
 */
internal class LibraryArtHandler(private val files: LibraryArtFiles) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        val file = files.open(path) ?: return null
        return try {
            WebResourceResponse(MIME, null, 200, "OK", HEADERS, FileInputStream(file))
        } catch (e: IOException) {
            Log.w(TAG, "a library cover couldn't be read")
            null
        }
    }

    companion object {
        private const val TAG = "RecordArt"
        const val MIME = "image/jpeg"
        val HEADERS: Map<String, String> = mapOf("Cache-Control" to "private, max-age=31536000, immutable")
    }
}
