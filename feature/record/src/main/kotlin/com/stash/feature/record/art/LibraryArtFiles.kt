package com.stash.feature.record.art

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Scale
import coil3.toBitmap
import com.stash.core.ui.util.ArtSizeInterceptor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext

/** Decodes a cover ([model]: a file's path, or a remote address from the image cache alone) to about [px] a side; null when it can't. */
internal fun interface ArtDecoder {
    suspend fun decode(model: String, px: Int): Bitmap?
}

/**
 * Coil, as the Library's rows load the same covers: a file's path, or an address read from Coil's disk cache only (never the
 * network: the record asks nothing of the library's hosts). Its pixels in memory, at least [px] on the short side.
 */
internal class CoilArtDecoder(private val context: Context) : ArtDecoder {
    override suspend fun decode(model: String, px: Int): Bitmap? {
        val request = ImageRequest.Builder(context)
            // A file as a File (Coil's file fetcher), an address as itself (its disk cache, keyed as the rows key it)
            .data(if (model.contains("://")) model else File(model))
            .size(px, px)
            .scale(Scale.FILL)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .networkCachePolicy(CachePolicy.DISABLED)
            .apply { extras[ArtSizeInterceptor.SkipSizing] = true }
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        return (result as? SuccessResult)?.image?.toBitmap()
    }
}

/**
 * The record's own-art files (phase 6, PHASE6.md §3-5): each library cover the record shows is made into
 * `cacheDir/record-library-art/<hash>.jpg` ([LibraryArtEncode]: decoded, the middle square, 600 a side, JPEG) and named to the
 * page only by `https://appassets.androidplatform.net/art/v1/<hash>.jpg` ([LibraryArtHandler] serves it).
 *
 * - The name is 32 hex of sha256: of a file's canonical path, length and modification time (a cover changed is a new name, so
 *   the page's year-long cache never shows an old one), or of a remote address.
 * - A name is given only once its file exists: the page's covers have no fallback, so it never meets a 404.
 * - What each name was made from is kept in memory ([sources]): only a name given in this process is served, and a file the
 *   folder's trim (or the system) removed is made again when the page asks for it.
 * - Only files in the library's own cover folders ([roots]: `cacheDir/albumart` for local imports and downloads,
 *   `filesDir/scanned_art`, `filesDir/cloud_art`) are read, after their path is made canonical: never external storage, a
 *   `content://` address or a link out; and none over [MAX_SOURCE_BYTES]. Every file is decoded (at most [DECODE_TIMEOUT_MS])
 *   and encoded again, so even a wrong path could only ever give a picture.
 * - The folder is trimmed when the record opens and after every [TRIM_EVERY] covers made.
 */
