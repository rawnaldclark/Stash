package com.stash.data.download.files

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.LibraryLayout
import com.stash.core.data.prefs.StoragePreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Importing a file from the device makes a NEW track, so it must never take
 * the place of a library file that is already there or of a path another
 * track records. Found on a Pixel 6: sharing "Roberta Flack - Killing Me
 * Softly With His Song" wrote over the downloaded copy at the same name, and
 * both rows pointed at the one file, so deleting either broke the other.
 *
 * Runs the real [LocalImportCoordinator] over the real [FileOrganizer], once
 * per storage mode: internal storage (plain files) and a SAF tree (an
 * in-memory one whose document URIs follow ExternalStorageProvider's
 * path-based shape, as the real one's do).
 */
class LocalImportCoordinatorTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context = mockk<Context>(relaxed = true)
    private val resolver = mockk<ContentResolver>(relaxed = true)
    private val storagePreference = mockk<StoragePreference>()
    private val trackDao = mockk<TrackDao>(relaxed = true)
    private val musicRepository = mockk<MusicRepository>(relaxed = true)
    private val inserted = slot<Track>()
    private val source = mockk<Uri>()
    private lateinit var musicDir: File
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        val filesDir = tmp.newFolder("files")
        musicDir = File(filesDir, "music")
        every { context.filesDir } returns filesDir
        cacheDir = tmp.newFolder("cache")
        every { context.cacheDir } returns cacheDir
        every { context.contentResolver } returns resolver
        every { context.packageName } returns "com.stash.app"
        sharedFromAnotherApp(source, id = 1)
        every { resolver.getType(source) } returns null
        every { resolver.openInputStream(source) } answers { ByteArrayInputStream(IMPORTED) }
        every { storagePreference.libraryLayout } returns flowOf(LibraryLayout.ARTIST_ALBUM)
        coEvery { trackDao.countOtherTracksWithFilePath(any(), any()) } returns 0
        coEvery { musicRepository.insertTrack(capture(inserted)) } returns 2L

        // No tags (the retriever is a stub here), so the import names the
        // track from the shared file's name, as it does for an untagged file.
        mockkStatic(DocumentFile::class)
        val shared = mockk<DocumentFile> { every { name } returns "Roberta Flack - Killing Me Softly With His Song.flac" }
        every { DocumentFile.fromSingleUri(any(), source) } returns shared
    }

    @After
    fun tearDown() {
        unmockkStatic(DocumentFile::class)
    }

    // ── Internal storage ────────────────────────────────────────────────

    private val canonicalFile: File
        get() = File(musicDir, "roberta-flack/singles/killing-me-softly-with-his-song.flac")

    @Test
    fun `an import never writes over a downloaded file at its name`() {
        every { storagePreference.externalTreeUri } returns flowOf(null)
        canonicalFile.parentFile!!.mkdirs()
        canonicalFile.writeBytes(DOWNLOADED)
        coEvery { trackDao.countOtherTracksWithFilePath(canonicalFile.absolutePath, any()) } returns 1

        importOne()

        assertThat(canonicalFile.readBytes()).isEqualTo(DOWNLOADED)
        val importedPath = inserted.captured.filePath!!
        assertThat(importedPath).isNotEqualTo(canonicalFile.absolutePath)
        assertThat(importedPath).isEqualTo(File(canonicalFile.parentFile, "killing-me-softly-with-his-song-2.flac").absolutePath)
        assertThat(File(importedPath).readBytes()).isEqualTo(IMPORTED)
    }

    /** A file nobody records (a leftover) is still never written over. */
    @Test
    fun `an import never writes over a file no track records`() {
        every { storagePreference.externalTreeUri } returns flowOf(null)
        canonicalFile.parentFile!!.mkdirs()
        canonicalFile.writeBytes(DOWNLOADED)

        importOne()

        assertThat(canonicalFile.readBytes()).isEqualTo(DOWNLOADED)
        assertThat(inserted.captured.filePath).endsWith("killing-me-softly-with-his-song-2.flac")
    }

    /** A track whose file has gone missing still owns its path. */
    @Test
    fun `an import never records a path another track records`() {
        every { storagePreference.externalTreeUri } returns flowOf(null)
        coEvery { trackDao.countOtherTracksWithFilePath(canonicalFile.absolutePath, any()) } returns 1

        importOne()

        assertThat(canonicalFile.exists()).isFalse()
        assertThat(inserted.captured.filePath).isEqualTo(
            File(canonicalFile.parentFile, "killing-me-softly-with-his-song-2.flac").absolutePath,
        )
    }

    @Test
    fun `an import takes the next free number`() {
        every { storagePreference.externalTreeUri } returns flowOf(null)
        canonicalFile.parentFile!!.mkdirs()
        canonicalFile.writeBytes(DOWNLOADED)
        val second = File(canonicalFile.parentFile, "killing-me-softly-with-his-song-2.flac").apply { writeBytes(DOWNLOADED) }

        importOne()

        assertThat(canonicalFile.readBytes()).isEqualTo(DOWNLOADED)
        assertThat(second.readBytes()).isEqualTo(DOWNLOADED)
        assertThat(inserted.captured.filePath).endsWith("killing-me-softly-with-his-song-3.flac")
    }

    @Test
    fun `an import with a free name keeps the plain name`() {
        every { storagePreference.externalTreeUri } returns flowOf(null)

        importOne()

        assertThat(inserted.captured.filePath).isEqualTo(canonicalFile.absolutePath)
        assertThat(canonicalFile.readBytes()).isEqualTo(IMPORTED)
    }

    // ── SAF tree ────────────────────────────────────────────────────────

    private val canonicalDoc = "roberta-flack/singles/killing-me-softly-with-his-song.flac"
    private val secondDoc = "roberta-flack/singles/killing-me-softly-with-his-song-2.flac"

    @Test
    fun `a SAF import never writes over a downloaded document at its name`() {
        val tree = FakeSafTree()
        tree.seed(canonicalDoc, DOWNLOADED)
        coEvery { trackDao.countOtherTracksWithFilePath(tree.uriOf(canonicalDoc), any()) } returns 1

        importOne()

        assertThat(tree.deleted).doesNotContain(tree.uriOf(canonicalDoc))
        assertThat(tree.exists(canonicalDoc)).isTrue()
        assertThat(tree.written[tree.uriOf(canonicalDoc)]).isEqualTo(DOWNLOADED)
        assertThat(inserted.captured.filePath).isEqualTo(tree.uriOf(secondDoc))
        assertThat(tree.written[tree.uriOf(secondDoc)]).isEqualTo(IMPORTED)
    }

    /**
     * The document is gone but a track still records its URI. A new document
     * with that name gets the same URI back, so the import moves on, and the
     * empty document it made to find that out is removed.
     */
    @Test
    fun `a SAF import never records a URI another track records`() {
        val tree = FakeSafTree()
        tree.seedDir("roberta-flack/singles")
        coEvery { trackDao.countOtherTracksWithFilePath(tree.uriOf(canonicalDoc), any()) } returns 1

        importOne()

        assertThat(inserted.captured.filePath).isEqualTo(tree.uriOf(secondDoc))
        assertThat(tree.written[tree.uriOf(secondDoc)]).isEqualTo(IMPORTED)
        assertThat(tree.exists(canonicalDoc)).isFalse()
    }

    @Test
    fun `a SAF import with a free name keeps the plain name`() {
        val tree = FakeSafTree()

        importOne()

        assertThat(inserted.captured.filePath).isEqualTo(tree.uriOf(canonicalDoc))
        assertThat(tree.written[tree.uriOf(canonicalDoc)]).isEqualTo(IMPORTED)
    }

    // ── Cancel ──────────────────────────────────────────────────────────

    /** A second file in the batch, which a cancelled batch must never reach. */
    private val second = mockk<Uri>()

    private fun stubSecondFile() {
        sharedFromAnotherApp(second, id = 2)
        every { resolver.getType(second) } returns null
        every { resolver.openInputStream(second) } answers { ByteArrayInputStream(IMPORTED) }
        val shared = mockk<DocumentFile> { every { name } returns "Evanescence - Lacrymosa.flac" }
        every { DocumentFile.fromSingleUri(any(), second) } returns shared
    }

    /**
     * A cancel while the file is still being copied in stops the batch: that
     * file never reaches the library, its temp copy is removed, the next file
     * is never opened, and the batch does not end on Done.
     */
    @Test
    fun `a cancel during the copy stops the batch and leaves nothing behind`() = runBlocking {
        every { storagePreference.externalTreeUri } returns flowOf(null)
        stubSecondFile()
        val reading = CountDownLatch(1)
        val gate = CountDownLatch(1)
        every { resolver.openInputStream(source) } answers { gatedStream(reading, gate) }
        val coordinator = coordinator()

        coordinator.start(listOf(source, second))
        assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue()
        coordinator.cancel()
        gate.countDown()

        assertThat(withTimeoutOrNull(1_500) { coordinator.state.first { it is LocalImportState.Done } }).isNull()
        assertThat(coordinator.state.value).isEqualTo(LocalImportState.Idle)
        verify(exactly = 0) { resolver.openInputStream(second) }
        coVerify(exactly = 0) { musicRepository.insertTrack(any()) }
        assertThat(tempFiles()).isEmpty()
        assertThat(libraryFiles()).isEmpty()
    }

    /**
     * A cancel while a file is being saved lets that file finish, so the
     * library never keeps a file no song records, then stops the batch.
     */
    @Test
    fun `a cancel while a file is saved finishes that file, then stops`() = runBlocking {
        every { storagePreference.externalTreeUri } returns flowOf(null)
        stubSecondFile()
        val saving = CountDownLatch(1)
        val saved = AtomicBoolean(false)
        coEvery { musicRepository.insertTrack(capture(inserted)) } coAnswers {
            saving.countDown()
            delay(500) // the cancel lands here
            saved.set(true)
            2L
        }
        val coordinator = coordinator()

        coordinator.start(listOf(source, second))
        assertThat(saving.await(5, TimeUnit.SECONDS)).isTrue()
        coordinator.cancel()

        assertThat(withTimeoutOrNull(2_000) { coordinator.state.first { it is LocalImportState.Done } }).isNull()
        assertThat(coordinator.state.value).isEqualTo(LocalImportState.Idle)
        assertThat(saved.get()).isTrue()
        assertThat(libraryFiles()).containsExactly(File(inserted.captured.filePath!!))
        verify(exactly = 0) { resolver.openInputStream(second) }
        assertThat(tempFiles()).isEmpty()
    }

    /** Streams [IMPORTED] once [gate] opens, saying when the copy has started. */
    private fun gatedStream(reading: CountDownLatch, gate: CountDownLatch) = object : InputStream() {
        private var sent = false

        override fun read(): Int = throw UnsupportedOperationException("read in blocks")

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (sent) return -1
            reading.countDown()
            gate.await(5, TimeUnit.SECONDS)
            sent = true
            IMPORTED.copyInto(b, off)
            return IMPORTED.size
        }
    }

    private fun tempFiles(): List<File> =
        File(cacheDir, "downloads").listFiles()?.filter { it.name.startsWith("import_") }.orEmpty()

    private fun libraryFiles(): List<File> = musicDir.walkTopDown().filter { it.isFile }.toList()

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Makes [uri] a content:// document of another app's provider, as the picker hands over. */
    private fun sharedFromAnotherApp(uri: Uri, id: Int) {
        every { uri.scheme } returns "content"
        every { uri.authority } returns "com.android.providers.media.documents"
        every { uri.toString() } returns "content://com.android.providers.media.documents/document/audio%3A$id"
    }

    private fun coordinator() =
        LocalImportCoordinator(context, FileOrganizer(context, storagePreference, trackDao), musicRepository)

    private fun importOne() = runBlocking {
        val coordinator = coordinator()
        coordinator.start(listOf(source))
        val done = withTimeout(10_000) {
            coordinator.state.first { it is LocalImportState.Done || it is LocalImportState.Error }
        }
        assertThat(done).isEqualTo(LocalImportState.Done(imported = 1, failed = 0))
    }

    /**
     * A SAF tree in memory. Documents are mocks over maps; a document's URI is
     * derived from its path, so a document made again at a deleted one's name
     * gets the same URI, as on ExternalStorageProvider. Creating a name that
     * is already taken gets " (1)" added, as the real providers do.
     */
    private inner class FakeSafTree {
        val written = mutableMapOf<String, ByteArray>()
        val deleted = mutableListOf<String>()
        private val dirs = mutableMapOf<String, MutableMap<String, DocumentFile>>()
        private val root = dir("")

        init {
            val treeUri = mockk<Uri>()
            every { storagePreference.externalTreeUri } returns flowOf(treeUri)
            every { DocumentFile.fromTreeUri(any(), treeUri) } returns root
            every { resolver.openOutputStream(any<Uri>()) } answers {
                val uri = firstArg<Uri>().toString()
                object : ByteArrayOutputStream() {
                    override fun close() {
                        super.close()
                        written[uri] = toByteArray()
                    }
                }
            }
        }

        fun uriOf(path: String): String =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2F" +
                path.replace("/", "%2F")

        fun exists(path: String): Boolean =
            dirs[path.substringBeforeLast('/')]?.containsKey(path.substringAfterLast('/')) == true

        fun seedDir(path: String): DocumentFile =
            path.split('/').fold(root) { cursor, segment -> cursor.findFile(segment) ?: cursor.createDirectory(segment)!! }

        fun seed(path: String, bytes: ByteArray) {
            seedDir(path.substringBeforeLast('/')).createFile("audio/flac", path.substringAfterLast('/'))
            written[uriOf(path)] = bytes
        }

        private fun uri(path: String): Uri {
            val text = uriOf(path)
            return mockk { every { this@mockk.toString() } returns text }
        }

        private fun dir(path: String): DocumentFile {
            val children = linkedMapOf<String, DocumentFile>()
            dirs[path] = children
            val doc = mockk<DocumentFile>()
            every { doc.isDirectory } returns true
            every { doc.isFile } returns false
            every { doc.name } returns path.substringAfterLast('/')
            every { doc.uri } returns uri(path)
            every { doc.findFile(any()) } answers { children[firstArg()] }
            every { doc.listFiles() } answers { children.values.toTypedArray() }
            every { doc.createDirectory(any()) } answers {
                val name = firstArg<String>()
                dir(join(path, name)).also { children[name] = it }
            }
            every { doc.createFile(any(), any()) } answers {
                var name = secondArg<String>()
                if (name in children) name = "${name.substringBeforeLast('.')} (1).${name.substringAfterLast('.')}"
                file(join(path, name), children).also { children[name] = it }
            }
            return doc
        }

        private fun file(path: String, siblings: MutableMap<String, DocumentFile>): DocumentFile {
            val name = path.substringAfterLast('/')
            val text = uriOf(path)
            val doc = mockk<DocumentFile>()
            every { doc.isDirectory } returns false
            every { doc.isFile } returns true
            every { doc.name } returns name
            every { doc.uri } returns uri(path)
            every { doc.delete() } answers {
                siblings.remove(name)
                written.remove(text)
                deleted += text
                true
            }
            return doc
        }

        private fun join(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"
    }

    private companion object {
        val DOWNLOADED = "downloaded flac".toByteArray()
        val IMPORTED = "imported audio".toByteArray()
    }
}
