package com.stash.feature.record.imports

import com.google.common.truth.Truth.assertThat
import com.stash.feature.record.importer.PickedFile
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The intake: files brought (picked, shared, opened with Stash) copied into the app's storage with the names the person saw, and
 * the sniff a shared file goes through before the import takes it (names inside, nested zips too, nothing inflated).
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
        fun sniff(name: String, f: File) = intake.looksLikeExport(listOf(PickedFile(name, f)), scratch)

        assertThat(sniff("my_spotify_data.zip", resource("my_spotify_data.zip"))).isTrue() // a zip inside a zip
        assertThat(sniff("my_spotify_data (1).zip", resource("my_spotify_data (1).zip"))).isTrue()
        assertThat(sniff("StreamingHistory_music_0.json", file("StreamingHistory_music_0.json", "[]".toByteArray()))).isTrue()
        assertThat(sniff("holiday_photos.zip", file("holiday.zip", zipOf("notes/packing-list.txt" to "socks".toByteArray(), "notes/settings.json" to "{}".toByteArray())))).isFalse()
        assertThat(sniff("settings.json", file("settings.json", "{}".toByteArray()))).isFalse()
        assertThat(sniff("broken.zip", file("broken.zip", "PK not really".toByteArray()))).isFalse()
        assertThat(sniff("Streaming_History_Video_2022.json", file("v.json", "[]".toByteArray()))).isFalse()
        assertThat(scratch.listFiles().orEmpty()).isEmpty()
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
