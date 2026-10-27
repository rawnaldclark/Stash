package com.stash.feature.record.imports

import com.google.common.truth.Truth.assertThat
import com.stash.feature.record.importer.ExportZip
import com.stash.feature.record.importer.ImportBudget
import com.stash.feature.record.importer.ImportTooBig
import com.stash.feature.record.importer.PickedFile
import com.stash.feature.record.importer.StorageFull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The intake: files brought (picked, shared, opened with Stash) copied into the app's storage with the names the person saw, and
 * the sniff a shared file goes through before the import takes it (names inside, nested zips too, nothing inflated), and the
 * copy's bounds: only the bytes read count (never the size a provider claims), and a copy stops at the import's ceiling or
 * before the phone's last free space, leaving nothing behind.
 */
class ImportIntakeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun resource(name: String): File = File(requireNotNull(javaClass.getResource("/spotify-export/$name")).toURI())

    private fun source(name: String?, file: File) = IntakeSource(name, file.length()) { file.inputStream() }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }.toByteArray()

    private fun file(name: String, bytes: ByteArray) = tmp.newFile(name).apply { writeBytes(bytes) }

    @Test fun `copies keep the name the person saw, in the order brought`() {
        val intake = ImportIntake(tmp.newFolder("files"))
        val copies = intake.copy(listOf(source("my_spotify_data.zip", resource("my_spotify_data.zip")), source("my_spotify_data (1).zip", resource("my_spotify_data (1).zip"))), emptyList())

        assertThat(copies.map { it.name }).containsExactly("my_spotify_data.zip", "my_spotify_data (1).zip").inOrder()
        assertThat(copies.map { it.file.readBytes().size }).containsExactly(resource("my_spotify_data.zip").length().toInt(), resource("my_spotify_data (1).zip").length().toInt()).inOrder()
        assertThat(intake.existing().map { it.name }).containsExactly("my_spotify_data.zip", "my_spotify_data (1).zip").inOrder()
    }

    @Test fun `a file already brought, by its name and size, is not copied again`() {
        val intake = ImportIntake(tmp.newFolder("files"))
        val first = intake.copy(listOf(source("my_spotify_data.zip", resource("my_spotify_data.zip"))), emptyList())
        val again = intake.copy(listOf(source("my_spotify_data.zip", resource("my_spotify_data.zip")), source("my_spotify_data (1).zip", resource("my_spotify_data (1).zip"))), first)
        assertThat(again.map { it.name }).containsExactly("my_spotify_data (1).zip")
    }

    @Test fun `a zip a provider names without an extension is known by its signature, a path in a name is dropped`() {
        val intake = ImportIntake(tmp.newFolder("files"))
        val copies = intake.copy(listOf(source("my_spotify_data", resource("my_spotify_data.zip")), source("../../etc/notes.json", file("n.json", "[]".toByteArray())), source(null, file("x.bin", byteArrayOf(1, 2, 3)))), emptyList())
        assertThat(copies.map { it.name }).containsExactly("my_spotify_data.zip", "notes.json", "file").inOrder()
        assertThat(copies.all { it.file.parentFile == tmp.root.resolve("files") }).isTrue()
    }

    @Test fun `a file that can't be opened leaves no half copy`() {
        val dir = tmp.newFolder("files")
        val intake = ImportIntake(dir)
        val broken = IntakeSource("my_spotify_data.zip", null) { throw IOException("gone") }
        runCatching { intake.copy(listOf(broken), emptyList()) }
        assertThat(dir.listFiles().orEmpty()).isEmpty()
    }

    @Test fun `the sniff - Spotify's names inside a zip, nested too, or a loose file Spotify names`() {
        val intake = ImportIntake(tmp.newFolder("files"))
        val scratch = tmp.newFolder("scratch")
        fun sniff(name: String, f: File) = ExportZip(scratch, ImportBudget()).use { intake.looksLikeExport(listOf(PickedFile(name, f)), it) }

        assertThat(sniff("my_spotify_data.zip", resource("my_spotify_data.zip"))).isTrue() // a zip inside a zip
        assertThat(sniff("my_spotify_data (1).zip", resource("my_spotify_data (1).zip"))).isTrue()
        assertThat(sniff("StreamingHistory_music_0.json", file("StreamingHistory_music_0.json", "[]".toByteArray()))).isTrue()
        assertThat(sniff("holiday_photos.zip", file("holiday.zip", zipOf("notes/packing-list.txt" to "socks".toByteArray(), "notes/settings.json" to "{}".toByteArray())))).isFalse()
        assertThat(sniff("settings.json", file("settings.json", "{}".toByteArray()))).isFalse()
        assertThat(sniff("broken.zip", file("broken.zip", "PK not really".toByteArray()))).isFalse()
        assertThat(sniff("Streaming_History_Video_2022.json", file("v.json", "[]".toByteArray()))).isFalse()
        assertThat(scratch.listFiles().orEmpty()).isEmpty()
    }

    /** A provider's file that never ends (what a `/dev/zero` behind a content URI would be). */
    private fun endless(claim: Long?) = IntakeSource("my_spotify_data.zip", claim) {
        object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = len.also { b.fill(0, off, off + len) }
        }
    }

    @Test fun `a file that never ends stops at the import's ceiling, whatever size it claims, and leaves nothing`() {
        val dir = tmp.newFolder("files")
        val intake = ImportIntake(dir, maxTotal = 1L shl 20)
        assertThrows(ImportTooBig::class.java) { intake.copy(listOf(endless(claim = 10)), emptyList()) }
        assertThrows(ImportTooBig::class.java) { intake.copy(listOf(endless(claim = null)), emptyList()) }
        assertThat(dir.listFiles().orEmpty()).isEmpty()
    }

    @Test fun `a copy stops before the phone's last free space, and every copy of that call goes with it`() {
        val dir = tmp.newFolder("files")
        // 1 MiB free past the headroom, less what's been written (a disk)
        val intake = ImportIntake(dir, headroom = 8L shl 20, freeSpace = { (9L shl 20) - dir.listFiles().orEmpty().sumOf { it.length() } })
        val ok = source("my_spotify_data (1).zip", resource("my_spotify_data (1).zip"))
        assertThrows(StorageFull::class.java) { intake.copy(listOf(ok, endless(claim = 100)), emptyList()) }
        assertThat(dir.listFiles().orEmpty()).isEmpty()
    }

    @Test fun `the bytes read are what count, never the size a provider claims`() {
        val intake = ImportIntake(tmp.newFolder("files"))
        val real = resource("my_spotify_data.zip")
        val copy = intake.copy(listOf(IntakeSource("my_spotify_data.zip", 12) { real.inputStream() }), emptyList()).single()
        assertThat(copy.file.length()).isEqualTo(real.length())
        // The ceiling counts the copies the import has already
        val small = ImportIntake(tmp.newFolder("files2"), maxTotal = real.length() + 10)
        val first = small.copy(listOf(source("my_spotify_data.zip", real)), emptyList())
        assertThrows(ImportTooBig::class.java) { small.copy(listOf(source("my_spotify_data (1).zip", resource("my_spotify_data (1).zip"))), first) }
    }

    @Test fun `a cancel during the copy stops it between blocks and leaves no file`() {
        val dir = tmp.newFolder("files")
        val intake = ImportIntake(dir)
        var blocks = 0
        assertThrows(CancellationException::class.java) {
            intake.copy(listOf(endless(claim = null)), emptyList()) { if (++blocks > 3) throw CancellationException("cancelled") }
        }
        assertThat(dir.listFiles().orEmpty()).isEmpty()
    }

    @Test fun `a name is cut by its UTF-8 bytes, never through a character, its extension kept`() {
        val cjk = "我的音乐数据".repeat(30) + ".zip" // 180 characters, 540 bytes before the extension
        val cut = ImportIntake.safeName(cjk)
        assertThat(cut.toByteArray(Charsets.UTF_8).size).isAtMost(200)
        assertThat(cut).endsWith(".zip")
        assertThat(cjk).startsWith(cut.removeSuffix(".zip"))
        val emoji = "🎵".repeat(80) + ".json" // 4 bytes each
        val cut2 = ImportIntake.safeName(emoji)
        assertThat(cut2.toByteArray(Charsets.UTF_8).size).isAtMost(200)
        assertThat(cut2.removeSuffix(".json").length % 2).isEqualTo(0) // whole pairs only
        assertThat(ImportIntake.safeName("my_spotify_data.zip")).isEqualTo("my_spotify_data.zip")
    }

    @Test fun `a copy still streaming is a part - never listed, never cleared under it, gone with everything at start`() {
        val dir = tmp.newFolder("files")
        val intake = ImportIntake(dir)
        val done = intake.copy(listOf(source("my_spotify_data.zip", resource("my_spotify_data.zip"))), emptyList())
        val part = intake.reserve("my_spotify_data (1).zip")

        assertThat(part.exists()).isTrue()
        assertThat(intake.existing().map { it.file }).containsExactly(done.single().file)
        // the next name after both
        val next = intake.reserve("x.json")
        assertThat(next.name).startsWith("part2__")
        intake.clear()
        assertThat(intake.existing()).isEmpty()
        assertThat(part.exists() && next.exists()).isTrue()
        intake.clearAll()
        assertThat(dir.listFiles().orEmpty()).isEmpty()
        // a file someone named .part is a copy like any other
        val named = intake.copy(listOf(source("history.part", file("h.part", "[]".toByteArray()))), emptyList())
        assertThat(intake.existing().map { it.name }).containsExactly("history.part")
        assertThat(named).hasSize(1)
    }

    @Test fun `an Error while streaming (not only an Exception) leaves no file behind`() {
        val dir = tmp.newFolder("files")
        val intake = ImportIntake(dir)
        val broken = IntakeSource("my_spotify_data.zip", null) {
            object : InputStream() {
                override fun read(): Int = throw OutOfMemoryError("test")
                override fun read(b: ByteArray, off: Int, len: Int): Int = throw OutOfMemoryError("test")
            }
        }
        assertThrows(OutOfMemoryError::class.java) {
            intake.copy(listOf(source("my_spotify_data (1).zip", resource("my_spotify_data (1).zip")), broken), emptyList())
        }
        assertThat(dir.listFiles().orEmpty()).isEmpty()
    }

    @Test fun `clear lets every copy go`() {
        val dir = tmp.newFolder("files")
        val intake = ImportIntake(dir)
        intake.copy(listOf(source("my_spotify_data.zip", resource("my_spotify_data.zip"))), emptyList())
        intake.clear()
        assertThat(dir.listFiles().orEmpty()).isEmpty()
        assertThat(intake.existing()).isEmpty()
    }
}
