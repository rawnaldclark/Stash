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
import com.stash.feature.record.importer.NotAnExport
import com.stash.feature.record.importer.PickedFile
import com.stash.feature.record.importer.Prepared
import com.stash.feature.record.importer.RecordArchiveStore
import com.stash.feature.record.importer.SpotifyImporter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

/** Why an import came to nothing (service.ts `SpotifyState` error reasons, and [NO_PLAYS]). */
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

    OTHER,
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
    /** A file shared or opened with Stash that holds no Spotify export. */
    NOT_AN_EXPORT,

    /** Files brought while an import is running: it goes on, they wait. */
    BUSY,
}

/**
 * Bring your Spotify, the app's side (stash-player `src/lib/spotify/service.ts`): the import's state for the Home card, the
 * sheet and Settings, and the steps that change it. One flow, opened from three places: the Home card, the record's
 * `stash:import`, and a zip shared or opened with Stash ([bringShared], which looks inside first and opens the sheet only for an
 * export).
 *
 * The work runs in a foreground job ([RecordImportWorker]), so it goes on when the person leaves the screen or the app. It is
 * two steps, as on the web: [bring] reads the files and works out what they would add, writing nothing, and the preview waits;
 * [confirm] plans the merge again against the archive as it is then and writes it. A commit and Remove import hold one lock.
 * A commit marks `pending` before it writes: if the app dies halfway, the job runs again from the copies it kept, and the next
 * merge puts right anything left half done.
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
    /** Whether the job is queued or running (WorkManager's word for it; tests say no). */
    private val jobAlive: suspend () -> Boolean,
    private val scope: CoroutineScope,
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
        jobAlive = { RecordImportWorker.alive(context) },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
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

    /** The intake's leftovers from a run the app didn't finish are cleared before anything new is copied. */
    private val ready = CompletableDeferred<Unit>()

    private sealed interface Work {
        /** Copy [sources] in and read them (with the files already in the preview when [add]); [shared]: look inside first. */
        class Prepare(val sources: List<IntakeSource>, val add: Boolean, val shared: Boolean) : Work

        data object Commit : Work
    }

    private val lock = Any()
    private var queued: Work? = null
    private var files: List<PickedFile> = emptyList()
    private var prepared: Prepared? = null

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
    fun bring(uris: List<Uri>) = bringSources(uris.map(sourceOf), shared = false)

    /**
     * Files shared or opened with Stash: copied in at once (their grant can lapse), looked inside, and brought in only when they
     * hold a Spotify export (the sheet opens then); otherwise the person is told it isn't one.
     */
    fun bringShared(uris: List<Uri>) = bringSources(uris.map(sourceOf), shared = true)

    internal fun bringSources(sources: List<IntakeSource>, shared: Boolean) {
        if (sources.isEmpty()) return
        val current = _state.value
        if (current is ImportState.Working || synchronized(lock) { queued != null }) {
            _notes.tryEmit(ImportNote.BUSY)
            if (shared) openSheet()
            return
        }
        synchronized(lock) { queued = Work.Prepare(sources, add = current is ImportState.Preview, shared = shared) }
        if (!shared) _state.value = ImportState.Working(ImportProgress.Open)
        launch()
    }

    /** Imports what the preview showed. */
    fun confirm() {
        if (_state.value !is ImportState.Preview) return
        synchronized(lock) { queued = Work.Commit }
        _state.value = ImportState.Working(ImportProgress.Write(0, 0), saving = true)
        launch()
    }

    /** A failed save, again: the same files read again, then the preview (nothing is counted twice). */
    fun again() {
        val had = synchronized(lock) { files }
        if (had.isEmpty() || _state.value is ImportState.Working) return
        synchronized(lock) {
            files = emptyList()
            queued = Work.Prepare(had.map { f -> IntakeSource(f.name, null) { f.file.inputStream() } }, add = false, shared = false)
        }
        _state.value = ImportState.Working(ImportProgress.Open)
        launch()
    }

    /** Back to the start (a preview, a failure or the done step): its files let go. Nothing while an import runs. */
    fun reset() {
        if (_state.value is ImportState.Working) return
        synchronized(lock) {
            prepared = null
            files = emptyList()
        }
        _state.value = ImportState.Idle
        scope.launch { intake.clear() }
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

    // ── the job ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** The import's next step, run by the job; [onProgress] keeps its notification up to date. */
    internal suspend fun runNext(onProgress: (ImportProgress) -> Unit = {}) {
        ready.await()
        val work = synchronized(lock) { queued.also { queued = null } }
        when (work) {
            is Work.Prepare -> prepare(work, onProgress)
            Work.Commit -> commit(onProgress)
            // The job ran again after the app died: a commit it was writing goes on from the files it kept; anything else is gone
            null -> if (store.pending != null) commit(onProgress) else if (_state.value !is ImportState.Preview) intake.clear()
        }
    }

    private suspend fun prepare(work: Work.Prepare, onProgress: (ImportProgress) -> Unit) {
        val had = synchronized(lock) { if (work.add) files else emptyList() }
        try {
            val copies = withContext(io) { intake.copy(work.sources, had) }
            if (work.shared) {
                val export = withContext(io) { intake.looksLikeExport(copies, scratch) }
                if (!export) {
                    intake.delete(copies)
                    _notes.tryEmit(ImportNote.NOT_AN_EXPORT)
                    return
                }
                openSheet()
            }
            if (!work.add) intake.delete(intake.existing().filter { e -> copies.none { it.file == e.file } })
            val all = had + copies
            synchronized(lock) {
                files = all
                prepared = null
            }
            _state.value = ImportState.Working(ImportProgress.Open)
            val job = currentCoroutineContext().job
            val prep = importer.prepare(all, now(), checkpoint = { job.ensureActive() }) { p ->
                _state.value = ImportState.Working(p)
                onProgress(p)
            }
            if (prep.preview.anything) {
                synchronized(lock) { prepared = prep }
                _state.value = ImportState.Preview(prep.preview, all.size)
            } else {
                _state.value = ImportState.Failed(nothingIn(prep.preview))
            }
        } catch (e: CancellationException) {
            _state.value = ImportState.Idle
            throw e
        } catch (e: Exception) {
            if (e !is NotAnExport && e !is StorageFull) Log.w(TAG, "reading the files failed: ${e.javaClass.simpleName}")
            _state.value = ImportState.Failed(
                when {
                    e is NotAnExport -> ImportFailure.NOT_EXPORT
                    e is StorageFull || isNoSpace(e) -> ImportFailure.STORAGE
                    else -> ImportFailure.OTHER
                },
            )
            // A file shared with Stash that couldn't be read: the sheet says so
            if (work.shared) openSheet()
        }
    }

    private suspend fun commit(onProgress: (ImportProgress) -> Unit) {
        try {
            val job = currentCoroutineContext().job
            val prep = synchronized(lock) { prepared } ?: run {
                // The app died after the preview or mid-write: read the kept copies again (the merge is planned afresh anyway)
                val kept = intake.existing()
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
            synchronized(lock) {
                prepared = null
                files = emptyList()
            }
            intake.clear()
            Log.i(TAG, "import written: ${result.listens} new listens, ${result.written} plays, ${result.dropped} dropped")
            _state.value = ImportState.Done(result.listens, store.info.value.record?.plays ?: 0)
            _changed.tryEmit(Unit)
        } catch (e: CancellationException) {
            // The job was stopped: what was written stays, its record is put right, and the next start's merge finishes it
            withContext(NonCancellable) { writes.withLock { finishPending() } }
            _state.value = ImportState.Idle
            _changed.tryEmit(Unit)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "writing the import failed: ${e.javaClass.simpleName}")
            runCatching { writes.withLock { finishPending() } }
            _state.value = ImportState.Failed(if (e is StorageFull || isNoSpace(e)) ImportFailure.STORAGE else ImportFailure.OTHER, saving = true)
            _changed.tryEmit(Unit)
        }
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
     * At start: a commit the app's death interrupted is taken up again (its job runs once more); otherwise the copies an unfinished
     * run left are cleared. Plays kept with no record (a library restored from a backup) get one.
     */
    private suspend fun start() {
        try {
            if (store.pending != null) {
                if (!jobAlive()) launch()
            } else {
                if (!jobAlive()) intake.clear()
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

    private companion object {
        const val TAG = "YourRecord"
    }
}

/** Settings reaches the imports through core:data's [YourRecordImports]; this module's service is it. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class RecordImportsModule {
    @Binds
    abstract fun yourRecordImports(imports: RecordImports): YourRecordImports
}
