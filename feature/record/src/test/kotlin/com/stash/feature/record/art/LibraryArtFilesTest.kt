package com.stash.feature.record.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/** A JPEG of [w]×[h] (left half red, right half blue) with an EXIF segment carrying [mark]. */
internal fun jpegWithExif(w: Int, h: Int, mark: String): ByteArray {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(Color.BLUE)
    c.drawRect(0f, 0f, w / 2f, h.toFloat(), Paint().apply { color = Color.RED })
    val plain = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    // APP1: "Exif\0\0", a big-endian TIFF header, an empty IFD, then the mark (a GPS position, say) as padding bytes
    val body = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) +
        byteArrayOf(0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00) +
        mark.toByteArray(Charsets.ISO_8859_1)
    val len = body.size + 2
    val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), (len and 0xff).toByte()) + body
    return plain.copyOfRange(0, 2) + app1 + plain.copyOfRange(2, plain.size)
}

internal fun ByteArray.contains(part: ByteArray): Boolean =
    (0..size - part.size).any { i -> part.indices.all { this[i + it] == part[it] } }

/** Decodes as Coil would for a file (BitmapFactory, sampled down near [px]); an address is never fetched here. */
internal val testDecoder = ArtDecoder { model, px ->
    if (model.contains("://")) return@ArtDecoder null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(model, bounds)
    if (bounds.outWidth <= 0) return@ArtDecoder null
    var sample = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
    BitmapFactory.decodeFile(model, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** LibraryArtEncodeTest of PHASE6.md: a cover made from a file is a small clean square, from the app's own files only. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryArtEncodeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var files: LibraryArtFiles

    @Before fun setUp() {
        files = LibraryArtFiles(File(context.cacheDir, LibraryArtFiles.DIR), listOf(context.filesDir, context.cacheDir), testDecoder, Dispatchers.Unconfined)
        File(context.cacheDir, LibraryArtFiles.DIR).deleteRecursively()
    }

    private fun served(url: String): File = File(files.dir, url.removePrefix(LibraryArtFiles.URL_PREFIX))

    @Test fun `a 3000 px cover becomes a JPEG square of at most 600, its EXIF gone`() = runTest {
        val src = File(context.filesDir, "albumart/local_big.jpg").apply { parentFile!!.mkdirs(); writeBytes(jpegWithExif(3000, 2000, "SECRET-GPS-48.85N")) }
        assertThat(src.readBytes().contains("Exif".toByteArray())).isTrue()
        val url = files.forFile(src.path)!!
        assertThat(url).matches("https://appassets\\.androidplatform\\.net/art/v1/[0-9a-f]{32}\\.jpg")
        val out = served(url).readBytes()
        assertThat(out.copyOfRange(0, 2)).isEqualTo(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        assertThat(out.contains("Exif".toByteArray())).isFalse()
        assertThat(out.contains("SECRET-GPS".toByteArray())).isFalse()
        val b = BitmapFactory.decodeByteArray(out, 0, out.size)
        assertThat(b.width).isEqualTo(b.height)
        assertThat(b.width).isAtMost(600)
        assertThat(b.width).isAtLeast(500)
        // The middle square: red on the left, blue on the right
        assertThat(Color.red(b.getPixel(10, b.height / 2))).isGreaterThan(200)
        assertThat(Color.blue(b.getPixel(b.width - 10, b.height / 2))).isGreaterThan(200)
    }

    // API 27: Robolectric's ImageDecoder can't read a file, so Coil decodes with BitmapFactory here (on the phone, ImageDecoder)
    @Config(sdk = [27])
    @Test fun `through Coil, as the app decodes it, a 3000 px file is a 600 square too, and an address never fetched is nothing`() = runTest {
        val coil = LibraryArtFiles(files.dir, listOf(context.filesDir, context.cacheDir), CoilArtDecoder(context), Dispatchers.Unconfined)
        val src = File(context.filesDir, "albumart/local_coil.jpg").apply { parentFile!!.mkdirs(); writeBytes(jpegWithExif(3000, 2000, "SECRET-GPS")) }
        val out = served(coil.forFile(src.path)!!).readBytes()
        assertThat(out.contains("SECRET-GPS".toByteArray())).isFalse()
        val b = BitmapFactory.decodeByteArray(out, 0, out.size)
        assertThat(b.width to b.height).isEqualTo(600 to 600)
        assertThat(coil.forUrl("https://i.scdn.co/image/never-cached")).isNull()
    }

    @Test fun `the same file twice is one name, and a changed file is another`() = runTest {
        val src = File(context.filesDir, "a.jpg").apply { writeBytes(jpegWithExif(700, 700, "x")) }
        val first = files.forFile(src.path)!!
        assertThat(files.forFile(src.path)).isEqualTo(first)
        src.writeBytes(jpegWithExif(800, 800, "y"))
        src.setLastModified(src.lastModified() + 5_000)
        assertThat(files.forFile(src.path)).isNotEqualTo(first)
    }

    @Test fun `a file that isn't a picture gives nothing, and leaves nothing behind`() = runTest {
        val src = File(context.filesDir, "not-art.jpg").apply { writeText("SQLite format 3\u0000 secrets") }
        assertThat(files.forFile(src.path)).isNull()
        assertThat(files.dir.listFiles().orEmpty().toList()).isEmpty()
    }

    @Test fun `a path outside the app's own folders is refused, even one that climbs out of them`() = runTest {
        val outside = File(context.filesDir.parentFile!!.parentFile, "outside.jpg").apply { writeBytes(jpegWithExif(100, 100, "x")) }
        assertThat(files.forFile(outside.path)).isNull()
        assertThat(files.forFile(context.filesDir.path + "/../../" + outside.name)).isNull()
        assertThat(files.forFile("content://media/external/images/1")).isNull()
        assertThat(files.forFile("/sdcard/Music/cover.jpg")).isNull()
        assertThat(files.forFile("relative/cover.jpg")).isNull()
        assertThat(files.forUrl("file:///data/data/x/databases/stash.db")).isNull()
        outside.delete()
    }
}

/** LibraryArtHandlerTest of PHASE6.md: `/art/` serves only names given, from its own folder. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryArtHandlerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var files: LibraryArtFiles
    private lateinit var handler: LibraryArtHandler

    @Before fun setUp() {
        File(context.cacheDir, LibraryArtFiles.DIR).deleteRecursively()
        files = LibraryArtFiles(File(context.cacheDir, LibraryArtFiles.DIR), listOf(context.filesDir, context.cacheDir), testDecoder, Dispatchers.Unconfined)
        handler = LibraryArtHandler(files)
    }

    private suspend fun given(): String {
        val src = File(context.filesDir, "cover.jpg").apply { writeBytes(jpegWithExif(640, 640, "x")) }
        return files.forFile(src.path)!!.removePrefix("https://appassets.androidplatform.net/art/")
    }

    @Test fun `a name given is a year-long JPEG`() = runTest {
        val path = given()
        val r = handler.handle(path)!!
        assertThat(r.mimeType).isEqualTo("image/jpeg")
        assertThat(r.statusCode).isEqualTo(200)
        assertThat(r.responseHeaders).containsExactly("Cache-Control", "private, max-age=31536000, immutable")
        val bytes = r.data.use { it.readBytes() }
        assertThat(BitmapFactory.decodeByteArray(bytes, 0, bytes.size).width).isEqualTo(600)
    }

    @Test fun `only v1 and 32 lowercase hex, and only a name it gave`() = runTest {
        val path = given()
        val hash = path.removePrefix("v1/").removeSuffix(".jpg")
        // A file of the right shape put in the folder by no one: no source, still its own folder's
        for (bad in listOf(
            "v1/../../databases/stash.db",
            "v1/%2e%2e/%2e%2e/databases/stash.db",
            "v1/..%2F..%2Fdatabases%2Fstash.db",
            "../databases/stash.db",
            "v1/${hash.uppercase()}.jpg",
            "v1/$hash.jpg/",
            "v1/$hash.jpg\n",
            "v1/$hash.png",
            "v2/$hash.jpg",
            "v1/${hash.dropLast(1)}.jpg",
            "v1/${hash}0.jpg",
            "/v1/$hash.jpg",
            "v1/$hash",
            "",
        )) assertThat(handler.handle(bad)).isNull()
        assertThat(handler.handle("v1/${"0".repeat(32)}.jpg")).isNull() // never given
        assertThat(handler.handle(path)).isNotNull()
    }

    @Test fun `a cover the trim removed is made again from what it was made from`() = runTest {
        val path = given()
        files.trim(maxBytes = 0)
        assertThat(files.dir.listFiles().orEmpty().toList()).isEmpty()
        val r = handler.handle(path)!!
        assertThat(r.data.use { it.readBytes() }.size).isGreaterThan(0)
    }

    @Test fun `the trim keeps the covers shown last`() = runTest {
        val a = File(context.filesDir, "a.jpg").apply { writeBytes(jpegWithExif(700, 700, "a")) }
        val b = File(context.filesDir, "b.jpg").apply { writeBytes(jpegWithExif(900, 500, "b")) }
        val pa = files.forFile(a.path)!!.removePrefix("https://appassets.androidplatform.net/art/")
        val pb = files.forFile(b.path)!!.removePrefix("https://appassets.androidplatform.net/art/")
        val fa = File(files.dir, pa.removePrefix("v1/"))
        val fb = File(files.dir, pb.removePrefix("v1/"))
        fa.setLastModified(1_000)
        fb.setLastModified(2_000)
        files.trim(maxBytes = fb.length())
        assertThat(fa.exists()).isFalse()
        assertThat(fb.exists()).isTrue()
    }
}
