package com.stash.data.download.files

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.stash.core.data.repository.MusicRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The saved extension of an imported file is one of the audio types Stash
 * files: taken from the provider's MIME type or display name only when it is
 * one of them, else from the content itself, else the file is refused. Only
 * content:// URIs whose provider belongs to another app are read.
 */
class LocalImportCoordinatorExtensionTest {

    @get:Rule val tmp = TemporaryFolder()

    private val contentResolver = mockk<ContentResolver>()
    private val context = mockk<Context>()
    private val fileOrganizer = mockk<FileOrganizer>()
    private val musicRepository = mockk<MusicRepository>(relaxed = true)
    private lateinit var tempDir: File

    /** What the import pipeline is allowed to name a file. */
    private val audioExtensions =
        setOf("mp3", "m4a", "mp4", "aac", "flac", "ogg", "oga", "opus", "wav", "webm", "mka", "amr", "awb")

    /** Every format commitDownload was asked to use. */
    private val committedFormats = mutableListOf<String>()

    /** What the device's retriever reports as the content's container type. */
    private var sniffedMime: String? = null

    /** What the device's retriever reports for "has video" ("yes" or nothing). */
    private var sniffedHasVideo: String? = null

    @Before
    fun setUp() {
        tempDir = tmp.newFolder("downloads")
        every { context.contentResolver } returns contentResolver
        every { context.packageName } returns OWN_PACKAGE
        every { fileOrganizer.getTempDir() } returns tempDir
        every { fileOrganizer.getAlbumArtDir() } returns tmp.newFolder("albumart")
        // Every parameter has a matcher here and in the verifies below: one left
        // to its default would match only that default (asNewFile = false), not
        // the import's commit.
        coEvery { fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            committedFormats += arg<String>(4)
            val temp = firstArg<File>()
            val size = temp.length()
            temp.delete()
            FileOrganizer.CommittedTrack("/music/imported.${arg<String>(4)}", size)
        }

        // What the provider says its file is called. Set per test via [share].
        mockkStatic(DocumentFile::class)
        // Stubbed so that naming files from the device's MIME table again
        // would fail assertOnlyAudioFormats().
        mockkStatic(MimeTypeMap::class)
        val mimeTypeMap = mockk<MimeTypeMap>()
        every { mimeTypeMap.getExtensionFromMimeType(any()) } returns null
        every { mimeTypeMap.getExtensionFromMimeType("audio/mpeg") } returns "mp3"
        every { mimeTypeMap.getExtensionFromMimeType("text/html") } returns "html"
        every { mimeTypeMap.getExtensionFromMimeType("application/vnd.android.package-archive") } returns "apk"
        every { MimeTypeMap.getSingleton() } returns mimeTypeMap

        // The retriever finds no tags; its container type is [sniffedMime].
        mockkConstructor(MediaMetadataRetriever::class)
        every { anyConstructed<MediaMetadataRetriever>().setDataSource(any<String>()) } just Runs
        every { anyConstructed<MediaMetadataRetriever>().extractMetadata(any()) } returns null
        every {
            anyConstructed<MediaMetadataRetriever>().extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
        } answers { sniffedMime }
        every {
            anyConstructed<MediaMetadataRetriever>().extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
        } answers { sniffedHasVideo }
        every { anyConstructed<MediaMetadataRetriever>().embeddedPicture } returns null
        every { anyConstructed<MediaMetadataRetriever>().release() } just Runs
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private var nextId = 0

    /** A content:// share whose provider, at [authority], reports [displayName] and [mime]. */
    private fun share(
        displayName: String?,
        mime: String?,
        bytes: ByteArray = "not really audio".toByteArray(),
        authority: String = "com.example.provider",
    ): Uri {
        val uri = mockk<Uri>()
        every { uri.scheme } returns "content"
        every { uri.authority } returns authority
        every { uri.toString() } returns "content://$authority/item/${nextId++}"
        every { DocumentFile.fromSingleUri(context, uri) } returns mockk { every { name } returns displayName }
        every { contentResolver.getType(uri) } returns mime
        every { contentResolver.openInputStream(uri) } answers { bytes.inputStream() }
        return uri
    }

    private fun import(vararg uris: Uri): LocalImportState.Done = runBlocking {
        val coordinator = LocalImportCoordinator(context, fileOrganizer, musicRepository)
        coordinator.start(uris.toList())
        withTimeout(10_000) {
            coordinator.state.first { it is LocalImportState.Done } as LocalImportState.Done
        }
    }

    private fun assertOnlyAudioFormats() {
        val bad = committedFormats.filterNot { it in audioExtensions }
        assertTrue("committed under non-audio formats: $bad", bad.isEmpty())
    }

    private fun assertNothingLeftBehind() {
        val left = tmp.root.walkTopDown().filter { it.isFile }.toList()
        assertTrue("files left behind: $left", left.isEmpty())
    }

    @Test
    fun `a display name whose extension isn't an audio type never names the file`() {
        val names = listOf(
            "notes.html",
            "song.apk",
            "song.mp3/../x",
            "song.mp3\\..\\x",
            "song.ｍｐ３", // fullwidth letters and digit
            "song.mp３",
            "song.mp3\u0000.png",
            "song." + "a".repeat(300),
            "song.mp3 ",
        )

        val done = import(*names.map { share(displayName = it, mime = null) }.toTypedArray())

        assertOnlyAudioFormats()
        // None of these is identifiable audio either, so each is refused.
        assertEquals(LocalImportState.Done(imported = 0, failed = names.size), done)
        assertNothingLeftBehind()
    }

    @Test
    fun `a MIME type that isn't audio never names the file`() {
        val done = import(
            share(displayName = "song", mime = "text/html"),
            share(displayName = null, mime = "application/vnd.android.package-archive"),
        )

        assertOnlyAudioFormats()
        assertEquals(LocalImportState.Done(imported = 0, failed = 2), done)
    }

    @Test
    fun `an audio MIME type wins over a display name with separators`() {
        val done = import(share(displayName = "song.mp3/../x", mime = "audio/mpeg"))

        assertEquals(LocalImportState.Done(imported = 1, failed = 0), done)
        assertEquals(listOf("mp3"), committedFormats)
    }

    @Test
    fun `an upper-case audio extension is accepted in lower case`() {
        val done = import(share(displayName = "Radiohead - Idioteque.MP3", mime = null))

        assertEquals(LocalImportState.Done(imported = 1, failed = 0), done)
        assertEquals(listOf("mp3"), committedFormats)
    }

    @Test
    fun `Matroska audio and AMR voice notes import under their own extensions`() {
        val done = import(
            share(displayName = "Song.MKA", mime = null),
            share(displayName = null, mime = "audio/x-matroska"),
            share(displayName = "memo.amr", mime = null),
            share(displayName = null, mime = "audio/amr"),
            share(displayName = "memo", mime = "audio/amr-wb"),
        )

        assertEquals(LocalImportState.Done(imported = 5, failed = 0), done)
        assertEquals(listOf("mka", "mka", "amr", "amr", "awb"), committedFormats)
    }

    @Test
    fun `with no usable name or type the content decides, and unknown content is refused`() {
        sniffedMime = "audio/flac"
        val identified = import(share(displayName = null, mime = null))
        assertEquals(LocalImportState.Done(imported = 1, failed = 0), identified)
        assertEquals(listOf("flac"), committedFormats)

        sniffedMime = null
        val unknown = import(share(displayName = "track", mime = "application/octet-stream"))
        assertEquals(LocalImportState.Done(imported = 0, failed = 1), unknown)
        assertEquals("nothing more was committed", listOf("flac"), committedFormats)
        assertNothingLeftBehind()
    }

    @Test
    fun `AMR content with no usable name or type imports under its band's extension`() {
        sniffedMime = "audio/amr"
        val narrow = import(share(displayName = null, mime = null))
        sniffedMime = "audio/amr-wb"
        val wide = import(share(displayName = "memo", mime = "application/octet-stream"))

        assertEquals(LocalImportState.Done(imported = 1, failed = 0), narrow)
        assertEquals(LocalImportState.Done(imported = 1, failed = 0), wide)
        assertEquals(listOf("amr", "awb"), committedFormats)
    }

    @Test
    fun `Matroska and WebM content without video imports as audio`() {
        sniffedMime = "video/x-matroska"
        val mka = import(share(displayName = "track", mime = "application/octet-stream"))
        sniffedMime = "video/webm"
        val webm = import(share(displayName = null, mime = null))

        assertEquals(LocalImportState.Done(imported = 1, failed = 0), mka)
        assertEquals(LocalImportState.Done(imported = 1, failed = 0), webm)
        assertEquals(listOf("mka", "webm"), committedFormats)
    }

    @Test
    fun `Matroska and WebM content with video is refused`() {
        sniffedHasVideo = "yes"
        sniffedMime = "video/x-matroska"
        val mkv = import(share(displayName = "clip", mime = null))
        sniffedMime = "video/webm"
        val webm = import(share(displayName = null, mime = null))

        assertEquals(LocalImportState.Done(imported = 0, failed = 1), mkv)
        assertEquals(LocalImportState.Done(imported = 0, failed = 1), webm)
        assertTrue("nothing may be committed, got $committedFormats", committedFormats.isEmpty())
        assertNothingLeftBehind()
    }

    @Test
    fun `a provider's video type never names the file`() {
        val done = import(
            share(displayName = "clip", mime = "video/x-matroska"),
            share(displayName = null, mime = "video/webm"),
        )

        assertEquals(LocalImportState.Done(imported = 0, failed = 2), done)
        assertTrue("nothing may be committed, got $committedFormats", committedFormats.isEmpty())
    }

    @Test
    fun `a URI from one of this app's own providers is never read`() {
        val own = listOf(
            share(displayName = "song.mp3", mime = "audio/mpeg", authority = "$OWN_PACKAGE.fileprovider"),
            share(displayName = "song.mp3", mime = "audio/mpeg", authority = "$OWN_PACKAGE.androidx-startup"),
            share(displayName = "song.mp3", mime = "audio/mpeg", authority = OWN_PACKAGE),
            share(displayName = "song.mp3", mime = "audio/mpeg", authority = "0@$OWN_PACKAGE.fileprovider"),
        )

        val done = import(*own.toTypedArray())

        assertEquals(LocalImportState.Done(imported = 0, failed = own.size), done)
        own.forEach { uri -> verify(exactly = 0) { contentResolver.openInputStream(uri) } }
        coVerify(exactly = 0) { fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `another app's provider is read, whatever its name starts with`() {
        val done = import(
            share(displayName = "song.mp3", mime = "audio/mpeg", authority = "${OWN_PACKAGE}x.provider"),
            share(displayName = "song.mp3", mime = "audio/mpeg", authority = "com.example.provider"),
        )

        assertEquals(LocalImportState.Done(imported = 2, failed = 0), done)
    }

    @Test
    fun `a file URI is never read`() {
        val localFile = tmp.newFile("x.mp3").apply { writeText("audio") }
        val uri = mockk<Uri>()
        every { uri.scheme } returns "file"
        every { uri.authority } returns null
        every { uri.path } returns localFile.absolutePath
        every { uri.toString() } returns "file://${localFile.absolutePath}"
        every { DocumentFile.fromSingleUri(context, uri) } returns mockk { every { name } returns "x.mp3" }
        every { contentResolver.getType(uri) } returns null
        every { contentResolver.openInputStream(uri) } answers { localFile.inputStream() }

        val done = import(uri)

        assertEquals(LocalImportState.Done(imported = 0, failed = 1), done)
        verify(exactly = 0) { contentResolver.openInputStream(uri) }
        coVerify(exactly = 0) { fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    private companion object {
        const val OWN_PACKAGE = "org.example.player"
    }
}