@Singleton
class LibraryArtFiles internal constructor(
    internal val dir: File,
    roots: List<File>,
    private val decoder: ArtDecoder,
    private val io: CoroutineContext = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val decodeTimeoutMs: Long = DECODE_TIMEOUT_MS,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        dir = File(context.cacheDir, DIR),
        roots = coverFolders(filesDir = context.filesDir, cacheDir = context.cacheDir),
        decoder = CoilArtDecoder(context),
    )

    private val roots: List<String> = roots.map { it.canonicalPath.trimEnd(File.separatorChar) + File.separator }

    /** What a name was made from: a file's canonical path, or a remote address. */
    private sealed interface Source {
        val model: String

        data class Local(override val model: String) : Source
        data class Remote(override val model: String) : Source
    }

    private val sources = ConcurrentHashMap<String, Source>()

    /** Covers made at once, at most: a page asks 150 at a time, each a decode of a whole picture. */
    private val making = Semaphore(MAKE_AT_ONCE)

    /** Covers made in this process: the folder is trimmed after every [TRIM_EVERY]. */
    private val writes = AtomicInteger()

    /** The record's address for the cover in the file at [path] (made first when needed), or null: not ours to read, or not a picture. */
    suspend fun forFile(path: String): String? = withContext(io) {
        val file = readable(path) ?: return@withContext null
        val length = file.length()
        if (length > MAX_SOURCE_BYTES) return@withContext null
        val modified = file.lastModified()
        give(hash("file\u0000${file.path}\u0000$length\u0000$modified"), Source.Local(file.path))
    }

    /** The record's address for the cover at [url] as the image cache has it (never fetched), or null. */
    suspend fun forUrl(url: String): String? = withContext(io) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return@withContext null
        give(hash("url\u0000$url"), Source.Remote(url))
    }

    private suspend fun give(hash: String, source: Source): String? {
        val out = fileOf(hash)
        if (!out.isFile || out.length() == 0L) {
            if (!make(source, out)) return null
        }
        sources[hash] = source
        return URL_PREFIX + hash + ".jpg"
    }

    private suspend fun make(source: Source, out: File): Boolean = making.withPermit {
        try {
            // A picture that takes longer than this (a huge or hostile file) is no cover
            val bitmap = withTimeoutOrNull(decodeTimeoutMs) { decoder.decode(source.model, LibraryArtEncode.PX) }
                ?: return@withPermit false
            LibraryArtEncode.write(bitmap, out).also { ok ->
                if (ok && writes.incrementAndGet() % TRIM_EVERY == 0) trim()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "a library cover couldn't be made: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * The file for the page's [path] under `/art/` (`v1/<32 hex>.jpg`, nothing else), made again from what it was made from
     * when it has gone; null for any other path or a name not given in this process (even one whose file is still in the
     * folder). Called on the WebView's own thread, which may wait.
     */
    fun open(path: String): File? {
        val hash = NAME.matchEntire(path)?.groupValues?.get(1) ?: return null
        val source = sources[hash] ?: return null
        val file = fileOf(hash)
        if (!file.isFile || file.length() == 0L) {
            if (!runBlocking(io) { make(source, file) }) return null
        }
        // Kept as the most recent for the trim
        file.setLastModified(now())
        return file
    }

    /** Leaves the folder at [maxBytes] or less, the covers used least recently going first (and anything half-written). */
    fun trim(maxBytes: Long = MAX_BYTES) {
        try {
            trimNow(maxBytes)
        } catch (e: Exception) {
            Log.w(TAG, "the library covers' folder couldn't be trimmed: ${e.javaClass.simpleName}")
        }
    }

    private fun trimNow(maxBytes: Long) {
        val all = dir.listFiles() ?: return
        var total = 0L
        val stale = now() - TMP_AGE_MS
        val covers = all.filter { f ->
            if (f.name.endsWith(LibraryArtEncode.TMP)) {
                if (f.lastModified() < stale) f.delete()
                false
            } else {
                f.isFile
            }
        }
        for (f in covers.sortedByDescending { it.lastModified() }) {
            total += f.length()
            if (total > maxBytes) f.delete()
        }
    }

    /** [path] made canonical, when it is a file under one of the app's own folders; else null. */
    internal fun readable(path: String): File? {
        if (!File(path).isAbsolute || path.contains("://") || path.contains('\u0000')) return null
        val file = try {
            File(path).canonicalFile
        } catch (e: IOException) {
            return null
        }
        if (roots.none { file.path.startsWith(it) }) return null
        return file.takeIf { it.isFile }
    }

    private fun fileOf(hash: String) = File(dir, "$hash.jpg")

    companion object {
        private const val TAG = "RecordArt"

        /** In `cacheDir`. */
        const val DIR = "record-library-art"

        /** The page's address of a cover, before its name. */
        const val URL_PREFIX = "https://appassets.androidplatform.net/art/v1/"

        /** What the handler takes (the path after `/art/`). */
        val NAME = Regex("^v1/([0-9a-f]{32})\\.jpg$")

        /** The folder's size after a trim, at most. */
        const val MAX_BYTES = 40L * 1024 * 1024

        const val MAKE_AT_ONCE = 3

        /** A decode taking longer than this gives no cover. */
        const val DECODE_TIMEOUT_MS = 3_000L

        /** A source file bigger than this is never decoded (a cover is a few hundred KB; a 20 MB one is not art). */
        const val MAX_SOURCE_BYTES = 20L * 1024 * 1024

        /** The folder is trimmed after this many covers made, as well as on each open. */
        const val TRIM_EVERY = 50

        /** A half-written file older than this is left over from a process that died. */
        const val TMP_AGE_MS = 60_000L

        /** The library's cover folders: the only places a cover is read from. */
        internal fun coverFolders(filesDir: File, cacheDir: File): List<File> = listOf(
            // Local imports' embedded art (LocalImportCoordinator) and downloads' covers (FileOrganizer.getAlbumArtDir)
            File(cacheDir, "albumart"),
            // Scanned songs' embedded art (feature:library ScannedCoverStore.DIR_NAME)
            File(filesDir, "scanned_art"),
            // Cloud songs' embedded art (data:cloud CloudArtStore.DIR)
            File(filesDir, "cloud_art"),
        )

        internal fun hash(identity: String): String =
            MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }.substring(0, 32)
    }
}
