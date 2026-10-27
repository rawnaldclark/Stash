package com.stash.feature.record.imports

import android.content.Context
import android.net.Uri
import android.util.Log
import com.stash.core.data.db.dao.RecordPlayDao
import com.stash.core.data.db.dao.WebLibraryExportDao
import com.stash.core.data.record.YourRecordImportState
import com.stash.core.data.record.YourRecordImports
import com.stash.feature.record.importer.ImportPreview
import com.stash.feature.record.importer.ImportProgress
import com.stash.feature.record.importer.ImportTooBig
import com.stash.feature.record.importer.NotAnExport
import com.stash.feature.record.importer.PickedFile
import com.stash.feature.record.importer.Prepared
import com.stash.feature.record.importer.RecordArchiveStore
import com.stash.feature.record.importer.SpotifyImporter
import com.stash.feature.record.importer.StorageFull
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext
import javax.inject.Inject
import javax.inject.Singleton

/** Why an import came to nothing (service.ts `SpotifyState` error reasons, and [NO_PLAYS] and [TOO_BIG], the app's own). */
enum class ImportFailure {
    /** None of the files is a file of a Spotify export. */
    NOT_EXPORT,

    /** An export, every play of it here already (spec §5.5: never called "not an export"). */
    NOTHING_NEW,

    /** An export with playlists or liked songs and no plays: those come in with a later update. */
    NO_PLAYS,

    /** A zip that wouldn't open, or files that couldn't be read. */
    UNREADABLE,

    /** No room on the phone. */
    STORAGE,

    /** Far more than any export holds (a zip bomb, or not an export at all): refused before it filled the phone. */
    TOO_BIG,

    OTHER;

    /** Something went wrong, rather than an answer about the files: the card says "Your import didn't finish", and a notification. */
    val unfinished: Boolean get() = this == UNREADABLE || this == STORAGE || this == TOO_BIG || this == OTHER
}

/** The import as the screens show it (service.ts `SpotifyState`). */
sealed interface ImportState {
    data object Idle : ImportState

    /** Opening, reading or saving; [saving] once plays are being written (a failure then is a failed save, tried again safely). */
    data class Working(val progress: ImportProgress, val saving: Boolean = false) : ImportState

    /** What the files would add, waiting for the person: nothing is written until they import. [files]: how many were brought. */
    data class Preview(val preview: ImportPreview, val files: Int) : ImportState

    data class Failed(val reason: ImportFailure, val saving: Boolean = false) : ImportState

    /** Written: [listens] new listens; [recordPlays], the record's listens in all. */
    data class Done(val listens: Int, val recordPlays: Int) : ImportState

    /** Opening, reading, saving, or a preview waiting: an import on its way (the Home card says so). */
    val busy: Boolean get() = this is Working || this is Preview
}

/** Something to tell the person wherever they are (a toast). */
enum class ImportNote {
    /** A file shared or opened with Stash, to add to a preview, that holds no Spotify export (the preview stays). */
    NOT_AN_EXPORT,

    /** Files brought while an import is running: it goes on, they wait. */
    BUSY,
}

/** The system stopped the import more often than it's worth running again. */
private class StoppedTooOften : Exception("stopped by the system too often")

/**
 * Bring your Spotify, the app's side (stash-player `src/lib/spotify/service.ts`): the import's state for the Home card, the
 * sheet and Settings, and the steps that change it. One flow, opened from three places: the Home card, the record's
 * `stash:import`, and a zip shared or opened with Stash ([bringShared]: the sheet opens at once and looks inside first).
 *
 * Files brought are copied into the app at once, in this process ([bringSources]): a file shared or opened with Stash can be
 * read only while the screen that received it lives. The reading and writing run in a foreground job ([RecordImportWorker]),
 * so they go on when the person leaves the screen or the app. Two steps, as on the web: a read works out what the files would
 * add, writing nothing, and the preview waits; [confirm] plans the merge again against the archive as it is then and writes
 * it. A commit and Remove import hold one lock. A commit marks `pending` before it writes.
 *
 * How an import ends early:
 * - The person's [cancel]: the job and any copy stop, the copies go, `pending` is cleared (what a save wrote keeps its
 *   record, put right). Each import has an epoch, so a cancel ends only the imports begun before it.
 * - The system stops the job (Android 15's dataSync budget, the 10-minute cap of a job that couldn't go foreground): `pending`
 *   and the copies stay and the step runs again when the job does, from where it was (after the app's death too); past
 *   [MAX_STOPS] stops it is given up as unfinished.
 * - A failure (any throwable, an OutOfMemoryError among them): Failed, never stuck at Working. When it's something that went
 *   wrong rather than an answer about the files, the copies stay for a Try again, the Home card says "Your import didn't
 *   finish" and a notification says so, until it's tried again or dismissed.
 */
