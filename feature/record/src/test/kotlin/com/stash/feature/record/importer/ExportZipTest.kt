package com.stash.feature.record.importer

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset

/**
 * The zip reader against what a real export never is: Zip64 records, entries flagged encrypted, an entry that inflates past
 * what it says, directory records that share their data (a zip bomb's trick), more records than a zip may hold, and the
 * import's budget across zips inside zips. Plus the intake's look inside: the outer names first, an inner zip only when they
 * hold none.
 */
class ExportZipTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(bytes: ByteArray, name: String = "my_spotify_data.zip"): File = File(tmp.newFolder(), name).apply { writeBytes(bytes) }

    private fun zip(budget: ImportBudget = ImportBudget()) = ExportZip(tmp.newFolder(), budget)

    private val history = """[{"ts":"2024-03-01T10:00:00Z","ms_played":200000,"master_metadata_track_name":"Ivy","master_metadata_album_artist_name":"Frank Ocean","master_metadata_album_album_name":"Blonde","spotify_track_uri":"spotify:track:2ZWlPOoWh0626oTaHrnl2a"}]"""

    @Test fun `zip64 records - sizes and offsets from the extra field, the directory from the zip64 end record`() {
        val bytes = TestZip.build(
            listOf(
                TestZip.entry("Spotify Extended Streaming History/Streaming_History_Audio_2024.json", history, deflate = true),
                TestZip.entry("Spotify Extended Streaming History/ReadMeFirst.pdf", "pdf"),
            ),
            zip64 = true,
        )
        zip().use { z ->
            val entries = z.entries(file(bytes), "x.zip!/")
            assertThat(entries.map { it.name }).containsExactly(
                "x.zip!/Spotify Extended Streaming History/Streaming_History_Audio_2024.json",
                "x.zip!/Spotify Extended Streaming History/ReadMeFirst.pdf",
            ).inOrder()
            assertThat(entries[0].size).isEqualTo(history.length.toLong())
            assertThat(String(entries[0].open().use { it.readBytes() })).isEqualTo(history)
            assertThat(String(entries[1].open().use { it.readBytes() })).isEqualTo("pdf")
        }
    }

    @Test fun `a zip64 export is read like any other`() {
        val bytes = TestZip.build(listOf(TestZip.entry("Spotify Extended Streaming History/Streaming_History_Audio_2024.json", history, deflate = true)), zip64 = true)
        val f = file(bytes)
        val bundle = SpotifyImporter(MemoryArchive(), tmp.newFolder(), ZoneOffset.UTC).read(listOf(PickedFile("my_spotify_data.zip", f)), NOW)
        assertThat(bundle.files.extended).isEqualTo(1)
        assertThat(bundle.plays.size).isEqualTo(1)
        assertThat(bundle.unreadable).isEqualTo(0)
    }

    @Test fun `an encrypted entry is refused when read, and counts as unreadable`() {
        val bytes = TestZip.build(listOf(TestZip.Entry("Spotify Extended Streaming History/Streaming_History_Audio_2024.json", history.toByteArray(), flags = 1)))
        val f = file(bytes)
        zip().use { z ->
            val e = z.entries(f, "x!/").single()
            assertThrows(ZipError::class.java) { e.open() }
        }
        val bundle = SpotifyImporter(MemoryArchive(), tmp.newFolder(), ZoneOffset.UTC).read(listOf(PickedFile("my_spotify_data.zip", f)), NOW)
        assertThat(bundle.unreadable).isEqualTo(1)
        assertThat(bundle.plays.size).isEqualTo(0)
    }

    @Test fun `an entry that inflates past what it says stops there, and its file is unreadable`() {
        val big = "[" + (1..2_000).joinToString(",") { history.removePrefix("[").removeSuffix("]") } + "]"
        val bytes = TestZip.build(
            listOf(TestZip.Entry("Spotify Extended Streaming History/Streaming_History_Audio_2024.json", big.toByteArray(), deflate = true, claimSize = 1_000)),
        )
        val f = file(bytes)
        zip().use { z ->
            val e = z.entries(f, "x!/").single()
            val err = assertThrows(ZipError::class.java) { e.open().use { it.readBytes() } }
            assertThat(err.message).isEqualTo("entry larger than it says")
        }
        val bundle = SpotifyImporter(MemoryArchive(), tmp.newFolder(), ZoneOffset.UTC).read(listOf(PickedFile("my_spotify_data.zip", f)), NOW)
        // The file's rows roll back: none of it counts
        assertThat(bundle.unreadable).isEqualTo(1)
        assertThat(bundle.plays.size).isEqualTo(0)
    }

    @Test fun `directory records that share one entry's data are refused (a zip bomb's trick)`() {
        val bytes = TestZip.build(
            listOf(TestZip.entry("Spotify Extended Streaming History/Streaming_History_Audio_2024.json", history, deflate = true)),
            aliases = (1..3).map { "Spotify Extended Streaming History/Streaming_History_Audio_202$it.json" to 0 },
        )
        val f = file(bytes)
        zip().use { z ->
            val err = assertThrows(ZipError::class.java) { z.entries(f, "x!/") }
            assertThat(err.message).isEqualTo("overlapping entries")
            assertThat(z.sniff(f, "x!/")).isFalse()
        }
        // The importer counts the zip as one that wouldn't open
        val bundle = SpotifyImporter(MemoryArchive(), tmp.newFolder(), ZoneOffset.UTC).read(listOf(PickedFile("my_spotify_data.zip", f)), NOW)
        assertThat(bundle.unreadable).isEqualTo(1)
        assertThat(bundle.plays.size).isEqualTo(0)
    }

    @Test fun `more records than a zip may hold is not a zip this reader opens`() {
        val bytes = TestZip.build(listOf(TestZip.entry("a.json", "[]")))
        // The end record's two counts, 20,001
        val e = bytes.size - 22
        bytes[e + 8] = (20_001 and 0xff).toByte(); bytes[e + 9] = (20_001 shr 8).toByte()
        bytes[e + 10] = (20_001 and 0xff).toByte(); bytes[e + 11] = (20_001 shr 8).toByte()
        zip().use { z -> assertThrows(ZipError::class.java) { z.entries(file(bytes), "x!/") } }
    }

    @Test fun `the budget counts every zip's records, however deep`() {
        val inner = TestZip.build((1..4).map { TestZip.entry("f$it.txt", "x") })
        val outer = TestZip.build((1..3).map { TestZip.Entry("inner$it.zip", inner) })
        // 3 outer + 12 inner records: 15
        zip(ImportBudget(maxEntries = 15)).use { z -> assertThat(z.entries(file(outer), "x!/")).hasSize(12) }
        zip(ImportBudget(maxEntries = 14)).use { z -> assertThrows(ImportTooBig::class.java) { z.entries(file(outer), "x!/") } }
    }

    @Test fun `a zip inside a zip inflated to disk counts against the scratch budget and the free space`() {
        val inner = TestZip.build(listOf(TestZip.entry("Spotify Extended Streaming History/Streaming_History_Audio_2024.json", history.repeat(1))))
        val outer = TestZip.build(listOf(TestZip.Entry("my_spotify_data.zip", inner, deflate = true)))
        zip(ImportBudget(maxScratch = inner.size.toLong())).use { z -> assertThat(z.entries(file(outer), "x!/")).hasSize(1) }
        zip(ImportBudget(maxScratch = inner.size.toLong() - 1)).use { z -> assertThrows(ImportTooBig::class.java) { z.entries(file(outer), "x!/") } }
        zip(ImportBudget(freeSpace = { ImportBudget.SCRATCH_HEADROOM - 1 })).use { z -> assertThrows(StorageFull::class.java) { z.entries(file(outer), "x!/") } }
    }

    @Test fun `the bytes read out of the files and the rows count against the budget`() {
        val bytes = TestZip.build(listOf(TestZip.entry("Spotify Extended Streaming History/Streaming_History_Audio_2024.json", "[" + List(10) { history.removePrefix("[").removeSuffix("]") }.joinToString(",") + "]", deflate = true)))
        val f = listOf(PickedFile("my_spotify_data.zip", file(bytes)))
        val importer = SpotifyImporter(MemoryArchive(), tmp.newFolder(), ZoneOffset.UTC)
        assertThat(importer.read(f, NOW, zip = zip(ImportBudget(maxRows = 10))).plays.size).isEqualTo(10)
        assertThrows(ImportTooBig::class.java) { importer.read(f, NOW, zip = zip(ImportBudget(maxRows = 9))) }
        assertThrows(ImportTooBig::class.java) { importer.read(f, NOW, zip = zip(ImportBudget(maxInflated = 100))) }
    }

    @Test fun `the look inside reads the outer names first and opens an inner zip only when they hold none`() {
        val inner = TestZip.build(listOf(TestZip.entry("Spotify Extended Streaming History/Streaming_History_Audio_2024.json", history)))
        // An export's names outside: the deflated inner zip is never inflated to look
        val withNames = TestZip.build(listOf(TestZip.entry("Spotify Account Data/StreamingHistory_music_0.json", "[]"), TestZip.Entry("nested.zip", inner, deflate = true)))
        val scratch = tmp.newFolder()
        ExportZip(scratch, ImportBudget()).use { z ->
            assertThat(z.sniff(file(withNames), "x!/")).isTrue()
            assertThat(scratch.listFiles().orEmpty()).isEmpty()
        }
        // Only a zip outside (Spotify's Extended zip as some phones save it): opened to look, and the read reuses what it inflated
        val onlyZip = TestZip.build(listOf(TestZip.Entry("my_spotify_data.zip", inner, deflate = true)))
        val scratch2 = tmp.newFolder()
        val f = file(onlyZip)
        ExportZip(scratch2, ImportBudget()).use { z ->
            assertThat(z.sniff(f, "x!/")).isTrue()
            assertThat(scratch2.listFiles().orEmpty()).hasLength(1)
            assertThat(z.entries(f, "x!/").map { it.name }).containsExactly("x!/my_spotify_data.zip!/Spotify Extended Streaming History/Streaming_History_Audio_2024.json")
            assertThat(scratch2.listFiles().orEmpty()).hasLength(1)
        }
        // Closed: its scratch file goes
        assertThat(scratch2.listFiles().orEmpty()).isEmpty()
        // Names that aren't an export's, inside and out
        val decoy = TestZip.build(listOf(TestZip.entry("photos/a.jpg", "jpg"), TestZip.Entry("more.zip", TestZip.build(listOf(TestZip.entry("b.txt", "b"))))))
        ExportZip(tmp.newFolder(), ImportBudget()).use { z -> assertThat(z.sniff(file(decoy), "x!/")).isFalse() }
    }

    private companion object {
        /** 2026-10-23T12:00:00Z. */
        const val NOW = 1_792_756_800_000L
    }
}
