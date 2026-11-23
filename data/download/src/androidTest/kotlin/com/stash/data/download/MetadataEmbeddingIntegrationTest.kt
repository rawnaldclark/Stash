package com.stash.data.download

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stash.core.common.AppVersionProvider
import com.stash.core.model.Track
import com.stash.data.download.files.ExtraTagSource
import com.stash.data.download.files.ExtraTags
import com.stash.data.download.files.MetadataEmbedder
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MetadataEmbeddingIntegrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val versionProvider = object : AppVersionProvider {
        override val versionName: String = "0.9.35-test"
        override val versionCode: Int = 71
    }
    // Task 5 removed FileOrganizer from MetadataEmbedder's constructor —
    // only context + appVersion needed for the embed pass.
    private val embedder = MetadataEmbedder(context, versionProvider)

    private lateinit var artFile: File

    @Before fun setUp() {
        // MetadataEmbedder.resolveFfmpegBinary() expects the youtubedl-android
        // ffmpeg library to have already unpacked libffmpeg.zip.so into
        // nativeLibraryDir — which only happens after FFmpeg.init runs. In
        // the production app that's done at app start inside
        // YtDlpManager.initialize; instrumented tests run in their own
        // process with no such init, so we replicate it here. (YoutubeDL.init
        // is intentionally NOT called — its Python bootstrap fails in the
        // self-instrumenting library test APK, and the embedder doesn't need it.)
        FFmpeg.getInstance().init(context)
        artFile = copyAssetToCache("sample_art.jpg")
    }

    @Test fun embedsTagsAndArtIntoOpus() = runBlocking {
        // v0.9.38 (#95): Opus art is now embedded via METADATA_BLOCK_PICTURE
        // (base64-encoded FLAC picture block in the Vorbis comment), replacing
        // the prior attached_pic path that ffmpeg rejects for Ogg streams.
        // MediaMetadataRetriever.embeddedPicture should now return non-null.
        verifyRoundTrip("sample_opus.opus")
    }

    /**
     * Issue #118: METADATA_BLOCK_PICTURE puts the base64-encoded FLAC Picture
     * block into a single argv entry passed to ffmpeg. Linux MAX_ARG_STRLEN
     * is ~128KB on most kernels, so realistic album-art sizes (300–500KB raw
     * JPEG → 400–700KB base64) could in theory overflow if youtubedl-android's
     * ffmpeg bridge does a plain exec().
     *
     * Stress test: use a 2.19MB synthetic noise JPEG (base64 ≈ 3MB) — well
     * beyond anything Spotify or YouTube would actually ship as cover art —
     * and confirm the embed still round-trips on a real device. If this test
     * passes, the kernel/bridge handles oversized argv entries fine and the
     * defensive ffmetadata-file fallback we sketched in the issue isn't
     * needed in practice. If it fails, that fallback becomes mandatory.
     */
    @Test fun embedsTagsAndLargeArtIntoOpus() = runBlocking {
        val largeArt = copyAssetToCache("sample_art_large.jpg")
        val source = copyAssetToCache("sample_opus.opus")
        val track = Track(
            id = 1, title = "Test Title", artist = "Test Artist",
            albumArtist = "Test AlbumArtist", album = "Test Album",
            isrc = "USTEST0000001",
        )

        val result = embedder.embedMetadata(source, track, largeArt)

        MediaMetadataRetriever().apply {
            setDataSource(result.absolutePath)
            assertEquals("Test Title", extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
            val art = embeddedPicture
            assertNotNull(
                "Issue #118: 2.19MB JPEG → base64 ≈ 3MB argv must still embed. " +
                    "If this assertion is null, the kernel argv limit hit us — " +
                    "implement the -i metadata.txt fallback.",
                art,
            )
            assertTrue("Embedded picture should be non-empty", art!!.isNotEmpty())
            release()
        }
        Unit
    }

    @Test fun embedsTagsAndArtIntoM4a() = runBlocking {
        verifyRoundTrip("sample_m4a.m4a")
    }

    @Test fun embedsTagsAndArtIntoFlac() = runBlocking {
        verifyRoundTrip("sample_flac.flac")
    }

    /**
     * A bare file (title and artist only, no album) takes the catalogue's
     * album, year, track number and genre, in each container; and a second
     * pass over the now-complete file does not even ask the catalogue.
     */
    @Test fun fillsABareFilesBlanksFromTheCatalogue() = runBlocking {
        for (asset in listOf("sample_flac.flac", "sample_opus.opus", "sample_m4a.m4a")) {
            var asked = 0
            val catalogue = object : ExtraTagSource {
                override suspend fun lookUp(track: Track): ExtraTags? {
                    asked++
                    return ExtraTags(album = "In Rainbows", year = 2007, trackNumber = 3, discNumber = 2, genre = "Alternative")
                }
            }
            val withCatalogue = MetadataEmbedder(context, versionProvider, catalogue)
            val source = copyAssetToCache(asset)
            val track = Track(id = 1, title = "Nude", artist = "Radiohead")

            val result = withCatalogue.embedMetadata(source, track, artFile)

            MediaMetadataRetriever().apply {
                setDataSource(result.absolutePath)
                assertEquals("$asset title", "Nude", extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
                assertEquals("$asset album", "In Rainbows", extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
                assertEquals("$asset genre", "Alternative", extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE))
                val year = extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                    ?: extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                if (asset.endsWith(".m4a")) {
                    // Android's reader does not report an MP4 file's year (its DATE is the container's
                    // creation time), so the tag is looked for in the file itself: the "(c)day" atom.
                    val bytes = result.readBytes().toString(Charsets.ISO_8859_1)
                    val at = bytes.indexOf("©day")
                    assertTrue("$asset has a year atom", at >= 0)
                    assertTrue("$asset year atom says 2007", bytes.substring(at, at + 40).contains("2007"))
                } else {
                    assertTrue("$asset year, was $year", year.orEmpty().contains("2007"))
                }
                val number = extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                assertTrue("$asset track, was $number", number.orEmpty().startsWith("3"))
                release()
            }
            assertEquals("$asset asked once", 1, asked)

            withCatalogue.embedMetadata(result, track, artFile)
            assertEquals("$asset: a complete file is not looked up again", 1, asked)
        }
    }

    private suspend fun verifyRoundTrip(asset: String) {
        val source = copyAssetToCache(asset)
        val track = Track(
            id = 1, title = "Test Title", artist = "Test Artist",
            albumArtist = "Test AlbumArtist", album = "Test Album",
            isrc = "USTEST0000001",
        )

        val result = embedder.embedMetadata(source, track, artFile)

        MediaMetadataRetriever().apply {
            setDataSource(result.absolutePath)
            assertEquals("Test Title", extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
            assertEquals("Test Artist", extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
            assertEquals("Test Album", extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
            assertEquals("Test AlbumArtist", extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))
            val art = embeddedPicture
            assertNotNull("Expected an embedded picture for $asset", art)
            assertTrue("Embedded picture should be non-empty for $asset", art!!.isNotEmpty())
            release()
        }
    }

    private fun copyAssetToCache(name: String): File {
        val target = File(context.cacheDir, "intgtest_$name")
        context.assets.open(name).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return target
    }
}
