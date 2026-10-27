package com.stash.feature.record.imports

import android.content.Context
import android.net.Uri
import android.util.Log
import com.stash.core.data.db.dao.RecordLengthDao
import com.stash.core.data.db.dao.RecordPlayDao
import com.stash.core.data.db.dao.WebLibraryExportDao
import com.stash.core.data.record.ChosenPlaylist
import com.stash.core.data.record.SpotifyLibraryImport
import com.stash.core.data.record.YourRecordImportState
import com.stash.core.data.record.YourRecordImports
import com.stash.feature.record.importer.ImportChoice
import com.stash.feature.record.importer.ImportPreview
import com.stash.feature.record.importer.ImportProgress
import com.stash.feature.record.importer.ImportTooBig
import com.stash.feature.record.importer.NotAnExport
import com.stash.feature.record.importer.PickedFile
import com.stash.feature.record.importer.Prepared
import com.stash.feature.record.importer.RecordArchiveStore
import com.stash.feature.record.importer.SpotifyImporter
import com.stash.feature.record.importer.StorageFull
import com.stash.feature.record.taste.RecordTaste
import com.stash.feature.record.taste.TasteHooks
import com.stash.core.model.weblink.SongKey
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
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
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
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext
import javax.inject.Inject
import javax.inject.Singleton

/** Why an import came to nothing (service.ts `SpotifyState` error reasons, and [TOO_BIG], the app's own). */
enum class ImportFailure {
    /** None of the files is a file of a Spotify export. */
    NOT_EXPORT,

    /** An export, every play of it here already, and no playlists or liked songs in it (spec §5.5: never called "not an export"). */
    NOTHING_NEW,

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