@Singleton
class RecordImports internal constructor(
    private val store: RecordImportStore,
    private val archive: RecordArchiveStore,
    /** This phone's own first and last play (the import record's first and last listen take them in, as taste does). */
    private val ownSpan: suspend () -> Pair<Long?, Long?>,
    private val intake: ImportIntake,
    private val scratch: File,
    private val sourceOf: (Uri) -> IntakeSource,
    /** Starts the job that calls [runNext] (WorkManager; a direct call in tests). */
    private val launch: () -> Unit,
    /** Stops the job and drops any step queued behind it (the person's Cancel: WorkManager's cancelUniqueWork). */
    private val cancelJob: () -> Unit,
    /** Whether the job is queued or running (WorkManager's word for it; tests say no). */
    private val jobAlive: suspend () -> Boolean,
    private val scope: CoroutineScope,
    private val notices: ImportNotices = ImportNotices.None,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** Where files are copied and looked into (tests run it in place). */
    private val io: CoroutineContext = Dispatchers.IO,
) : YourRecordImports {

    @Inject constructor(
        @ApplicationContext context: Context,
        store: RecordImportStore,
        dao: RecordPlayDao,
        own: WebLibraryExportDao,
    ) : this(
        store = store,
        archive = DaoArchiveStore(dao),
        ownSpan = { own.ownFirstPlayAt() to own.ownLastPlayAt() },
        intake = ImportIntake(File(context.noBackupFilesDir, "record-import/files")),
        scratch = File(context.noBackupFilesDir, "record-import/scratch"),
        sourceOf = { ImportIntake.source(context.contentResolver, it) },
        launch = { RecordImportWorker.start(context) },
        cancelJob = { RecordImportWorker.cancel(context) },
        jobAlive = { RecordImportWorker.alive(context) },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        notices = ImportNotifications(context),
    )

    private val importer = SpotifyImporter(archive, scratch, zone)

    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    val info: StateFlow<ImportInfo> = store.info

    private val _sheet = MutableStateFlow(false)

    /** The import sheet is open (over whatever is showing: Home, the record, Settings). */
    val sheetOpen: StateFlow<Boolean> = _sheet.asStateFlow()

    private val _notes = MutableSharedFlow<ImportNote>(extraBufferCapacity = 4)
    val notes: SharedFlow<ImportNote> = _notes.asSharedFlow()

    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The imported plays changed (an import written, or removed): an open record presses itself again. */
    val archiveChanged: SharedFlow<Unit> = _changed.asSharedFlow()

    override val settings: StateFlow<YourRecordImportState> = combine(store.info, _state) { i, s ->
        YourRecordImportState(
            loaded = true,
            imported = i.record != null,
            spotifyPlays = i.record?.let { it.bySource?.spotify ?: it.plays } ?: 0,
            importing = s is ImportState.Working,
            inviteHidden = i.inviteHidden,
        )
    }.stateIn(scope, SharingStarted.Eagerly, YourRecordImportState())

    /** One commit or removal at a time. */
    private val writes = Mutex()

    /** One change to the copies at a time (a copy, the copies an import no longer needs deleted, all of them cleared). */
    private val disk = Mutex()

    /** The intake's leftovers from a run the app didn't finish are cleared before anything new is copied. */
    private val ready = CompletableDeferred<Unit>()

    private sealed interface Work {
        /** The import this step belongs to: a Cancel ends the steps of the imports begun before it, never a newer one's. */
        val epoch: Long

        /**
         * Read [kept] (the preview's files, when these add to it) with [copies] (the files just brought, or every copy when the
         * read is done again); [shared]: a file shared or opened with Stash, looked inside first.
         */
        class Read(val kept: List<PickedFile>, val copies: List<PickedFile>, val shared: Boolean, override val epoch: Long) : Work

        class Commit(override val epoch: Long) : Work
    }

    private val lock = Any()
    private var queued: Work? = null
    private var files: List<PickedFile> = emptyList()
    private var prepared: Prepared? = null

    /** The copy of the files just brought, while it runs. */
    private var copying: Job? = null

    /** The latest import begun, and the latest the person cancelled (every import up to it). */
    private var epoch = 0L
    private var cancelledThrough = -1L

    init {
        scope.launch { start() }
    }

    // ── what the screens call ───────────────────────────────────────────────────────────────────────────────────────

    override fun openImport() = openSheet()

    fun openSheet() {
        _sheet.value = true
    }

    /** The sheet closed (an import running goes on: the Home card shows how far it is). */
    fun closeSheet() {
        _sheet.value = false
    }

    /** Files chosen in the sheet: read alone, or with the ones already in the preview (each file once). */
    fun bring(uris: List<Uri>) {
        bringSources(uris.filter(ImportIntake::takes).map(sourceOf), shared = false)
    }

    /**
     * Files shared or opened with Stash (the intake screen): the sheet opens at once, they're copied in while the grant to read
     * them holds, looked inside, and read only when they hold a Spotify export. Returns the copy (the intake screen keeps its
     * grant until it's done); null when they were turned away because an import is running.
     */
    fun bringShared(uris: List<Uri>): Job? = bringSources(uris.filter(ImportIntake::takes).map(sourceOf), shared = true)

    /**
     * Files brought: copied in at once, here in the app (the job may start later than a grant lasts), then read by the job.
     * Turned away (BUSY) while an import is running.
     */
    internal fun bringSources(sources: List<IntakeSource>, shared: Boolean): Job? {
        if (sources.isEmpty()) return null
        val copy = synchronized(lock) {
            val current = _state.value
            if (current is ImportState.Working || queued != null || copying?.isActive == true) {
                null
            } else {
                val e = ++epoch
                val add = current is ImportState.Preview
                _state.value = ImportState.Working(ImportProgress.Open)
                scope.launch { copyIn(sources, add, shared, e) }.also { copying = it }
            }
        }
        if (copy == null) _notes.tryEmit(ImportNote.BUSY)
        if (shared) openSheet()
        return copy
    }

    /** Imports what the preview showed. */
    fun confirm() {
        synchronized(lock) {
            if (_state.value !is ImportState.Preview) return
            queued = Work.Commit(epoch)
            _state.value = ImportState.Working(ImportProgress.Write(0, 0), saving = true)
        }
        launch()
    }

    /**
     * Try again (a failed save in the sheet; "Your import didn't finish" on the card): the files kept, read again, then the
     * preview (nothing is counted twice). With none kept, the sheet's start, to bring them.
     */
    fun again() {
        val e = synchronized(lock) {
            if (_state.value is ImportState.Working || queued != null || copying?.isActive == true) return
            _state.value = ImportState.Working(ImportProgress.Open)
            ++epoch
        }
        clearUnfinished()
        scope.launch {
            ready.await()
            val had = synchronized(lock) { files }.ifEmpty { withContext(io) { intake.existing() } }
            val go = synchronized(lock) {
                if (e != epoch || e <= cancelledThrough) return@launch
                if (had.isNotEmpty()) {
                    files = emptyList()
                    prepared = null
                    queued = Work.Read(emptyList(), had, shared = false, epoch = e)
                } else {
                    _state.value = ImportState.Idle
                }
                had.isNotEmpty()
            }
            if (go) launch() else openSheet()
        }
    }

    /**
     * Cancel, while it works: the job and any copy stop, the copies go, and nothing is taken up again. A save stopped halfway
     * keeps what it wrote (its record put right: nothing is counted twice when the files come again).
     */
    fun cancel() {
        val (e, copy) = synchronized(lock) {
            if (_state.value !is ImportState.Working) return
            cancelledThrough = epoch
            queued = null
            _state.value = ImportState.Idle
            epoch to copying
        }
        copy?.cancel()
        cancelJob()
        scope.launch {
            copy?.join()
            dropAfterCancel(e)
        }
    }

    /** Back to the start (a preview, a failure or the done step): its files let go. Nothing while an import runs. */
    fun reset() {
        val e = synchronized(lock) {
            if (_state.value is ImportState.Working) return
            prepared = null
            files = emptyList()
            _state.value = ImportState.Idle
            epoch
        }
        clearUnfinished()
        scope.launch { clearCopies(e) }
    }

    /** The card's × on "Your import didn't finish": the notice goes, and the files kept for a Try again. */
    fun dismissUnfinished() {
        val st = _state.value
        if (st is ImportState.Failed || st is ImportState.Idle || st is ImportState.Done) reset() else clearUnfinished()
    }

    /** The person tapped through to Spotify's privacy page to ask for their data. */
    fun markAsked() = store.setAsked(now())

    fun hideInvite() = store.setInviteHidden(true)

    override fun showInvite() = store.setInviteHidden(false)

    /** Remove import (spec §5.4): every imported play and the import record go; when Spotify was asked stays. */
    override suspend fun removeImport(): Boolean {
        if (_state.value is ImportState.Working) return false
        writes.withLock {
            archive.clear()
            store.clearRecord()
        }
        if (_state.value !is ImportState.Working) reset()
        _changed.tryEmit(Unit)
        return true
    }

    // ── the copy ────────────────────────────────────────────────────────────────────────────────────────────────────

    private suspend fun copyIn(sources: List<IntakeSource>, add: Boolean, shared: Boolean, e: Long) {
        try {
            ready.await()
            val had = synchronized(lock) { if (add) files else emptyList() }
            val job = currentCoroutineContext().job
            val copies = disk.withLock { withContext(io) { intake.copy(sources, had) { job.ensureActive() } } }
            synchronized(lock) {
                if (e != epoch || e <= cancelledThrough) return
                queued = Work.Read(had, copies, shared, e)
            }
            launch()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            failed(t, saving = false, e = e)
        }
    }

    // ── the job ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** The import's next step, run by the job; [onProgress] keeps its notification up to date. */
    internal suspend fun runNext(onProgress: (ImportProgress) -> Unit = {}) {
        ready.await()
        val work = synchronized(lock) { queued.also { queued = null } } ?: resumed() ?: return
        try {
            when (work) {
                is Work.Read -> read(work, onProgress)
                is Work.Commit -> commit(onProgress)
            }
        } catch (c: CancellationException) {
            withContext(NonCancellable) { stopped(work) }
            throw c
        } catch (t: Throwable) {
            // Whatever it was (an OutOfMemoryError among them), the import never stays at Working
            withContext(NonCancellable) { failed(t, saving = work is Work.Commit, e = work.epoch) }
        }
    }

    /**
     * Nothing queued: the job ran again after the app died. A save it was writing goes on (from the copies it kept); a read it
     * was doing is done again from the copies, to its preview. A failed import's copies wait for the person's Try again.
     */
    private suspend fun resumed(): Work? {
        if (_state.value != ImportState.Idle) return null
        val e = synchronized(lock) { ++epoch }
        if (store.pending != null) return Work.Commit(e)
        if (store.info.value.unfinished != null) return null
        val kept = withContext(io) { intake.existing() }
        if (kept.isEmpty()) return null
        _state.value = ImportState.Working(ImportProgress.Open)
        return Work.Read(emptyList(), kept, shared = false, epoch = e)
    }

    private suspend fun read(work: Work.Read, onProgress: (ImportProgress) -> Unit) {
        val job = currentCoroutineContext().job
        val all = work.kept + work.copies
        // One reader for the look inside and the read: a zip inside a zip is inflated once, all of it under one budget
        val reader = importer.newZip()
        val export = try {
            !work.shared || withContext(io) { intake.looksLikeExport(work.copies, reader) }
        } catch (t: Throwable) {
            reader.close()
            throw t
        }
        if (!export) {
            // Closed first: its files are let go before the copies are deleted
            reader.close()
            notAnExport(work)
            return
        }
        reader.use { zip ->
            // A new import (not more files for a preview): any copies an earlier one kept go, and so does its "didn't finish"
            if (work.kept.isEmpty()) {
                disk.withLock {
                    if (synchronized(lock) { work.epoch == epoch }) {
                        withContext(io) { intake.delete(intake.existing().filter { f -> all.none { it.file == f.file } }) }
                    }
                }
                clearUnfinished()
            }
            synchronized(lock) {
                files = all
                prepared = null
            }
            _state.value = ImportState.Working(ImportProgress.Open)
            val prep = importer.prepare(all, now(), checkpoint = { job.ensureActive() }, zip = zip) { p ->
                _state.value = ImportState.Working(p)
                onProgress(p)
            }
            store.clearStops()
            if (prep.preview.anything) {
                synchronized(lock) { prepared = prep }
                _state.value = ImportState.Preview(prep.preview, all.size)
            } else {
                val reason = nothingIn(prep.preview)
                _state.value = ImportState.Failed(reason)
                if (reason.unfinished) markUnfinished()
            }
        }
    }

    /** Files shared or opened with Stash that hold no export: told so in the sheet, or, when they were more for a preview, the preview stays. */
    private suspend fun notAnExport(work: Work.Read) {
        disk.withLock { withContext(io) { intake.delete(work.copies) } }
        val kept = synchronized(lock) { prepared?.takeIf { work.kept.isNotEmpty() } }
        if (kept != null) {
            _state.value = ImportState.Preview(kept.preview, work.kept.size)
            _notes.tryEmit(ImportNote.NOT_AN_EXPORT)
        } else {
            _state.value = ImportState.Failed(ImportFailure.NOT_EXPORT)
        }
    }

    private suspend fun commit(onProgress: (ImportProgress) -> Unit) {
        val job = currentCoroutineContext().job
        val prep = synchronized(lock) { prepared } ?: run {
            // The app died after the preview or mid-write: read the kept copies again (the merge is planned afresh anyway)
            val kept = withContext(io) { intake.existing() }
            if (kept.isEmpty()) {
                writes.withLock { finishPending() }
                _state.value = ImportState.Idle
                return
            }
            synchronized(lock) { files = kept }
            _state.value = ImportState.Working(ImportProgress.Open, saving = true)
            importer.prepare(kept, now(), checkpoint = { job.ensureActive() }) { onProgress(it) }
        }
        val result = writes.withLock {
            store.setPending(now())
            _state.value = ImportState.Working(ImportProgress.Write(0, 0), saving = true)
            val r = importer.commit(prep, checkpoint = { job.ensureActive() }) { p ->
                _state.value = ImportState.Working(p, saving = true)
                onProgress(p)
            }
            storeRecord(added = 1)
            store.setPending(null)
            r
        }
        val e = synchronized(lock) {
            prepared = null
            files = emptyList()
            epoch
        }
        clearCopies(e)
        store.clearStops()
        clearUnfinished()
        Log.i(TAG, "import written: ${result.listens} new listens, ${result.written} plays, ${result.dropped} dropped")
        _state.value = ImportState.Done(result.listens, store.info.value.record?.plays ?: 0)
        _changed.tryEmit(Unit)
    }

    // ── how it ends early ───────────────────────────────────────────────────────────────────────────────────────────

    /** The job was stopped: by the person's Cancel (everything goes), or by the system (the step runs again from where it was). */
    private suspend fun stopped(work: Work) {
        if (synchronized(lock) { work.epoch <= cancelledThrough }) {
            dropAfterCancel(work.epoch)
            return
        }
        // What a save wrote so far shows; `pending` and the copies stay for the step's next run
        if (work is Work.Commit) _changed.tryEmit(Unit)
        val stops = store.addStop()
        if (stops >= MAX_STOPS) {
            failed(StoppedTooOften(), saving = work is Work.Commit, e = work.epoch)
            return
        }
        Log.i(TAG, "the import was stopped by the system ($stops): it goes on when the job runs again")
        // A stopped job runs again by itself: in this process it takes the same step; after the app's death, resumed() finds it
        synchronized(lock) { if (queued == null) queued = work }
    }

    /**
     * Failed (or given up after the system stopped it [MAX_STOPS] times): said in the sheet, and when it went wrong (rather than
     * an answer about the files) on the card and in a notification, the copies kept for a Try again.
     */
    private suspend fun failed(t: Throwable, saving: Boolean, e: Long) {
        // First: after an OutOfMemoryError, what the read held is let go
        synchronized(lock) { prepared = null }
        if (t !is NotAnExport && t !is StorageFull) Log.w(TAG, "${if (saving) "writing" else "reading"} the import failed: ${t.javaClass.simpleName}")
        if (saving) {
            // What was written keeps its record; a save that failed isn't taken up by itself (Try again does it)
            try {
                writes.withLock { finishPending() }
            } catch (c: CancellationException) {
                throw c
            } catch (x: Exception) {
                Log.w(TAG, "the import's record couldn't be put right: ${x.javaClass.simpleName}")
            }
            _changed.tryEmit(Unit)
        }
        store.clearStops()
        // An import cancelled, or one begun since: nothing to say about this one
        if (synchronized(lock) { e != epoch || e <= cancelledThrough }) return
        val reason = when {
            t is NotAnExport -> ImportFailure.NOT_EXPORT
            t is ImportTooBig -> ImportFailure.TOO_BIG
            t is StorageFull || isNoSpace(t) -> ImportFailure.STORAGE
            else -> ImportFailure.OTHER
        }
        _state.value = ImportState.Failed(reason, saving)
        if (reason.unfinished) markUnfinished()
    }

    /** After the person's Cancel: what a save wrote keeps its record, and unless an import has begun since, the copies and the step go. */
    private suspend fun dropAfterCancel(e: Long) {
        try {
            writes.withLock { finishPending() }
        } catch (c: CancellationException) {
            throw c
        } catch (x: Exception) {
            Log.w(TAG, "the import's record couldn't be put right: ${x.javaClass.simpleName}")
        }
        store.clearStops()
        val current = synchronized(lock) {
            (e == epoch).also {
                if (it) {
                    prepared = null
                    files = emptyList()
                    queued = null
                }
            }
        }
        if (!current) return
        clearCopies(e)
        _changed.tryEmit(Unit)
    }

    /** The copies go, unless an import begun since [e] has its own there now. */
    private suspend fun clearCopies(e: Long) = disk.withLock {
        if (synchronized(lock) { e == epoch }) withContext(io) { intake.clear() }
    }

    private fun markUnfinished() {
        store.setUnfinished(now())
        notices.unfinished()
    }

    private fun clearUnfinished() {
        store.setUnfinished(null)
        notices.clear()
    }

    /** service.ts `choose`'s word for files that add nothing (and [ImportFailure.NO_PLAYS], the app's own). */
    private fun nothingIn(p: ImportPreview): ImportFailure = when {
        p.same > 0 || p.overlap > 0 -> ImportFailure.NOTHING_NEW
        p.unreadable > 0 -> ImportFailure.UNREADABLE
        p.playlists.isNotEmpty() || p.likes > 0 -> ImportFailure.NO_PLAYS
        else -> ImportFailure.NOT_EXPORT
    }

    /** The import record over the archive as it is now ([added] imports on top of the last one's count). Under [writes]. */
    private suspend fun storeRecord(added: Int) {
        val (ownFirst, ownLast) = ownSpan()
        store.setRecord(SpotifyImporter.record(store.info.value.record, archive.listens(), ownFirst, ownLast, now(), added))
    }

    /** importer.ts `finishPending`: an import that stopped after writing plays gets its record now. Under [writes]. */
    private suspend fun finishPending() {
        if (store.pending == null) return
        if (archive.count() > 0) storeRecord(added = if (store.info.value.record == null) 1 else 0)
        store.setPending(null)
    }

    /**
     * At start: with no job running, the scratch a dead one left goes; a commit the app's death interrupted is taken up again (its
     * job runs once more); otherwise the copies an unfinished run left are cleared, unless they wait for a Try again. Plays kept
     * with no record (a library restored from a backup) get one.
     */
    private suspend fun start() {
        try {
            val alive = jobAlive()
            if (!alive) withContext(io) { scratch.deleteRecursively() }
            if (store.pending != null) {
                if (!alive) launch()
            } else {
                if (!alive && store.info.value.unfinished == null) withContext(io) { intake.clear() }
                if (store.info.value.record == null && archive.count() > 0) writes.withLock { storeRecord(added = 1) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the import's start-up check failed: ${e.javaClass.simpleName}")
        } finally {
            ready.complete(Unit)
        }
    }

    private fun isNoSpace(e: Throwable): Boolean = generateSequence(e) { it.cause }.any { it.message?.contains("ENOSPC") == true || it.javaClass.simpleName == "SQLiteFullException" }

    internal companion object {
        const val TAG = "YourRecord"

        /** Times the system may stop one import before it's given up as unfinished (a heavy import takes a minute or two). */
        const val MAX_STOPS = 3
    }
}

/** Settings reaches the imports through core:data's [YourRecordImports]; this module's service is it. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class RecordImportsModule {
    @Binds
    abstract fun yourRecordImports(imports: RecordImports): YourRecordImports
}
