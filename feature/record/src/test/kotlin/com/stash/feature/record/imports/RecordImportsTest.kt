package com.stash.feature.record.imports

import com.google.common.truth.Truth.assertThat
import com.stash.feature.record.importer.ImportProgress
import com.stash.feature.record.importer.MemoryArchive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Bring your Spotify end to end, apart from Android: the files brought, read into a preview that writes nothing, then written
 * with an import record; the same files again saying nothing new; Remove import; a shared file looked into first; and a commit
 * the app's death interrupted, taken up again from the copies it kept.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordImportsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun resource(name: String): File = File(requireNotNull(javaClass.getResource("/spotify-export/$name")).toURI())

    private fun fixture() = listOf("my_spotify_data.zip", "my_spotify_data (1).zip").map { n -> resource(n).let { f -> IntakeSource(n, f.length()) { f.inputStream() } } }

    private val decoy: IntakeSource by lazy {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("notes/packing-list.txt")); z.write("socks".toByteArray()); z.closeEntry() }
        }.toByteArray()
        IntakeSource("holiday_photos.zip", bytes.size.toLong()) { bytes.inputStream() }
    }

    private inner class Rig(
        val test: TestScope,
        val archive: MemoryArchive = MemoryArchive(),
        val prefs: MemoryPrefs = MemoryPrefs(),
        val files: File = tmp.newFolder(),
        /** False: the job is started but never runs (an import that is still running). */
        val runs: Boolean = true,
    ) {
        val store = RecordImportStore(prefs)
        var launches = 0
        lateinit var imports: RecordImports
        private val dispatcher = StandardTestDispatcher(test.testScheduler)

        init {
            imports = RecordImports(
                store = store,
                archive = archive,
                ownSpan = { null to null },
                intake = ImportIntake(files),
                scratch = tmp.newFolder(),
                sourceOf = { error("no uris here") },
                launch = {
                    launches++
                    if (runs) test.backgroundScope.launch(dispatcher) { imports.runNext() }
                },
                jobAlive = { false },
                scope = CoroutineScope(test.backgroundScope.coroutineContext + dispatcher),
                now = { NOW },
                zone = ZoneOffset.UTC,
                io = dispatcher,
            )
        }
    }

    @Test fun `the preview writes nothing, the import writes it all and keeps a record`() = runTest {
        val rig = Rig(this)
        val changes = mutableListOf<Unit>()
        backgroundScope.launch { rig.imports.archiveChanged.collect { changes += it } }
        rig.imports.bringSources(fixture(), shared = false)
        assertThat(rig.imports.state.value).isEqualTo(ImportState.Working(ImportProgress.Open))
        runCurrent()

        val preview = (rig.imports.state.value as ImportState.Preview).preview
        assertThat(preview.plays).isEqualTo(6_086)
        assertThat(preview.same).isEqualTo(5)
        assertThat(rig.archive.plays).isEmpty()
        assertThat(rig.store.info.value.record).isNull()

        rig.imports.confirm()
        runCurrent()

        assertThat(rig.imports.state.value).isEqualTo(ImportState.Done(listens = 6_086, recordPlays = 6_086))
        assertThat(rig.archive.plays).hasSize(6_814)
        val record = rig.store.info.value.record!!
        assertThat(listOf(record.imports, record.plays, record.bySource?.spotify, record.firstAt, record.lastAt)).containsExactly(1, 6_086, 6_086, NOW, NOW).inOrder()
        assertThat(record.first).isEqualTo(1_609_498_043_414) // the archive's first listen (expected.json's preview first)
        assertThat(rig.store.pending).isNull()
        assertThat(changes).hasSize(1)
        // the copies go once written
        assertThat(rig.files.listFiles().orEmpty()).isEmpty()
        // Settings sees it
        assertThat(rig.imports.settings.value.imported).isTrue()
        assertThat(rig.imports.settings.value.spotifyPlays).isEqualTo(6_086)
    }

    @Test fun `the same files again - nothing new, nothing written`() = runTest {
        val rig = Rig(this)
        rig.imports.bringSources(fixture(), shared = false)
        runCurrent()
        rig.imports.confirm()
        runCurrent()
        rig.imports.reset()

        rig.imports.bringSources(fixture(), shared = false)
        runCurrent()

        assertThat(rig.imports.state.value).isEqualTo(ImportState.Failed(ImportFailure.NOTHING_NEW))
        assertThat(rig.archive.plays).hasSize(6_814)
        assertThat(rig.store.info.value.record!!.imports).isEqualTo(1)
    }

    @Test fun `account data first, then the extended zip - the second adds the rest and takes the account twins' place`() = runTest {
        val rig = Rig(this)
        val (extended, account) = fixture()
        rig.imports.bringSources(listOf(account), shared = false)
        runCurrent()
        rig.imports.confirm()
        runCurrent()
        val afterAccount = rig.archive.plays.size

        rig.imports.bringSources(listOf(extended), shared = false)
        runCurrent()
        rig.imports.confirm()
        runCurrent()

        assertThat(afterAccount).isLessThan(6_814)
        assertThat(rig.archive.plays).hasSize(6_814)
        assertThat(rig.store.info.value.record!!.imports).isEqualTo(2)
        assertThat(rig.store.info.value.record!!.plays).isEqualTo(6_086)
    }

    @Test fun `remove clears the plays and the record, keeps when Spotify was asked and the card's x`() = runTest {
        val rig = Rig(this)
        rig.imports.markAsked()
        rig.imports.hideInvite()
        rig.imports.bringSources(fixture(), shared = false)
        runCurrent()
        rig.imports.confirm()
        runCurrent()

        assertThat(rig.imports.removeImport()).isTrue()

        assertThat(rig.archive.plays).isEmpty()
        assertThat(rig.store.info.value.record).isNull()
        assertThat(rig.store.info.value.asked).isEqualTo(NOW)
        assertThat(rig.store.info.value.inviteHidden).isTrue()
        assertThat(rig.imports.state.value).isEqualTo(ImportState.Idle)
        rig.imports.showInvite()
        assertThat(rig.store.info.value.inviteHidden).isFalse()
    }

    @Test fun `remove is refused while an import runs, and files brought meanwhile wait`() = runTest {
        val rig = Rig(this, runs = false)
        val notes = mutableListOf<ImportNote>()
        backgroundScope.launch { rig.imports.notes.collect { notes += it } }
        rig.imports.bringSources(fixture(), shared = false)
        runCurrent()

        assertThat(rig.imports.state.value).isInstanceOf(ImportState.Working::class.java)
        assertThat(rig.imports.removeImport()).isFalse()
        rig.imports.bringSources(fixture(), shared = true)
        runCurrent()
        assertThat(notes).containsExactly(ImportNote.BUSY)
        assertThat(rig.launches).isEqualTo(1)
    }

    @Test fun `a shared file that isn't an export is told so, and nothing else happens`() = runTest {
        val rig = Rig(this)
        val notes = mutableListOf<ImportNote>()
        backgroundScope.launch { rig.imports.notes.collect { notes += it } }

        rig.imports.bringSources(listOf(decoy), shared = true)
        runCurrent()

        assertThat(notes).containsExactly(ImportNote.NOT_AN_EXPORT)
        assertThat(rig.imports.state.value).isEqualTo(ImportState.Idle)
        assertThat(rig.imports.sheetOpen.value).isFalse()
        assertThat(rig.files.listFiles().orEmpty()).isEmpty()
    }

    @Test fun `a shared export opens the sheet at its preview`() = runTest {
        val rig = Rig(this)
        rig.imports.bringSources(fixture(), shared = true)
        runCurrent()
        assertThat(rig.imports.sheetOpen.value).isTrue()
        assertThat((rig.imports.state.value as ImportState.Preview).preview.plays).isEqualTo(6_086)
    }

    @Test fun `a file picked that isn't an export says so in the sheet`() = runTest {
        val rig = Rig(this)
        rig.imports.bringSources(listOf(decoy), shared = false)
        runCurrent()
        assertThat(rig.imports.state.value).isEqualTo(ImportState.Failed(ImportFailure.NOT_EXPORT))
    }

    @Test fun `a commit the app's death interrupted is taken up again from the copies it kept`() = runTest {
        // The app died mid-write: pending set, the copies kept, a few plays written
        val prefs = MemoryPrefs()
        val files = tmp.newFolder()
        resource("my_spotify_data.zip").copyTo(File(files, "0__my_spotify_data.zip"))
        resource("my_spotify_data (1).zip").copyTo(File(files, "1__my_spotify_data (1).zip"))
        RecordImportStore(prefs).setPending(NOW - 1_000)
        val archive = MemoryArchive()

        val rig = Rig(this, archive = archive, prefs = prefs, files = files)
        runCurrent()

        assertThat(rig.launches).isEqualTo(1)
        assertThat(archive.plays).hasSize(6_814)
        assertThat(rig.store.pending).isNull()
        assertThat(rig.store.info.value.record!!.imports).isEqualTo(1)
        assertThat(files.listFiles().orEmpty()).isEmpty()
    }

    @Test fun `plays kept with no record (a restored library) get one at start`() = runTest {
        val archive = MemoryArchive()
        archive.apply(listOf(com.stash.feature.record.archive.ArchivePlay("a|b|1", "A", "B", s = 1_700_000_000_000, ms = 60_000, f = 34)), emptyList())
        val rig = Rig(this, archive = archive)
        runCurrent()
        assertThat(rig.store.info.value.record!!.plays).isEqualTo(1)
        assertThat(rig.launches).isEqualTo(0)
    }

    private companion object {
        /** 2026-10-23T12:00:00Z, the fixture's now. */
        const val NOW = 1_792_756_800_000L
    }
}
