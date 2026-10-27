package com.stash.feature.record.imports

import com.google.common.truth.Truth.assertThat
import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.importer.ArchiveListens
import com.stash.feature.record.importer.ImportProgress
import com.stash.feature.record.importer.KeptPlay
import com.stash.feature.record.importer.MemoryArchive
import com.stash.feature.record.importer.RecordArchiveStore
import com.stash.feature.record.importer.SpotifyImporter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Bring your Spotify end to end, apart from Android: the files brought, read into a preview that writes nothing, then written
 * with an import record; the same files again saying nothing new; Remove import; a shared file looked into first; a commit the
 * app's death interrupted, taken up again from the copies it kept (an Account play left beside its Extended twin among them);
 * the system stopping the job (it goes on) against the person's Cancel (it ends); and every failure ending at Failed, with the
 * card's "didn't finish" and a notification when something went wrong.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordImportsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun resource(name: String): File = File(requireNotNull(javaClass.getResource("/spotify-export/$name")).toURI())

    private fun source(n: String) = resource(n).let { f -> IntakeSource(n, f.length()) { f.inputStream() } }

    /** The fixture's two zips: Extended streaming history, then Account data. */
    private fun fixture() = listOf(source(EXTENDED), source(ACCOUNT))

    private val decoy: IntakeSource by lazy {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("notes/packing-list.txt")); z.write("socks".toByteArray()); z.closeEntry() }
        }.toByteArray()
        IntakeSource("holiday_photos.zip", bytes.size.toLong()) { bytes.inputStream() }
    }

    private class Notices : ImportNotices {
        var shown = 0
        var cleared = 0
        override fun unfinished() {
            shown++
        }

        override fun clear() {
            cleared++
        }
    }

    /** An archive whose writes wait on [gate] from the [from]th on (a commit under way when the job is stopped). */
    private class GatedArchive(val inner: MemoryArchive, private val from: Int, private val gate: CompletableDeferred<Unit>) : RecordArchiveStore by inner {
        var applies = 0
        override suspend fun apply(add: List<ArchivePlay>, drop: List<String>) {
            if (++applies >= from) gate.await()
            inner.apply(add, drop)
        }
    }

    /** An archive whose writes run the phone out of memory. */
    private class OutOfMemoryArchive(val inner: MemoryArchive = MemoryArchive()) : RecordArchiveStore by inner {
        override suspend fun apply(add: List<ArchivePlay>, drop: List<String>) = throw OutOfMemoryError("test")
    }

    private inner class Rig(
        val test: TestScope,
        val archive: RecordArchiveStore = MemoryArchive(),
        val prefs: MemoryPrefs = MemoryPrefs(),
        val files: File = tmp.newFolder(),
        /** False: the job is started but never runs (an import that is still running). */
        val runs: Boolean = true,
        val scratch: File = tmp.newFolder(),
        alive: Boolean = false,
        intake: ImportIntake = ImportIntake(files),
        /** False: the person's Cancel reaches the job only later (WorkManager's cancel is asynchronous). */
        private val cancelStops: Boolean = true,
    ) {
        val store = RecordImportStore(prefs)
        val notices = Notices()
        var launches = 0
        var cancels = 0
        val jobs = mutableListOf<Job>()

        /** Each step's progress, as the job's notification hears it. */
        var onProgress: (ImportProgress) -> Unit = {}
        lateinit var imports: RecordImports
        private val dispatcher = StandardTestDispatcher(test.testScheduler)

        init {
            imports = RecordImports(
                store = store,
                archive = archive,
                ownSpan = { null to null },
                intake = intake,
                scratch = scratch,
                sourceOf = { error("no uris here") },
                launch = {
                    launches++
                    if (runs) rerun()
                },
                cancelJob = {
                    cancels++
                    if (cancelStops) stop()
                },
                jobAlive = { alive },
                scope = CoroutineScope(test.backgroundScope.coroutineContext + dispatcher),
                notices = notices,
                now = { NOW },
                zone = ZoneOffset.UTC,
                io = dispatcher,
            )
        }

        /** The job runs (again): WorkManager's run of the unique work. */
        fun rerun() {
            jobs += test.backgroundScope.launch(dispatcher) { imports.runNext { onProgress(it) } }
        }

        /** The job is stopped: by the system, when nobody called [RecordImports.cancel]. */
        fun stop() = jobs.forEach { it.cancel() }

        fun copies() = files.listFiles().orEmpty().toList()
    }

    @Test fun `the preview writes nothing, the import writes it all and keeps a record`() = runTest {
        val rig = Rig(this)
        val memory = rig.archive as MemoryArchive
        val changes = mutableListOf<Unit>()
        backgroundScope.launch { rig.imports.archiveChanged.collect { changes += it } }
        rig.imports.bringSources(fixture(), shared = false)
        assertThat(rig.imports.state.value).isEqualTo(ImportState.Working(ImportProgress.Open))
        runCurrent()

        val preview = (rig.imports.state.value as ImportState.Preview).preview
        assertThat(preview.plays).isEqualTo(6_086)
        assertThat(preview.same).isEqualTo(5)
        assertThat(memory.plays).isEmpty()
        assertThat(rig.store.info.value.record).isNull()

        rig.imports.confirm()
        runCurrent()

        assertThat(rig.imports.state.value).isEqualTo(ImportState.Done(listens = 6_086, recordPlays = 6_086))
        assertThat(memory.plays).hasSize(6_814)
        val record = rig.store.info.value.record!!
        assertThat(listOf(record.imports, record.plays, record.bySource?.spotify, record.firstAt, record.lastAt)).containsExactly(1, 6_086, 6_086, NOW, NOW).inOrder()
        assertThat(record.first).isEqualTo(1_609_498_043_414) // the archive's first listen (expected.json's preview first)
        assertThat(rig.store.pending).isNull()
        assertThat(changes).hasSize(1)
        // the copies go once written
        assertThat(rig.copies()).isEmpty()
        // Settings sees it
        assertThat(rig.imports.settings.value.imported).isTrue()
        assertThat(rig.imports.settings.value.spotifyPlays).isEqualTo(6_086)
        assertThat(rig.notices.shown).isEqualTo(0)
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
        assertThat((rig.archive as MemoryArchive).plays).hasSize(6_814)
        assertThat(rig.store.info.value.record!!.imports).isEqualTo(1)
        // An answer about the files, not something that went wrong: no "didn't finish"
        assertThat(rig.store.info.value.unfinished).isNull()
        assertThat(rig.notices.shown).isEqualTo(0)
    }

    @Test fun `account data first, then the extended zip - the second adds the rest and takes the account twins' place`() = runTest {
        val rig = Rig(this)
        val memory = rig.archive as MemoryArchive
        rig.imports.bringSources(listOf(source(ACCOUNT)), shared = false)
        runCurrent()
        rig.imports.confirm()
        runCurrent()
        val afterAccount = memory.plays.size

        rig.imports.bringSources(listOf(source(EXTENDED)), shared = false)
        runCurrent()
        rig.imports.confirm()
        runCurrent()

        assertThat(afterAccount).isLessThan(6_814)
        assertThat(memory.plays).hasSize(6_814)
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

        assertThat((rig.archive as MemoryArchive).plays).isEmpty()
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
        assertThat(rig.imports.bringSources(fixture(), shared = true)).isNull()
        runCurrent()
        assertThat(notes).containsExactly(ImportNote.BUSY)
        assertThat(rig.launches).isEqualTo(1)
    }

    @Test fun `files are copied at once, before the job runs (a share's grant can lapse before it does)`() = runTest {
        val rig = Rig(this, runs = false)
        val copy = rig.imports.bringSources(fixture(), shared = true)!!
        runCurrent()
        assertThat(copy.isCompleted).isTrue()
        assertThat(rig.copies()).hasSize(2)
        assertThat(rig.launches).isEqualTo(1)
        assertThat(rig.imports.sheetOpen.value).isTrue()
    }

    @Test fun `a shared file that isn't an export - the sheet, open at once, says so, and nothing is kept`() = runTest {
        val rig = Rig(this)
        val notes = mutableListOf<ImportNote>()
        backgroundScope.launch { rig.imports.notes.collect { notes += it } }

        rig.imports.bringSources(listOf(decoy), shared = true)
        assertThat(rig.imports.sheetOpen.value).isTrue()
        runCurrent()

        assertThat(rig.imports.state.value).isEqualTo(ImportState.Failed(ImportFailure.NOT_EXPORT))
        assertThat(notes).isEmpty()
        assertThat(rig.copies()).isEmpty()
        assertThat(rig.store.info.value.unfinished).isNull()
    }

    @Test fun `a shared file that isn't an export, brought to a preview - the preview stays, and a toast says why`() = runTest {
        val rig = Rig(this)
        val notes = mutableListOf<ImportNote>()
        backgroundScope.launch { rig.imports.notes.collect { notes += it } }
        rig.imports.bringSources(fixture(), shared = false)
        runCurrent()
        val before = rig.imports.state.value as ImportState.Preview

        rig.imports.bringSources(listOf(decoy), shared = true)
        runCurrent()

        assertThat(rig.imports.state.value).isEqualTo(before)
        assertThat(notes).containsExactly(ImportNote.NOT_AN_EXPORT)
        assertThat(rig.copies()).hasSize(2)
        rig.imports.confirm()
        runCurrent()
        assertThat((rig.archive as MemoryArchive).plays).hasSize(6_814)
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
        resource(EXTENDED).copyTo(File(files, "0__$EXTENDED"))
        resource(ACCOUNT).copyTo(File(files, "1__$ACCOUNT"))
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

    @Test fun `a half-written import is finished from its copies - an Account play left beside its Extended twin goes`() = runTest {
        // What the archive is after the Account zip, and after the Account zip then the Extended one, written whole
        val account = Rig(this)
        account.imports.bringSources(listOf(source(ACCOUNT)), shared = false)
        runCurrent()
        account.imports.confirm()
        runCurrent()
        val afterAccount = (account.archive as MemoryArchive).plays.values.toList()
        val clean = Rig(this)
        for (n in listOf(ACCOUNT, EXTENDED)) {
            clean.imports.bringSources(listOf(source(n)), shared = false)
            runCurrent()
            clean.imports.confirm()
            runCurrent()
        }
        val cleanIds = (clean.archive as MemoryArchive).plays.keys.toSet()
        val replaced = afterAccount.filter { it.id !in cleanIds }
        assertThat(replaced).isNotEmpty() // Account plays an Extended play takes the place of
        val extendedOnly = (clean.archive as MemoryArchive).plays.values.filter { p -> afterAccount.none { it.id == p.id } }

        // The Extended zip's commit stopped (the app died) after some of its adds, before any of its drops (adds go first)
        for (written in listOf(extendedOnly.size / 2, extendedOnly.size)) {
            val archive = MemoryArchive()
            archive.apply(afterAccount + extendedOnly.take(written), emptyList())
            val prefs = MemoryPrefs()
            RecordImportStore(prefs).apply {
                setRecord(account.store.info.value.record!!)
                setPending(NOW - 1_000)
            }
            val files = tmp.newFolder()
            resource(EXTENDED).copyTo(File(files, "0__$EXTENDED"))
            assertThat(archive.plays.keys.containsAll(replaced.map { it.id })).isTrue()

            val rig = Rig(this, archive = archive, prefs = prefs, files = files)
            runCurrent()

            assertThat(archive.plays.keys).isEqualTo(cleanIds)
            assertThat(rig.store.pending).isNull()
            assertThat(rig.store.info.value.record!!.imports).isEqualTo(2)
            assertThat(rig.store.info.value.record!!.plays).isEqualTo(6_086)
            assertThat(files.listFiles().orEmpty()).isEmpty()
        }
    }

    @Test fun `a read the app's death interrupted is done again from the copies, to its preview`() = runTest {
        val files = tmp.newFolder()
        resource(EXTENDED).copyTo(File(files, "0__$EXTENDED"))
        resource(ACCOUNT).copyTo(File(files, "1__$ACCOUNT"))
        // The job is still queued (WorkManager runs it again once the app is back): its copies are kept
        val rig = Rig(this, files = files, alive = true)
        runCurrent()
        assertThat(rig.copies()).hasSize(2)

        rig.rerun()
        runCurrent()

        assertThat((rig.imports.state.value as ImportState.Preview).preview.plays).isEqualTo(6_086)
    }

    @Test fun `the system stopping a save - pending and the copies stay, and the job's next run finishes it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val memory = MemoryArchive()
        val rig = Rig(this, archive = GatedArchive(memory, from = 2, gate = gate))
        rig.imports.bringSources(fixture(), shared = false)
        runCurrent()
        rig.imports.confirm()
        runCurrent()
        assertThat(rig.imports.state.value).isInstanceOf(ImportState.Working::class.java)
        assertThat(memory.plays).hasSize(5_000)

        rig.stop()
        runCurrent()

        assertThat(rig.store.pending).isNotNull()
        assertThat(rig.copies()).hasSize(2)
        assertThat(rig.store.stops).isEqualTo(1)
        assertThat(rig.imports.state.value).isInstanceOf(ImportState.Working::class.java)
        assertThat(rig.cancels).isEqualTo(0)

        gate.complete(Unit)
        rig.rerun()
        runCurrent()

        assertThat(rig.imports.state.value).isInstanceOf(ImportState.Done::class.java)
        assertThat(memory.plays).hasSize(6_814)
        assertThat(rig.store.pending).isNull()
        assertThat(rig.store.stops).isEqualTo(0)
        assertThat(rig.copies()).isEmpty()
        assertThat(rig.store.info.value.record!!.plays).isEqualTo(6_086)
    }

    @Test fun `the person's cancel mid-save - nothing goes on, the copies go, what was written keeps its record`() = runTest {
        val memory = MemoryArchive()
        val rig = Rig(this, archive = GatedArchive(memory, from = 2, gate = CompletableDeferred()))
        rig.imports.bringSources(fixture(), shared = false)
        runCurrent()
        rig.imports.confirm()
        runCurrent()

        rig.imports.cancel()
        assertThat(rig.imports.state.value).isEqualTo(ImportState.Idle)
        runCurrent()

        assertThat(rig.cancels).isEqualTo(1)
        assertThat(rig.store.pending).isNull()
        assertThat(rig.copies()).isEmpty()
        assertThat(memory.plays).hasSize(5_000)
        // The plays written are counted once, with their record
        assertThat(rig.store.info.value.record!!.plays).isEqualTo(memory.plays.values.count { it.ms >= 30_000 })
        assertThat(rig.store.info.value.unfinished).isNull()
        // A run of the job after that (none should come) finds nothing to take up
        rig.rerun()
        runCurrent()
        assertThat(memory.plays).hasSize(5_000)
        assertThat(rig.imports.state.value).isEqualTo(ImportState.Idle)
    }

    @Test fun `the person's cancel while the files are copied or waiting to be read - nothing is kept, nothing runs`() = runTest {
        val rig = Rig(this)
        rig.imports.bringSources(fixture(), shared = false)
        rig.imports.cancel()
        runCurrent()
        assertThat(rig.imports.state.value).isEqualTo(ImportState.Idle)
        assertThat(rig.copies()).isEmpty()
        assertThat(rig.launches).isEqualTo(0)

        val queued = Rig(this, runs = false)
        queued.imports.bringSources(fixture(), shared = false)
        runCurrent()
        assertThat(queued.launches).isEqualTo(1)
        queued.imports.cancel()
        runCurrent()
        assertThat(queued.cancels).isEqualTo(1)
        assertThat(queued.imports.state.value).isEqualTo(ImportState.Idle)
        assertThat(queued.copies()).isEmpty()
        // A new import after a cancel is its own: the cancel doesn't reach it
        queued.imports.bringSources(fixture(), shared = false)
        runCurrent()
        queued.rerun()
        runCurrent()
        assertThat(queued.imports.state.value).isInstanceOf(ImportState.Preview::class.java)
    }

    @Test fun `a cancel while the merge is planned (no checkpoint there) - its preview never shows, the sheet stays at its start`() = runTest {
        // Planning the merge reads the archive with no checkpoint: here it waits on a gate nothing can cancel, as a long plan would
        val gate = CompletableDeferred<Unit>()
        val memory = MemoryArchive()
        val archive = object : RecordArchiveStore by memory {
            override suspend fun forEachPage(block: (List<KeptPlay>) -> Unit) {
                withContext(NonCancellable) { gate.await() }
                memory.forEachPage(block)
            }
        }
        val rig = Rig(this, archive = archive)
        rig.imports.bringSources(fixture(), shared = false)
        runCurrent()
        assertThat(rig.imports.state.value).isInstanceOf(ImportState.Working::class.java)

        rig.imports.cancel()
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertThat(rig.imports.state.value).isEqualTo(ImportState.Idle)
        assertThat(rig.copies()).isEmpty()
        // and the next import is its own
        rig.imports.bringSources(fixture(), shared = false)
        runCurrent()
        assertThat(rig.imports.state.value).isInstanceOf(ImportState.Preview::class.java)
    }

    @Test fun `stopped by the system again and again, the import is given up as unfinished, its copies kept for a try again`() = runTest {
        val memory = MemoryArchive()
        val rig = Rig(this, archive = GatedArchive(memory, from = 2, gate = CompletableDeferred()))
        rig.imports.bringSources(fixture(), shared = false)
        runCurrent()
        rig.imports.confirm()
        runCurrent()
        repeat(RecordImports.MAX_STOPS - 1) {
            rig.stop()
            runCurrent()
            assertThat(rig.store.pending).isNotNull()
            rig.rerun()
            runCurrent()
        }
        rig.stop()
        runCurrent()

        assertThat(rig.imports.state.value).isEqualTo(ImportState.Failed(ImportFailure.OTHER, saving = true))
        assertThat(rig.store.pending).isNull()
        assertThat(rig.store.info.value.record).isNotNull()
        assertThat(rig.store.info.value.unfinished).isEqualTo(NOW)
        assertThat(rig.notices.shown).isEqualTo(1)
        assertThat(rig.copies()).hasSize(2)
    }

    @Test fun `an OutOfMemoryError while saving ends at Failed, never stuck at Working - and Try again reads the kept copies`() = runTest {
        val rig = Rig(this, archive = OutOfMemoryArchive())
        rig.imports.bringSources(fixture(), shared = false)
        runCurrent()
        rig.imports.confirm()
        runCurrent()

        assertThat(rig.imports.state.value).isEqualTo(ImportState.Failed(ImportFailure.OTHER, saving = true))
        assertThat(rig.store.pending).isNull()
        assertThat(rig.store.info.value.unfinished).isEqualTo(NOW)
        assertThat(rig.notices.shown).isEqualTo(1)
        assertThat(rig.copies()).hasSize(2)

        rig.imports.again()
        runCurrent()
        assertThat(rig.imports.state.value).isInstanceOf(ImportState.Preview::class.java)
        assertThat(rig.store.info.value.unfinished).isNull()
        assertThat(rig.notices.cleared).isAtLeast(1)
    }

    @Test fun `files past the import's ceiling are refused whole - too big, nothing kept, the card says it didn't finish`() = runTest {
        val files = tmp.newFolder()
        val rig = Rig(this, files = files, intake = ImportIntake(files, maxTotal = 1_000))
        rig.imports.bringSources(fixture(), shared = false)
        runCurrent()

        assertThat(rig.imports.state.value).isEqualTo(ImportState.Failed(ImportFailure.TOO_BIG))
        assertThat(rig.copies()).isEmpty()
        assertThat(rig.launches).isEqualTo(0)
        assertThat(rig.store.info.value.unfinished).isEqualTo(NOW)
        assertThat(rig.notices.shown).isEqualTo(1)
    }

    @Test fun `an import that didn't finish waits for the person across a restart, and its x lets it go`() = runTest {
        val prefs = MemoryPrefs()
        val files = tmp.newFolder()
        resource(EXTENDED).copyTo(File(files, "0__$EXTENDED"))
        RecordImportStore(prefs).setUnfinished(NOW - 1_000)

        val rig = Rig(this, prefs = prefs, files = files)
        runCurrent()
        // Kept at start (no job is running, but the copies wait for a Try again)
        assertThat(rig.copies()).hasSize(1)
        assertThat(rig.launches).isEqualTo(0)

        rig.imports.dismissUnfinished()
        runCurrent()
        assertThat(rig.store.info.value.unfinished).isNull()
        assertThat(rig.copies()).isEmpty()
        assertThat(rig.notices.cleared).isAtLeast(1)
    }

    @Test fun `scratch a dead job left is cleared at start when no job runs, kept while one does`() = runTest {
        val scratch = tmp.newFolder()
        File(scratch, "nested123.zip").writeText("left behind")
        Rig(this, scratch = scratch)
        runCurrent()
        assertThat(scratch.exists() && scratch.listFiles().orEmpty().isNotEmpty()).isFalse()

        val busy = tmp.newFolder()
        File(busy, "nested456.zip").writeText("in use")
        Rig(this, scratch = busy, alive = true)
        runCurrent()
        assertThat(busy.listFiles().orEmpty()).hasLength(1)
    }

    @Test fun `plays kept with no record (a restored library) get one at start`() = runTest {
        val archive = MemoryArchive()
        archive.apply(listOf(ArchivePlay("a|b|1", "A", "B", s = 1_700_000_000_000, ms = 60_000, f = 34)), emptyList())
        val rig = Rig(this, archive = archive)
        runCurrent()
        assertThat(rig.store.info.value.record!!.plays).isEqualTo(1)
        assertThat(rig.launches).isEqualTo(0)
    }

    // ── round 3 ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `a commit finishing after a Cancel never deletes the copies of the import brought since`() = runTest {
        // WorkManager's cancel reaches the job late: the commit, past its last write, is still putting its record together
        val gate = CompletableDeferred<Unit>()
        val memory = MemoryArchive()
        val archive = object : RecordArchiveStore by memory {
            override suspend fun listens(): ArchiveListens {
                withContext(NonCancellable) { gate.await() }
                return memory.listens()
            }
        }
        val rig = Rig(this, archive = archive, cancelStops = false)
        rig.imports.bringSources(listOf(source(ACCOUNT)), shared = false)
        runCurrent()
        rig.imports.confirm()
        runCurrent()
        rig.imports.cancel()
        runCurrent()
        // A new import, brought and copied while the old commit hasn't finished
        rig.imports.bringSources(listOf(source(EXTENDED)), shared = false)
        runCurrent()

        gate.complete(Unit)
        runCurrent()

        assertThat(rig.copies().map { it.name.substringAfter("__") }).contains(EXTENDED)
        assertThat(rig.imports.state.value).isInstanceOf(ImportState.Preview::class.java)
    }

    @Test fun `a Cancel during an import under 5,000 plays writes none of it`() = runTest {
        val memory = MemoryArchive()
        // The job hears of the Cancel late (WorkManager's is asynchronous): the import's own checkpoint stops it
        val rig = Rig(this, archive = memory, cancelStops = false)
        rig.imports.bringSources(listOf(source(ACCOUNT)), shared = false)
        runCurrent()
        assertThat((rig.imports.state.value as ImportState.Preview).preview.plays).isLessThan(SpotifyImporter.BATCH)
        rig.onProgress = { if (it is ImportProgress.Write) rig.imports.cancel() }

        rig.imports.confirm()
        runCurrent()

        assertThat(memory.plays).isEmpty()
        assertThat(rig.imports.state.value).isEqualTo(ImportState.Idle)
        assertThat(rig.store.pending).isNull()
        assertThat(rig.store.info.value.record).isNull()
    }

    @Test fun `a Cancel while a system stop is being counted - the step is neither queued again nor taken up by the job's rerun`() = runTest {
        val prefs = MemoryPrefs()
        val archive = GatedArchive(MemoryArchive(), from = 2, gate = CompletableDeferred())
        // WorkManager's cancel is asynchronous: the rerun can start before it lands
        val rig = Rig(this, archive = archive, prefs = prefs, cancelStops = false)
        rig.imports.bringSources(fixture(), shared = false)
        runCurrent()
        rig.imports.confirm()
        runCurrent()
        val applies = archive.applies
        // The Cancel lands just as the stop is written down, with WorkManager's rerun of the stopped job already on its way
        prefs.onCommit = { keys ->
            if ("import_stops" in keys) {
                prefs.onCommit = null
                rig.rerun()
                rig.imports.cancel()
            }
        }

        rig.stop()
        runCurrent()

        // Nothing more is written for the cancelled import, and the next files are taken
        assertThat(archive.applies).isEqualTo(applies)
        assertThat(rig.store.pending).isNull()
        assertThat(rig.imports.state.value).isEqualTo(ImportState.Idle)
        assertThat(rig.imports.bringSources(listOf(source(ACCOUNT)), shared = false)).isNotNull()
    }

    @Test fun `after the app's death with a save under way, it shows as one at once and new files wait for it`() = runTest {
        val prefs = MemoryPrefs()
        val files = tmp.newFolder()
        resource(EXTENDED).copyTo(File(files, "0__$EXTENDED"))
        resource(ACCOUNT).copyTo(File(files, "1__$ACCOUNT"))
        RecordImportStore(prefs).setPending(NOW - 1_000)
        val memory = MemoryArchive()
        // The job is still queued (WorkManager runs it again once the app is back)
        val rig = Rig(this, archive = memory, prefs = prefs, files = files, runs = false, alive = true)

        assertThat(rig.imports.state.value).isEqualTo(ImportState.Working(ImportProgress.Open, saving = true))
        runCurrent()
        assertThat(rig.imports.bringSources(listOf(source(ACCOUNT)), shared = false)).isNull()

        rig.rerun()
        runCurrent()
        assertThat(rig.imports.state.value).isInstanceOf(ImportState.Done::class.java)
        assertThat(memory.plays).hasSize(6_814)
        assertThat(rig.copies()).isEmpty()
    }

    @Test fun `after the app's death mid-read with its job still to run, it shows as under way and new files wait for it`() = runTest {
        val files = tmp.newFolder()
        resource(EXTENDED).copyTo(File(files, "0__$EXTENDED"))
        resource(ACCOUNT).copyTo(File(files, "1__$ACCOUNT"))
        val rig = Rig(this, files = files, runs = false, alive = true)
        runCurrent()

        assertThat(rig.imports.state.value).isEqualTo(ImportState.Working(ImportProgress.Open))
        assertThat(rig.imports.bringSources(listOf(decoy), shared = false)).isNull()

        rig.rerun()
        runCurrent()
        assertThat((rig.imports.state.value as ImportState.Preview).preview.plays).isEqualTo(6_086)
    }

    @Test fun `more files than an export has are refused whole - too big, nothing copied`() = runTest {
        val rig = Rig(this)
        val many = (0..RecordImports.MAX_FILES).map { i -> IntakeSource("StreamingHistory_music_$i.json", 2) { "[]".byteInputStream() } }

        assertThat(rig.imports.bringSources(many, shared = true)).isNull()
        runCurrent()

        assertThat(rig.imports.state.value).isEqualTo(ImportState.Failed(ImportFailure.TOO_BIG))
        assertThat(rig.imports.sheetOpen.value).isTrue()
        assertThat(rig.copies()).isEmpty()
        assertThat(rig.launches).isEqualTo(0)
        assertThat(rig.notices.shown).isEqualTo(1)
        // At the cap, taken
        val atCap = many.take(RecordImports.MAX_FILES)
        rig.imports.reset()
        assertThat(rig.imports.bringSources(atCap, shared = false)).isNotNull()
    }

    @Test fun `a provider's name and size are asked when the copy runs, never on the caller's thread`() = runTest {
        var asked = 0
        val src = IntakeSource(describe = { asked++; EXTENDED to null }, open = { resource(EXTENDED).inputStream() })
        val rig = Rig(this, runs = false)

        rig.imports.bringSources(listOf(src), shared = false)
        assertThat(asked).isEqualTo(0)
        runCurrent()

        assertThat(asked).isEqualTo(1)
        assertThat(rig.copies()).hasSize(1)
    }

    @Test fun `an Error while a provider is read ends at Failed and leaves no part behind`() = runTest {
        val broken = IntakeSource(EXTENDED, null) {
            object : InputStream() {
                override fun read(): Int = throw OutOfMemoryError("test")
                override fun read(b: ByteArray, off: Int, len: Int): Int = throw OutOfMemoryError("test")
            }
        }
        val rig = Rig(this)

        rig.imports.bringSources(listOf(broken), shared = false)
        runCurrent()

        assertThat(rig.imports.state.value).isEqualTo(ImportState.Failed(ImportFailure.OTHER))
        assertThat(rig.files.listFiles().orEmpty()).isEmpty()
    }

    private companion object {
        /** 2026-10-23T12:00:00Z, the fixture's now. */
        const val NOW = 1_792_756_800_000L
        const val EXTENDED = "my_spotify_data.zip"
        const val ACCOUNT = "my_spotify_data (1).zip"
    }
}