    /**
     * Written: [listens] new listens; [recordPlays], the record's listens in all; [playlists] and [likes], what it brought the
     * library (the playlists chosen, the songs newly liked).
     */
    data class Done(val listens: Int, val recordPlays: Int, val playlists: Int = 0, val likes: Int = 0) : ImportState

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
 * The step's import was cancelled (or replaced) while the step ran: thrown at its next checkpoint, so it stops at once even
 * before WorkManager's own cancel (asynchronous) reaches the job.
 */
private class ImportCancelled : CancellationException("the import was cancelled")

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
    /** This app's package: URIs of its own providers are never read back through the intake. */
    private val ownPackage: String = "",
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** Where files are copied and looked into (tests run it in place). */
    private val io: CoroutineContext = Dispatchers.IO,
    /** The archive's one writer at a time, shared with the Last.fm import ([com.stash.feature.record.lastfm.RecordLastfm]). */
    private val writeLock: RecordWrites = RecordWrites(),
    /** Stops the Last.fm job, queued or stopped (WorkManager's cancelUniqueWork), so a removal can't be undone by it. */
    private val cancelLastfm: () -> Unit = {},
    /** Stops the lengths pass and waits for it to let go of the lock (service.ts `pauseLengths`); whether one was running. */
    private val pauseLengths: suspend () -> Boolean = { false },
    /** Starts the lengths pass (service.ts `timeSongs`). */
    private val startLengths: () -> Unit = {},
    /** Where the playlists, liked songs and hidden songs go (the library, `record_hidden`). */
    private val library: RecordLibrary = RecordLibrary.None,
    /** The taste, worked out again under the write lock after each change to the record ([RecordTaste]). */
    private val taste: TasteHooks = TasteHooks.None,
    /** At start, once the imports are ready: an import with no taste yet gets one ([RecordTaste.ensure]). */
    private val ensureTaste: suspend () -> Unit = {},
) : YourRecordImports {

    @Inject constructor(
        @ApplicationContext context: Context,
        store: RecordImportStore,
        dao: RecordPlayDao,
        lengthDao: RecordLengthDao,
        own: WebLibraryExportDao,
        writeLock: RecordWrites,
        /** Lazy: the lengths pass reads the import record and the changes from here. */
        lengths: dagger.Lazy<com.stash.feature.record.lastfm.RecordLengths>,
        library: RecordLibrary,
        taste: RecordTaste,
    ) : this(
        store = store,
        archive = DaoArchiveStore(dao, lengthDao),
        ownSpan = { own.ownFirstPlayAt() to own.ownLastPlayAt() },
        intake = ImportIntake(File(context.noBackupFilesDir, "record-import/files")),
        scratch = File(context.noBackupFilesDir, "record-import/scratch"),
        sourceOf = { ImportIntake.source(context.contentResolver, it) },
        launch = { RecordImportWorker.start(context) },
        cancelJob = { RecordImportWorker.cancel(context) },
        jobAlive = { RecordImportWorker.alive(context) },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        notices = ImportNotifications(context),
        ownPackage = context.packageName,
        writeLock = writeLock,
        cancelLastfm = { com.stash.feature.record.lastfm.RecordLastfmWorker.cancel(context) },
        pauseLengths = { lengths.get().pause() },
        startLengths = { lengths.get().start() },
        library = library,
        taste = taste,
        ensureTaste = taste::ensure,
    )

    private val importer = SpotifyImporter(archive, scratch, zone)

    /**
     * Where a provider is read (its name, size and bytes): a call that may never return is left running here when its import is
     * cancelled, and one that fails fails only its own wait (a supervisor: never the service's scope).
     */
    private val reads = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

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

    override val settings: StateFlow<YourRecordImportState> = combine(store.info, _state, writeLock.lastfmRunning) { i, s, lastfm ->
        YourRecordImportState(
            loaded = true,
            imported = i.record != null,
            spotifyPlays = i.record?.let { it.bySource?.spotify ?: it.plays } ?: 0,
            importing = s is ImportState.Working || lastfm,
            inviteHidden = i.inviteHidden,
        )
    }.stateIn(scope, SharingStarted.Eagerly, YourRecordImportState())

    /** One commit or removal at a time, and never during a Last.fm run ([RecordWrites]). */
    private val writes = writeLock.mutex

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

        /** [choice]: what the person chose to bring into the library (the playlists ticked, the liked songs or not). */
        class Commit(override val epoch: Long, val choice: ImportChoice) : Work
    }

    private val lock = Any()
    private var queued: Work? = null
    private var files: List<PickedFile> = emptyList()
    private var prepared: Prepared? = null

    /** The copy of the files just brought, while it runs. */
    private var copying: Job? = null

    /** The parts copies are streaming or renaming (each deletes its own): every other part is an orphan [clearCopies] removes. */
    private val liveParts = HashSet<File>()

    /** The latest import begun, and the latest the person cancelled (every import up to it). */
    private var epoch = 0L
    private var cancelledThrough = -1L

    /** A step waits for the job's next run (the app died with it under way): its import's epoch, taken by [resumed]. */
    private var resumeEpoch: Long? = null

    init {
        // A save the app's death interrupted shows as one at once (and turns new files away) until the job takes it up again
        if (store.pending != null) markResuming(saving = true)
        scope.launch { start() }
        scope.launch {
            ready.await()
            try {
                ensureTaste()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the taste's start-up check failed: ${e.javaClass.simpleName}")
            }
        }
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
        bringSources(uris.filter { ImportIntake.takes(it, ownPackage) }.map(sourceOf), shared = false)
    }

    /**
     * Files shared or opened with Stash (the intake screen): the sheet opens at once, they're copied in while the grant to read
     * them holds, looked inside, and read only when they hold a Spotify export. Returns the copy (the intake screen keeps its
     * grant until it's done); null when they were turned away because an import is running.
     */
    fun bringShared(uris: List<Uri>): Job? = bringSources(uris.filter { ImportIntake.takes(it, ownPackage) }.map(sourceOf), shared = true)

    /**
     * Files brought: copied in at once, here in the app (the job may start later than a grant lasts), then read by the job.
     * Turned away (BUSY) while an import is running.
     */
    internal fun bringSources(sources: List<IntakeSource>, shared: Boolean): Job? {
        if (sources.isEmpty()) return null
        var tooMany = false
        val copy = synchronized(lock) {
            val current = _state.value
            if (current is ImportState.Working || queued != null || copying?.isActive == true) {
                null
            } else if (sources.size + (if (current is ImportState.Preview) files.size else 0) > MAX_FILES) {
                // More files than an export has (counting a preview's, when these add to it): refused whole, never a partial import
                ++epoch
                _state.value = ImportState.Failed(ImportFailure.TOO_BIG)
                tooMany = true
                null
            } else {
                val e = ++epoch
                val add = current is ImportState.Preview
                _state.value = ImportState.Working(ImportProgress.Open)
                scope.launch { copyIn(sources, add, shared, e) }.also { copying = it }
            }
        }
        if (tooMany) markUnfinished() else if (copy == null) _notes.tryEmit(ImportNote.BUSY)
        if (shared) openSheet()
        return copy
    }

    /** Imports what the preview showed: its plays, and the playlists and liked songs of [choice]. */
    fun confirm(choice: ImportChoice = ImportChoice.ALL) {
        synchronized(lock) {
            if (_state.value !is ImportState.Preview) return
            queued = Work.Commit(epoch, choice)
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
            val had = synchronized(lock) { files }.ifEmpty { kept() }
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
            resumeEpoch = null
            _state.value = ImportState.Idle
            (epoch to copying).also { copying = null }
        }
        // Not waited for: a provider's read can block for ever. The copy stops at once and its read cleans up when it returns.
        copy?.cancel()
        cancelJob()
        scope.launch { dropAfterCancel(e) }
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

    /**
     * Remove import (spec §5.4): every imported play (the Spotify export's and the Last.fm scrobbles), the songs hidden on
     * Spotify, the import record and the Last.fm import's mark go; when Spotify was asked stays, and so do the playlists and liked
     * songs an import brought (they are the library's: the same export again finds them by their `name#n`). Refused while an
     * import runs, a Last.fm run too (the web would wait for its lock, minutes for a long history; here it says so at once).
     */
    override suspend fun removeImport(): Boolean {
        if (_state.value is ImportState.Working || writeLock.lastfmRunning.value) return false
        // In the app's scope, off the main thread: leaving Settings mid-way can't cut it in two
        scope.async(io + NonCancellable) {
            // The lengths pass lets go of the lock first (service.ts `remove`)
            pauseLengths()
            writes.withLock {
                // A Last.fm job queued or stopped would bring the scrobbles back: it goes, and one that slips through finds its
                // generation gone
                cancelLastfm()
                store.nextLastfmGeneration()
                archive.clear()
                library.clearHidden()
                // The taste and the songs put back go with the import (archive.ts `clear`)
                taste.clearLocked(keep = true)
                store.clearRecord()
            }
        }.await()
        if (_state.value !is ImportState.Working) reset()
        _changed.tryEmit(Unit)
        return true
    }

    // ── the copy ────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Copies [sources] in, each in three steps (a name reserved, the bytes streamed, the part renamed) so that the lock on the
     * copies is never held while a provider is read, and a read that never returns holds up nothing: neither a Cancel, nor the
     * next import. The provider's name, size and bytes are all read off the main thread.
     */
    private suspend fun copyIn(sources: List<IntakeSource>, add: Boolean, shared: Boolean, e: Long) {
        val copied = ArrayList<PickedFile>()
        try {
            ready.await()
            // Brought while the app was starting, and a step the app's death left waiting was found meanwhile: it goes first
            if (synchronized(lock) { !live(e) && (resumeEpoch ?: -1) > e }) {
                _notes.tryEmit(ImportNote.BUSY)
                return
            }
            val had = synchronized(lock) { if (add) files else emptyList() }
            var total = withContext(io) { had.sumOf { it.file.length() } }
            for (src in sources) {
                val (name, size) = abandonable(e) { src.name to src.size }
                if (intake.alreadyHave(name, size, had + copied)) continue
                // Reserved whole: a cancel can't land between the part's making and this copy holding it
                val part = withContext(NonCancellable) {
                    disk.withLock { withContext(io) { intake.reserve(name).also { synchronized(liveParts) { liveParts += it } } } }
                }
                var kept = false
                try {
                    total += streamInto(src, part, intake.maxTotal - total, e)
                    // Kept only while the import is wanted
                    val copy = withContext(NonCancellable) {
                        disk.withLock { withContext(io) { if (synchronized(lock) { live(e) }) intake.finish(part) else null } }
                    } ?: throw ImportCancelled()
                    kept = true
                    copied += copy
                } finally {
                    // Not kept (cancelled, failed, a rename that failed): the part goes now, and again when a read still running ends
                    if (!kept) part.delete()
                    synchronized(liveParts) { liveParts -= part }
                }
            }
            synchronized(lock) {
                if (!live(e)) throw ImportCancelled()
                queued = Work.Read(had, copied, shared, e)
            }
            launch()
        } catch (c: CancellationException) {
            dropCopies(copied)
            throw c
        } catch (t: Throwable) {
            dropCopies(copied)
            failed(t, saving = false, e = e)
        }
    }

    /**
     * Streams [src] into [part] on [io], where a provider's read can block for as long as it likes: this waits for it only while
     * import [e] is wanted. On a cancel it returns at once, closes the provider's stream (on Android that wakes a blocked read)
     * and leaves the read to delete the part when it ends, however it ends.
     */
    private suspend fun streamInto(src: IntakeSource, part: File, cap: Long, e: Long): Long {
        val input = AtomicReference<InputStream?>()
        val streaming = reads.async(io) {
            intake.stream(src, part, cap, opened = input::set) {
                if (!isActive || !synchronized(lock) { live(e) }) throw ImportCancelled()
            }
        }
        try {
            return streaming.await()
        } catch (t: Throwable) {
            streaming.cancel()
            streaming.invokeOnCompletion { part.delete() }
            input.get()?.let { s -> reads.launch(io) { runCatching { s.close() } } }
            throw t
        }
    }

    /** [block] (a call into another app) on [io], waited for only while import [e] is wanted. */
    private suspend fun <T> abandonable(e: Long, block: () -> T): T {
        val call = reads.async(io) { block() }
        return try {
            call.await().also { if (!synchronized(lock) { live(e) }) throw ImportCancelled() }
        } catch (t: Throwable) {
            call.cancel()
            throw t
        }
    }

    /** This call's copies go (a refused or cancelled copy keeps nothing). */
    private suspend fun dropCopies(copies: List<PickedFile>) {
        if (copies.isEmpty()) return
        withContext(NonCancellable) { disk.withLock { withContext(io) { intake.delete(copies) } } }
    }

    /** The finished copies, listed under the lock (never a part still streaming). */
    private suspend fun kept(): List<PickedFile> = disk.withLock { withContext(io) { intake.existing() } }

    // ── the job ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** The import's next step, run by the job; [onProgress] keeps its notification up to date. */
    internal suspend fun runNext(onProgress: (ImportProgress) -> Unit = {}) {
        ready.await()
        val work = synchronized(lock) { queued.also { queued = null } } ?: resumed() ?: return
        try {
            when (work) {
                is Work.Read -> read(work, onProgress)
                is Work.Commit -> commit(work, onProgress)
            }
        } catch (c: CancellationException) {
            withContext(NonCancellable) { stopped(work) }
            // The import cancelled under a step still running (WorkManager's cancel hadn't reached it): the step just ends
            if (c is ImportCancelled && currentCoroutineContext().isActive) return
            throw c
        } catch (t: Throwable) {
            // Whatever it was (an OutOfMemoryError among them), the import never stays at Working
            withContext(NonCancellable) { failed(t, saving = work is Work.Commit, e = work.epoch) }
        }
    }

    /**
     * Nothing queued: the job ran again after the app died. A save it was writing goes on (from the copies it kept); a read it
     * was doing is done again from the copies, to its preview. Only a step marked as waiting is taken up ([markResuming] at
     * start, which a Cancel clears): a run of the job after a Cancel, before the Cancel's clean-up has caught up, takes up nothing.
     */
    private suspend fun resumed(): Work? {
        val e = synchronized(lock) {
            if (queued != null || copying?.isActive == true) return null
            val waiting = resumeEpoch ?: return null
            resumeEpoch = null
            waiting.takeIf { live(it) } ?: return null
        }
        if (store.pending != null) return Work.Commit(e, store.pendingChoice)
        val kept = if (store.info.value.unfinished == null) kept() else emptyList()
        if (kept.isEmpty()) {
            publishAt(e, ImportState.Idle)
            return null
        }
        publishAt(e, ImportState.Working(ImportProgress.Open))
        return Work.Read(emptyList(), kept, shared = false, epoch = e)
    }

    /** A step waits for the job's next run: shown as under way (files brought meanwhile are turned away), cancellable. */
    private fun markResuming(saving: Boolean) = synchronized(lock) {
        resumeEpoch = ++epoch
        _state.value = ImportState.Working(ImportProgress.Open, saving = saving)
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
                    if (synchronized(lock) { live(work) }) {
                        withContext(io) { intake.delete(intake.existing().filter { f -> all.none { it.file == f.file } }) }
                    }
                }
                clearUnfinished()
            }
            synchronized(lock) {
                if (live(work)) {
                    files = all
                    prepared = null
                }
            }
            publish(work, ImportState.Working(ImportProgress.Open))
            val read = importer.prepare(all, now(), checkpoint = { checkpoint(job, work) }, zip = zip) { p ->
                publish(work, ImportState.Working(p))
                onProgress(p)
            }
            // Planning the merge has no checkpoint: a Cancel during it ends here, and its preview is never shown
            job.ensureActive()
            val prep = marked(read)
            job.ensureActive()
            store.clearStops()
            if (prep.preview.anything) {
                synchronized(lock) {
                    if (live(work)) {
                        prepared = prep
                        _state.value = ImportState.Preview(prep.preview, all.size)
                    }
                }
            } else {
                val reason = nothingIn(prep.preview)
                if (publish(work, ImportState.Failed(reason)) && reason.unfinished) markUnfinished()
            }
        }
    }

    /** Files shared or opened with Stash that hold no export: told so in the sheet, or, when they were more for a preview, the preview stays. */
    private suspend fun notAnExport(work: Work.Read) {
        disk.withLock { withContext(io) { intake.delete(work.copies) } }
        val kept = synchronized(lock) { prepared?.takeIf { work.kept.isNotEmpty() } }
        if (kept != null) {
            if (publish(work, ImportState.Preview(kept.preview, work.kept.size))) _notes.tryEmit(ImportNote.NOT_AN_EXPORT)
        } else {
            publish(work, ImportState.Failed(ImportFailure.NOT_EXPORT))
        }
    }

    /**
     * [prep] with what the library says of it: the playlists it holds already, each marked (owner decision D3: they start
     * unticked), and whether the liked songs would download. A library that can't be read marks nothing (they all start ticked).
     */
    private suspend fun marked(prep: Prepared): Prepared {
        val p = prep.preview
        if (p.playlists.isEmpty() && p.likes == 0) return prep
        return try {
            val already = if (p.playlists.isEmpty()) emptySet() else library.alreadyHere(prep.bundle.playlists)
            val download = p.likes > 0 && library.likesDownload()
            val playlists = p.playlists.mapIndexed { i, c -> if (i in already) c.copy(already = true) else c }
            Prepared(p.copy(playlists = playlists, likesDownload = download), prep.bundle, prep.files)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the library couldn't be compared with the export: ${e.javaClass.simpleName}")
            prep
        }
    }

    private suspend fun commit(work: Work.Commit, onProgress: (ImportProgress) -> Unit) {
        val job = currentCoroutineContext().job
        val prep = synchronized(lock) { prepared } ?: run {
            // The app died after the preview or mid-write: read the kept copies again (the merge is planned afresh anyway)
            val kept = kept()
            if (kept.isEmpty()) {
                writes.withLock { finishPending() }
                publish(work, ImportState.Idle)
                return
            }
            synchronized(lock) { if (live(work)) files = kept }
            publish(work, ImportState.Working(ImportProgress.Open, saving = true))
            importer.prepare(kept, now(), checkpoint = { checkpoint(job, work) }) { onProgress(it) }
        }
        // The lengths pass lets go of the lock for the commit, and carries on after it (the export may time some songs now)
        val timing = pauseLengths()
        val choice = work.choice
        val written = writes.withLock {
            checkpoint(job, work)
            // A commit taken up again keeps the time it began: the liked songs it brings keep the times it gave them
            val at = store.pending ?: now()
            store.setPending(at, choice)
            publish(work, ImportState.Working(ImportProgress.Write(0, 0), saving = true))
            // 1. The plays
            val r = importer.commit(prep, checkpoint = { checkpoint(job, work) }) { p ->
                publish(work, ImportState.Working(p, saving = true))
                onProgress(p)
            }
            // 2. The songs hidden on Spotify, added to those kept
            checkpoint(job, work)
            library.addHidden(prep.bundle.hidden)
            // 3. The playlists chosen (each known by its place in the whole export) and the liked songs, into the library
            val keys = SpotifyLibraryImport.playlistKeys(prep.bundle.playlists)
            val playlists = prep.bundle.playlists.indices.filter(choice::takes).map { ChosenPlaylist(keys[it], prep.bundle.playlists[it]) }
            val likes = if (choice.likes) prep.bundle.likes else emptyList()
            var liked = 0
            if (playlists.isNotEmpty() || likes.isNotEmpty()) {
                checkpoint(job, work)
                liked = library.write(playlists, likes, at) { done, total ->
                    checkpoint(job, work)
                    val p = ImportProgress.Library(done, total)
                    publish(work, ImportState.Working(p, saving = true))
                    onProgress(p)
                }
            }
            // 4. The taste, the songs this import brings kept (importer.ts `keptNow`): worked out before the record, as on the web
            checkpoint(job, work)
            publish(work, ImportState.Working(ImportProgress.Taste, saving = true))
            onProgress(ImportProgress.Taste)
            taste.refreshLocked(kept = keptBy(playlists, likes), importing = true)
            // 5. The record, and `pending` cleared with it in one write: steps 1 to 4 are safe to run again, this one is the end
            storeRecord(added = 1, playlists = playlists.size, likes = likes.size, finish = true)
            Triple(r, playlists.size, liked)
        }
        val (result, playlistsIn, likesIn) = written
        if (timing || store.info.value.lastfm != null) startLengths()
        val mine = synchronized(lock) {
            live(work).also {
                if (it) {
                    prepared = null
                    files = emptyList()
                }
            }
        }
        // Its own import's copies only: a newer import's are never touched
        if (mine) clearCopies(work.epoch)
        store.clearStops()
        clearUnfinished()
        Log.i(TAG, "import written: ${result.listens} new listens, ${result.written} plays, ${result.dropped} dropped")
        publish(work, ImportState.Done(result.listens, store.info.value.record?.plays ?: 0, playlistsIn, likesIn))
        _changed.tryEmit(Unit)
    }

    /** [work]'s import is the latest and wasn't cancelled. Under [lock]. */
    private fun live(work: Work): Boolean = live(work.epoch)

    /** Import [e] is the latest and wasn't cancelled. Under [lock]. */
    private fun live(e: Long): Boolean = e == epoch && e > cancelledThrough

    /** Between a step's blocks: stops on the job's cancel, and on the import's (WorkManager's cancel reaches the job later). */
    private fun checkpoint(job: Job, work: Work) {
        job.ensureActive()
        if (!synchronized(lock) { live(work) }) throw ImportCancelled()
    }

    /** Shows [s] for import [e], unless it was cancelled or another has begun since. */
    private fun publishAt(e: Long, s: ImportState): Boolean = synchronized(lock) {
        live(e).also { if (it) _state.value = s }
    }

    /**
     * Shows [s] for [work]'s import, unless the person cancelled it or another import has begun since: a step stopped by a
     * Cancel can still report once before it reaches its next checkpoint, and must not bring back what the Cancel closed.
     */
    private fun publish(work: Work, s: ImportState): Boolean = synchronized(lock) {
        live(work).also { if (it) _state.value = s }
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
        // A stopped job runs again by itself: in this process it takes the same step; after the app's death, resumed() finds it.
        // Checked again here, in the lock that queues it: a Cancel can land while the stop was being counted
        synchronized(lock) { if (queued == null && live(work)) queued = work }
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
                    if (_state.value is ImportState.Working) _state.value = ImportState.Idle
                }
            }
        }
        if (!current) return
        clearCopies(e)
        _changed.tryEmit(Unit)
    }

    /** The copies go, and any part no copy is working on, unless an import begun since [e] has its own there now. */
    private suspend fun clearCopies(e: Long) = disk.withLock {
        if (synchronized(lock) { e == epoch }) {
            val live = synchronized(liveParts) { liveParts.toSet() }
            withContext(io) { intake.clear(live) }
        }
    }

    private fun markUnfinished() {
        store.setUnfinished(now())
        notices.unfinished()
    }

    private fun clearUnfinished() {
        store.setUnfinished(null)
        notices.clear()
    }

    /** service.ts `choose`'s word for files that add nothing (no new plays, no playlists, no liked songs). */
    private fun nothingIn(p: ImportPreview): ImportFailure = when {
        p.same > 0 || p.overlap > 0 -> ImportFailure.NOTHING_NEW
        p.unreadable > 0 -> ImportFailure.UNREADABLE
        else -> ImportFailure.NOT_EXPORT
    }

    /**
     * The import record over the archive as it is now ([added] imports, and the [playlists] and [likes] brought into the library,
     * on top of the last one's counts). [finish]: a commit's end, `pending` cleared in the same write. Under [writes].
     */
    private suspend fun storeRecord(added: Int, playlists: Int = 0, likes: Int = 0, finish: Boolean = false) {
        val (ownFirst, ownLast) = ownSpan()
        val record = SpotifyImporter.record(store.info.value.record, archive.listens(), ownFirst, ownLast, now(), added, playlists, likes)
        if (finish) store.finishCommit(record) else store.setRecord(record)
    }

    /**
     * After a Last.fm run, under the write lock it holds: the import record over the archive as it is now. Scrobbles alone make
     * one, so Your record opens on them (service.ts `runLastfm`: an import counted only when there was no record yet).
     */
    internal suspend fun recordAfterScrobbles() {
        storeRecord(added = if (store.info.value.record == null) 1 else 0)
        taste.refreshLocked()
    }

    /**
     * After Remove your Last.fm scrobbles, under the write lock: with nothing left, the record goes (Last.fm was all there was;
     * when Spotify was asked stays), else it is counted again (service.ts `removeLastfm`).
     */
    internal suspend fun recordAfterScrobblesRemoved() {
        if (archive.count() == 0) {
            // The scrobbles were all there was: no record, and no songs hidden on Spotify, no taste nor songs put back either (the
            // playlists and likes stay)
            library.clearHidden()
            taste.clearLocked(keep = true)
            store.clearRecord()
        } else if (store.info.value.record != null) {
            storeRecord(added = 0)
            taste.refreshLocked()
        }
    }

    /**
     * After a slice of the lengths pass that timed songs, under the write lock it holds: the import record over the archive as
     * it is now, when there is one (service.ts `timeSongs`'s `storeRecord`, no import counted).
     */
    internal suspend fun recordAfterLengths() {
        if (store.info.value.record == null) return
        storeRecord(added = 0)
        taste.refreshLocked()
    }

    /** The scrobbles changed (a run brought some, or they were removed): an open record presses itself again. */
    internal fun archiveWasChanged() {
        _changed.tryEmit(Unit)
    }

    /** importer.ts `finishPending`: an import that stopped after writing plays gets its record now. Under [writes]. */
    private suspend fun finishPending() {
        if (store.pending == null) return
        if (archive.count() > 0) {
            storeRecord(added = if (store.info.value.record == null) 1 else 0)
            taste.refreshLocked()
        }
        store.setPending(null)
    }

    /** The song keys a commit brings into the library (the chosen playlists' songs and the liked songs): kept by the taste. */
    private fun keptBy(playlists: List<ChosenPlaylist>, likes: List<com.stash.core.data.record.ExportSong>): List<String> =
        likes.map { SongKey.songKey(it.title, it.artist) } + playlists.flatMap { p -> p.playlist.items.map { SongKey.songKey(it.title, it.artist) } }

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
                val waits = store.info.value.unfinished != null
                if (!alive && !waits) {
                    // Nothing can be copying yet (files brought now wait for this check): parts left by a dead copy go too
                    disk.withLock { withContext(io) { intake.clearAll() } }
                } else if (alive && !waits && kept().isNotEmpty()) {
                    // A read the app's death interrupted, its job still to run: under way, so new files don't replace it unseen
                    // (files brought while the app was starting wait for this check, and then give way with "busy")
                    synchronized(lock) {
                        if (queued == null) {
                            resumeEpoch = ++epoch
                            _state.value = ImportState.Working(ImportProgress.Open)
                        }
                    }
                }
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

        /** Files in one import, at most (Spotify sends two zips; a folder of loose history files is a few dozen). */
        const val MAX_FILES = 64

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

    @Binds
    abstract fun recordLibrary(library: DaoRecordLibrary): RecordLibrary
}
