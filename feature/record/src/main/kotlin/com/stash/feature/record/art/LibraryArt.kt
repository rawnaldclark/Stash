package com.stash.feature.record.art

import android.os.SystemClock
import android.util.Log
import com.stash.core.data.db.dao.LibraryArtDao
import com.stash.feature.record.bridge.RecordLibraryArt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * The record's library covers for one record opened ([RecordLibraryArt]): its [LibraryArtIndex] is read once, off the main
 * thread, on the page's first ask, and each cover found is made a file the page can show ([LibraryArtFiles]).
 */
internal class LibraryArt(
    private val dao: LibraryArtDao,
    private val files: LibraryArtFiles,
    /** Where scanned songs' covers are (`filesDir/scanned_art`, feature:library's ScannedCoverStore). */
    private val scannedDir: File,
    private val work: CoroutineContext = Dispatchers.IO,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : RecordLibraryArt {
    private val building = Mutex()
    private var index: LibraryArtIndex? = null

    private suspend fun index(): LibraryArtIndex = building.withLock {
        index ?: build().also { index = it }
    }

    private suspend fun build(): LibraryArtIndex = try {
        val start = clock()
        val tracks = dao.trackArt()
        val scanned = dao.scannedArt()
        LibraryArtIndex.build(tracks, scanned) { name -> File(scannedDir, name).path }.also {
            Log.i(TAG, "library art: ${tracks.size} tracks and ${scanned.size} scanned songs, ${it.localKeys} own covers and ${it.remoteKeys} addresses by key, in ${clock() - start} ms")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The lookups carry on alone
        Log.w(TAG, "the library's covers couldn't be read: ${e.javaClass.simpleName}")
        LibraryArtIndex.EMPTY
    }

    override suspend fun local(songs: List<ArtSong>): List<String?> = withContext(work) {
        val ix = index()
        resolve(songs.map(ix::localFor), files::forFile)
    }

    override suspend fun remote(songs: List<ArtSong>): List<String?> = withContext(work) {
        val ix = index()
        resolve(songs.map(ix::remoteFor), files::forUrl)
    }

    /** Each source made once however many songs share it (an album's songs), a few at a time ([LibraryArtFiles.MAKE_AT_ONCE]). */
    private suspend fun resolve(sources: List<String?>, give: suspend (String) -> String?): List<String?> = coroutineScope {
        val once = HashMap<String, Deferred<String?>>()
        val asks = sources.map { s -> s?.let { once.getOrPut(it) { async { give(it) } } } }
        once.values.awaitAll()
        asks.map { it?.await() }
    }

    private companion object {
        const val TAG = "RecordArt"
    }
}
