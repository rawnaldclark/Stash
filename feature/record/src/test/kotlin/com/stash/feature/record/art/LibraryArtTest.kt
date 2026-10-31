package com.stash.feature.record.art

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.LibraryArtDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** One record opened: its index read once, each cover made once, and a database that fails leaves the lookups alone. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryArtTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val decodes = AtomicInteger()
    private lateinit var files: LibraryArtFiles
    private lateinit var cover: File

    private class Dao(val tracks: List<LibraryArtDao.TrackArt>, val fail: Boolean = false) : LibraryArtDao {
        val reads = AtomicInteger()
        override suspend fun trackArt(): List<LibraryArtDao.TrackArt> {
            reads.incrementAndGet()
            delay(50) // long enough for a second ask to arrive while the first reads
            if (fail) error("database is locked")
            return tracks
        }

        override suspend fun scannedArt(): List<LibraryArtDao.ScannedArt> = emptyList()
    }

    @Before fun setUp() {
        File(context.cacheDir, LibraryArtFiles.DIR).deleteRecursively()
        files = LibraryArtFiles(
            File(context.cacheDir, LibraryArtFiles.DIR),
            LibraryArtFiles.coverFolders(context.filesDir, context.cacheDir),
            { m, px -> decodes.incrementAndGet(); testDecoder.decode(m, px) },
            Dispatchers.Unconfined,
        )
        cover = File(context.filesDir, "cloud_art/blonde.jpg").apply { parentFile!!.mkdirs(); writeBytes(jpegWithExif(640, 640, "x")) }
    }

    private fun track(title: String, path: String?) = LibraryArtDao.TrackArt(title, "Frank Ocean", "", "Blonde", path, null)

    private fun art(dao: LibraryArtDao) = LibraryArt(dao, files, File(context.filesDir, "scanned_art"), Dispatchers.Default)

    @Test fun `asks that arrive together read the library once`() = runTest {
        val dao = Dao(listOf(track("Ivy", cover.path)))
        val art = art(dao)
        val songs = listOf(ArtSong("Ivy", "Frank Ocean"))
        withContext(Dispatchers.Default) {
            (1..5).map { async { art.owned(songs) } }.awaitAll() + listOf(art.local(songs).map { it != null })
        }.forEach { assertThat(it).containsExactly(true) }
        assertThat(dao.reads.get()).isEqualTo(1)
    }

    @Test fun `an album's songs share one cover, made once`() = runTest {
        val art = art(Dao(listOf(track("Ivy", cover.path), track("Nights", cover.path))))
        val urls = withContext(Dispatchers.Default) {
            art.local(listOf(ArtSong("Ivy", "Frank Ocean", "Blonde"), ArtSong("Nights", "Frank Ocean", "Blonde"), ArtSong("Solo", "Frank Ocean")))
        }
        assertThat(urls[0]).isNotNull()
        assertThat(urls[1]).isEqualTo(urls[0])
        assertThat(urls[2]).isNull()
        assertThat(decodes.get()).isEqualTo(1)
    }

    @Test fun `a library that can't be read has no covers, and says so without failing the record`() = runTest {
        val art = art(Dao(listOf(track("Ivy", cover.path)), fail = true))
        val songs = listOf(ArtSong("Ivy", "Frank Ocean", "Blonde"))
        withContext(Dispatchers.Default) {
            assertThat(art.owned(songs)).containsExactly(false)
            assertThat(art.local(songs)).containsExactly(null)
            assertThat(art.remote(songs)).containsExactly(null)
        }
        assertThat(decodes.get()).isEqualTo(0)
    }
}
