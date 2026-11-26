package com.stash.data.download.files

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.stash.core.data.db.LibrarySwaps
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.weblibrary.WebLibraryExporter
import com.stash.core.data.weblibrary.WebLibraryFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State of "Send to computer" (Settings › Library & storage), one step at a time: getting started ([Preparing]), the
 * songs being copied out ([Copying]), waiting for Stash on the computer to start ([Waiting]), the computer taking them
 * ([Taking]: it deletes each file once it has it, so what is left in the folder is what is still to go), the end
 * ([Finished]), and a cancel being carried out ([Cancelling]).
 */
sealed interface SendToComputerState {
    data object Idle : SendToComputerState
    /** Asked for; the copy has not said how many songs yet. */
    data object Preparing : SendToComputerState
    data class Copying(val current: Int, val total: Int) : SendToComputerState
    /**
     * [total] songs ready in the folder; the computer has taken none yet. [rest]: what the send left out (the phone ran
     * out of room after these, and what the rest need even once the computer has these, which frees the room their
     * copies take). [libraryOnly]: the send is your likes, playlists and plays alone, with no songs (the computer has
     * every one). [libraryLeftOut]: the songs went without them (the library couldn't be read or written, was too big, or
     * the phone filled up).
     */
    data class Waiting(
        val total: Int,
        val rest: SendMemory.Rest = SendMemory.Rest(),
        val libraryOnly: Boolean = false,
        val libraryLeftOut: Boolean = false,
    ) : SendToComputerState
    data class Taking(val taken: Int, val total: Int) : SendToComputerState
    /**
     * The computer took [taken] of [total]: all of them (the folder is gone), the ones chosen on it ([chosen]: it removed
     * the list, and the copies it did not want are cleared), or it stopped with some left. With all taken, [rest] is what
     * the send left out: a send again carries the songs left for room, once there is room for them. [libraryOnly]: the
     * send was your likes, playlists and plays alone, and the computer has them.
     */
    data class Finished(
        val taken: Int,
        val total: Int,
        val chosen: Boolean = false,
        val rest: SendMemory.Rest = SendMemory.Rest(),
        val libraryOnly: Boolean = false,
    ) : SendToComputerState
    /** A cancel is being carried out: the copy stopped and its files removed. */
    data object Cancelling : SendToComputerState
    /**
     * The send stopped: [message] says why. [copiesLeft]: copies of the send are left in the folder with no list (there
     * was no room, or another list is in the way), and a [SendToComputerCoordinator.cancel] removes them. [listInTheWay]:
     * another copy of Stash's list is in the folder ([ListInTheWayException]). [noAnswer]: the phone's storage did not
     * answer ([NoAnswerException]).
     */
    data class Error(
        val message: String,
        val copiesLeft: Boolean = false,
        val listInTheWay: Boolean = false,
        val noAnswer: Boolean = false,
    ) : SendToComputerState
}

/** What a send would copy: [songs] downloaded songs, [bytes] in all, leaving out the [onComputer] the computer has. */
data class SendCount(val songs: Int, val bytes: Long, val onComputer: Int)

/**
 * "Send to computer": copies every downloaded song into `Download/Stash transfer/`, where a computer sees it over USB
 * ("File transfer"), with `stash-transfer.json` saying which song each file is (and its format, quality and playlists,
 * to choose by). Stash for desktop, with the phone plugged in, takes the songs chosen there into its library as
 * downloaded songs (matched by ISRC, then title and artist), deleting each from the phone once it has it; then the
 * folder, or, with songs not chosen left in it, just the list, and the phone clears those copies.
 *
 * Downloads live in the app's private storage (or a picked folder), which a computer cannot reach; this copy is the
 * one way across that needs no developer options. It goes through MediaStore's Downloads collection
 * ([MediaStoreSendFolder]), so it needs no storage permission (Android 10 and later).
 *
 * The copy is tens of gigabytes for a big library, so it is built to be left alone and to survive being stopped:
 * - it runs as one unique piece of WorkManager work in the foreground ([SendToComputerWorker]), with a progress
 *   notification, so Android does not freeze it when the app leaves the screen or the screen turns off;
 * - there is only ever one: [start] marks the send as begun before it returns, and the work is unique;
 * - a send that was stopped (the app closed, the phone restarted) is carried on, not begun again ([SongSender]);
 * - [cancel] waits for the copy to really stop, then removes everything it wrote (finished at the next look when the
 *   app is killed meanwhile);
 * - a clear-up of the copies and a copy never run at once ([folderBusy]).
 *
 * After the copy it watches the folder and counts what the computer has taken, so the phone says how far along it is.
 * Nothing in the library changes: the phone keeps its downloads.
 *
 * It remembers the songs the computer has ([SendMemory]): each one whose copy the computer took (or deleted, having it
 * already), so the next send copies only the others, until [forget].
 *
 * Each send also carries the library ([SongSender.LIBRARY_NAME], [writeLibrary]): the likes, playlists and plays, for the
 * computer to bring in. It is neither a song nor the list: the computer taking it, leaving it or never getting it changes
 * nothing of what the phone counts as taken, and a send goes without it when it can't be written. With every song on the
 * computer already, a send carries it alone, with a list of no songs.
 */
@Singleton
class SendToComputerCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val library: WebLibraryExporter,
) {
    private val _state = MutableStateFlow<SendToComputerState>(SendToComputerState.Idle)
    val state: StateFlow<SendToComputerState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchJob: Job? = null
    private val lock = Any()

    private val workManager get() = WorkManager.getInstance(context)

    private val memory by lazy { SendMemory(File(context.filesDir, MEMORY_FILE), LibrarySwaps.count(context)) }

    /**
     * Held while the folder is written or cleared: by [copy] for its whole body, and by every clear-up of the copies
     * ([clearCopies]) with the look that decided it. So the two never run at once: a clear-up removes nothing until the
     * copy has returned (it would write on behind it), and a send tapped during a clear-up copies once that is done, its
     * copies and its record untouched by it. Not reentrant: nothing that holds it calls what takes it.
     */
    @VisibleForTesting internal val folderBusy = Mutex()

    /**
     * There from a cancel's tap until its clear-up is done ([clearCopies]): a clear-up cut short (the app killed before or
     * during it) is finished by [resume], shown as stopping, so the copies, tens of gigabytes, are not left in the folder
     * unseen.
     */
    private val stopMark get() = File(context.filesDir, STOP_FILE)

    /** The same for a clear-up of the copies the computer left once it was done with the send: finished with nothing shown. */
    private val leftMark get() = File(context.filesDir, LEFT_FILE)

    /**
     * There once the clear-up under way has begun removing copies: from then on a copy gone is its own, not one the
     * computer took. An empty file, as the marks are: a phone too full to write any data can still make one.
     */
    private val clearedMark get() = File(context.filesDir, CLEARED_FILE)

    /** A test's folder, in place of MediaStore's. */
    @VisibleForTesting internal var folderForTest: SendFolder? = null

    /** What a send would copy: the downloaded songs the computer doesn't have, their size, and how many it has. */
    suspend fun count(): SendCount {
        val songs = trackDao.getAllDownloaded().filter { !it.filePath.isNullOrBlank() }
        val has = withContext(Dispatchers.IO) { runCatching { memory.onComputer() }.getOrDefault(emptySet()) }
        val (there, toSend) = songs.partition { it.id in has }
        return SendCount(toSend.size, toSend.sumOf { it.fileSizeBytes.coerceAtLeast(0) }, there.size)
    }

    /** From now on a send copies every song again, the ones the computer has too. */
    suspend fun forget() {
        withContext(Dispatchers.IO) { runCatching { memory.forget() } }
    }

    /** Begins a send, unless one is under way. The state says so before this returns: a second tap finds it busy. */
    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _state.value = SendToComputerState.Error("Send to computer needs Android 10 or newer.")
            return
        }
        synchronized(lock) {
            if (busy(_state.value)) return
            watchJob?.cancel()
            _state.value = SendToComputerState.Preparing
        }
        // Unique and KEEP: were the state ever wrong, there would still be one copy, not two.
        workManager.enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SendToComputerWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build(),
        )
    }

    /**
     * What is there from before, shown where it is: the copy still running (the app's screen was closed, or Android
     * restarted the work), a clear-up cut short (finished now), or a finished send the computer has not taken yet.
     * Nothing when idle with no send there.
     */
    fun resume() {
        if (_state.value !is SendToComputerState.Idle || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        scope.launch {
            if (copyUnderWay()) {
                synchronized(lock) { if (_state.value is SendToComputerState.Idle) _state.value = SendToComputerState.Preparing }
                return@launch
            }
            // Looked at and seen to with the folder to itself: a send tapped meanwhile copies once this is done.
            folderBusy.withLock {
                // A send begun since sees to what is there itself.
                if (_state.value !is SendToComputerState.Idle) return@launch
                val stopping = stopMark.exists()
                if (stopping || leftMark.exists()) {
                    // A clear-up was cut short (the app killed before or during it, or the folder didn't answer):
                    // finished now. A cancel's is shown stopping; the copies the computer left go with nothing shown.
                    if (stopping) {
                        synchronized(lock) {
                            if (_state.value !is SendToComputerState.Idle) return@launch
                            _state.value = SendToComputerState.Cancelling
                        }
                    }
                    try {
                        clearCopies(if (stopping) stopMark else leftMark)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Tried again at the next look.
                    }
                    if (stopping) _state.value = SendToComputerState.Idle
                    return@launch
                }
                val total = try {
                    sentBefore()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The folder didn't answer: no news. Nothing is let go or removed; the next look decides.
                    return@launch
                }
                if (total == null) {
                    // The computer finished with a send while the app was away: what it took is kept, the copies left go.
                    if (runCatching { memory.sending().isNotEmpty() }.getOrDefault(false)) {
                        try {
                            clearCopies(leftMark)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Finished at the next look.
                        }
                    } else {
                        // A library file left with no list (the computer normally removes it) goes: no send names it. Only
                        // that file: copies kept for a send to carry on stay.
                        runCatching { folder().delete(SongSender.LIBRARY_NAME) }
                    }
                    return@launch
                }
                // What the send left out is told again, as it was before the app closed: songs it didn't copy, and the
                // library when no library file is beside the list (the computer removes it only with the list). A list
                // of no songs is the library alone. A look that isn't answered claims nothing left out.
                val rest = runCatching { memory.rest() }.getOrDefault(SendMemory.Rest())
                val libraryOnly = total == 0
                val libraryLeftOut = !libraryOnly &&
                    runCatching { folder().files().none { it.name == SongSender.LIBRARY_NAME && !it.pending } }.getOrDefault(false)
                synchronized(lock) {
                    if (_state.value !is SendToComputerState.Idle) return@launch
                    _state.value = SendToComputerState.Waiting(total, rest, libraryOnly, libraryLeftOut)
                    watchJob = scope.launch { runCatching { watch(total, rest, libraryOnly) } }
                }
            }
        }
    }

    /** Stops: the copy, or the wait for the computer. Returns at once; the files are gone when the state is Idle again. */
    fun cancel() {
        synchronized(lock) {
            if (_state.value is SendToComputerState.Cancelling || _state.value is SendToComputerState.Idle) return
            _state.value = SendToComputerState.Cancelling
            // Marked at once: the app killed from here on (swiped away during the wait below) still has the copies, and
            // a list the copy writes meanwhile, removed at the next look.
            mark(stopMark)
            watchJob?.cancel()
            watchJob = null
        }
        scope.launch {
            try {
                workManager.cancelUniqueWork(WORK_NAME)
                // The copy stops between blocks. The work reads as cancelled before its copy has stopped, so the copy
                // itself is waited for too ([clearStopped]); the work is, in case it had not started.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    clearStopped { workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).first { infos -> infos.all { it.state.isFinished } } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // What is left goes with the next send, or the next look once the clear-up has begun.
            }
            _state.value = SendToComputerState.Idle
        }
    }

    /** Puts away the result. A send the computer stopped partway stays in the folder, to be taken when it carries on. */
    fun dismiss() {
        synchronized(lock) {
            if (_state.value is SendToComputerState.Finished || _state.value is SendToComputerState.Error) {
                watchJob?.cancel()
                watchJob = null
                _state.value = SendToComputerState.Idle
            }
        }
    }

    /**
     * The copy itself, run by [SendToComputerWorker]. [onProgress] is told as it goes (for the notification). Throws
     * CancellationException when cancelled; anything else is shown as the error.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    internal suspend fun copy(onProgress: suspend (done: Int, total: Int) -> Unit) {
        try {
            folderBusy.lock()
        } catch (e: CancellationException) {
            // Stopped (the notification's Cancel) as it waited behind a clear-up of the copies: nothing was copied, and
            // the card still says the send is starting. Put right as any stop from outside is.
            stoppedFromOutside()
            throw e
        }
        try {
            copyAlone(onProgress)
        } finally {
            folderBusy.unlock()
        }
    }

    /** [copy], with [folderBusy] held. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun copyAlone(onProgress: suspend (done: Int, total: Int) -> Unit) {
        val ownClear = synchronized(lock) {
            // Cancelled as the work was starting: leave the state, and the clear-up it marked, to the cancel.
            if (_state.value is SendToComputerState.Cancelling) return
            // This send clears the folder itself: a clear-up cut short is done with. Once it had begun removing, the
            // copies gone were its own, not taken.
            clearBegun().also { unmark() }
        }
        // A send of the library alone (the computer has every song), and why its library file wasn't written, when it
        // wasn't: what the card says when it stops is about that, not about copies.
        var libraryAlone = false
        var noLibrary: NoLibrary? = null
        try {
            // A send from before is cleared by this one: what the computer took of it is kept first.
            if (ownClear) runCatching { memory.doneSending() } else letGo()
            val downloaded = trackDao.getAllDownloaded().filter { !it.filePath.isNullOrBlank() }
            if (downloaded.isEmpty()) {
                // An earlier send's copies go; ones that can't be removed now go with the next send, and this is still
                // the answer.
                try {
                    folder().clear()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Left for the next send.
                }
                _state.value = SendToComputerState.Error("There are no downloaded songs to send.")
                return
            }
            val has = runCatching { memory.onComputer() }.getOrDefault(emptySet())
            // None when the computer has every song: the send is then the library alone, with a list of no songs.
            val songs = downloaded.filter { it.id !in has }
            libraryAlone = songs.isEmpty()
            // Each song's playlists, for choosing on the computer; a send goes without them rather than not at all.
            val playlists = if (songs.isEmpty()) emptyMap() else {
                runCatching { playlistDao.playlistNamesOfSongs() }.getOrDefault(emptyList()).groupBy({ it.trackId }, { it.name })
            }
            var told = 0L
            val result = SongSender(folder(), ::freeBytes).send(
                songs.map { sendSong(it, playlists[it.id].orEmpty()) },
                // Which song each file is, and what the send left out, kept before the computer can take any (a send
                // goes on without it).
                beforeList = { listed, sent ->
                    runCatching {
                        memory.startSending(
                            listed.associate { (song, file) -> file.name to SendMemory.Sent(song.id, file.id) },
                            SendMemory.Rest(sent.failed, sent.full, sent.noRoom),
                        )
                    }
                },
                library = { sentAt -> writeLibrary(sentAt).also { noLibrary = it } == null },
            ) { done, total ->
                // A send of the library alone has no songs to count: it stays "Getting songs ready…".
                if (total > 0 && _state.value !is SendToComputerState.Cancelling) {
                    _state.value = SendToComputerState.Copying(done.coerceAtMost(total - 1), total)
                }
                val now = System.currentTimeMillis()
                if (now - told >= NOTIFY_EVERY_MS || done == total) {
                    told = now
                    runCatching { onProgress(done, total) }
                }
            }
            // A cancel that came during the last progress call or the list's write (the notification's Cancel stops
            // the work, not this): carried out, rather than the send left waiting for the computer.
            currentCoroutineContext().ensureActive()
            if (result.entries.isEmpty() && !result.library) {
                _state.value = SendToComputerState.Error(
                    when {
                        !libraryAlone -> "None of the songs could be copied."
                        // Sending again can't help: it is as big next time.
                        noLibrary == NoLibrary.TOO_BIG -> "Your library is too big to send this way."
                        noLibrary == NoLibrary.NO_ROOM -> LIBRARY_NO_ROOM
                        else -> "Your likes, playlists and plays could not be copied. Tap Send to computer again."
                    },
                )
                return
            }
            synchronized(lock) {
                if (_state.value is SendToComputerState.Cancelling) return
                val rest = SendMemory.Rest(result.failed, result.full, result.noRoom)
                val libraryOnly = result.entries.isEmpty()
                _state.value = SendToComputerState.Waiting(result.entries.size, rest, libraryOnly, libraryLeftOut = !result.library)
                watchJob?.cancel()
                watchJob = scope.launch { runCatching { watch(result.entries.size, rest, libraryOnly) } }
            }
        } catch (e: CancellationException) {
            stoppedFromOutside()
            throw e
        } catch (e: NoRoomException) {
            // There is no list, so nothing of it can be taken: the copies stay for the send to carry on, unnoted. The
            // library alone has no copies: its file is gone already ([SongSender]), and the room it needs is said plainly.
            runCatching { memory.doneSending() }
            if (_state.value !is SendToComputerState.Cancelling) {
                _state.value = SendToComputerState.Error(if (libraryAlone) LIBRARY_NO_ROOM else noRoom(e.needed), copiesLeft = e.copies > 0)
            }
        } catch (e: ListInTheWayException) {
            // Nothing is noted as taken (the computer may delete the folder with the other send in it), and the copies
            // stay for the next send, or go with Remove the copies (there are some: a list is only written to name them).
            // The library alone leaves none: its file is gone already ([SongSender]), so there is nothing to remove.
            runCatching { memory.doneSending() }
            if (_state.value !is SendToComputerState.Cancelling) {
                _state.value = SendToComputerState.Error(e.message.orEmpty(), copiesLeft = !libraryAlone, listInTheWay = true)
            }
        } catch (e: NoAnswerException) {
            // Said plainly on the card ([SendToComputerState.Error.noAnswer]).
            if (_state.value !is SendToComputerState.Cancelling) _state.value = SendToComputerState.Error(e.message.orEmpty(), noAnswer = true)
        } catch (e: Exception) {
            if (_state.value !is SendToComputerState.Cancelling) _state.value = SendToComputerState.Error(e.message ?: "The songs could not be copied.")
        }
    }

    /**
     * The copy was stopped by something other than [cancel]. The notification's own Cancel cancels the work directly:
     * that is finished the same way (the files removed, the state Idle). Android stopping the work to run it again
     * later is left alone: the send carries on when it does.
     */
    private fun stoppedFromOutside() {
        if (_state.value is SendToComputerState.Cancelling) return
        scope.launch {
            delay(500)
            val cancelled = runCatching {
                workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).first().all { it.state == WorkInfo.State.CANCELLED }
            }.getOrDefault(false)
            if (!cancelled) return@launch
            synchronized(lock) {
                if (_state.value !is SendToComputerState.Preparing && _state.value !is SendToComputerState.Copying) return@launch
                _state.value = SendToComputerState.Cancelling
                // Marked at once, as a cancel is: finished at the next look if the app is killed before it is done.
                mark(stopMark)
            }
            try {
                // The copy is returning (this is called from it): nothing is removed before it has.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) clearStopped()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // What is left goes with the next send, or the next look once the clear-up has begun.
            }
            _state.value = SendToComputerState.Idle
        }
    }

    /**
     * The clear-up of a send stopped ([cancel], the notification's Cancel), once the work is over ([stopped]) and the copy
     * itself has returned: it would write on behind the clear-up otherwise. A copy not stopped in [STOP_WAIT_MS] is
     * stuck, and the copies go anyway.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun clearStopped(stopped: suspend () -> Unit = {}) {
        // Set only once the lock is held: a timeout as it is taken never leaves it held with no one to let it go.
        var alone = false
        withTimeoutOrNull(STOP_WAIT_MS) {
            stopped()
            folderBusy.lock()
            alone = true
        }
        try {
            clearCopies(stopMark)
        } finally {
            if (alone) folderBusy.unlock()
        }
    }

    /**
     * Lets the send in the folder go and removes every copy, in a way that outlives the app: [marker] ([stopMark] for a
     * cancel's, [leftMark] for the copies the computer left) is there until it is all done, and [resume] finishes a
     * clear-up it finds. What the computer took is noted first ([present]: what the folder holds now, when just asked),
     * unless an earlier go had begun removing ([clearedMark]). A phone too full to write the let-go is cleared anyway
     * (that frees room) and let go after. Throws when the folder can't be cleared or the send can't be let go; the marks
     * stay then. Run with [folderBusy] held (or the copy stuck: [clearStopped]).
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun clearCopies(marker: File, present: List<SentFile>? = null) {
        mark(marker)
        if (!clearBegun()) noteTaken(present)
        runCatching { memory.doneSending() }
        // From here on a copy gone is this clear-up's own.
        mark(clearedMark)
        folder().clear()
        memory.doneSending()
        unmark()
    }

    /** Makes [file], an empty mark (no data to write, so a full phone can still make it). */
    private fun mark(file: File) {
        runCatching { file.createNewFile() }
    }

    /**
     * No clear-up is under way: the folder holds nothing to clear up. The stage goes first: left alone, it would say a
     * later clear-up had begun removing.
     */
    private fun unmark() {
        clearedMark.delete()
        stopMark.delete()
        leftMark.delete()
    }

    /** Whether a clear-up under way had begun removing copies, so the copies gone since are its own. */
    private fun clearBegun(): Boolean = clearedMark.exists()

    /**
     * Keeps what the computer has taken of the send in the folder: every file of it gone, but not to the Trash. [present]
     * is what the folder holds now, when just asked. False when it could not be told (the folder or the Trash didn't
     * answer, or the memory couldn't be written): nothing was decided, and the next look tries again.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun noteTaken(present: List<SentFile>? = null): Boolean = runCatching {
        if (memory.sending().isEmpty()) return true
        val folder = folder()
        memory.taken((present ?: folder.files()).map { it.name }.toSet(), folder.trashed())
    }.isSuccess

    /**
     * The send in the folder is over: what the computer took of it is kept, and it is let go. [present]: what the folder
     * holds now, when just asked (the record then goes by the same answer as the one that ended the send).
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun letGo(present: List<SentFile>? = null) {
        noteTaken(present)
        runCatching { memory.doneSending() }
    }

    private fun busy(state: SendToComputerState) = state is SendToComputerState.Preparing || state is SendToComputerState.Copying ||
        state is SendToComputerState.Waiting || state is SendToComputerState.Taking || state is SendToComputerState.Cancelling

    private suspend fun copyUnderWay(): Boolean = runCatching {
        workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).first().any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    }.getOrDefault(false)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun folder(): SendFolder = folderForTest ?: MediaStoreSendFolder(context.contentResolver)

    /** How many songs the list in the folder names, when this app's list is there (none: a send of the library alone). */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun sentBefore(): Int? {
        val text = folder().readList() ?: return null
        val songs = (kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonObject)?.get("songs") as? JsonArray
        return songs?.size
    }

    /** Why a send's library file wasn't written: it is too big to send, the phone has no room for it, or it failed. */
    private enum class NoLibrary { TOO_BIG, NO_ROOM, FAILED }

    /**
     * Writes the library file of the send numbered [sentAtMs] ([SongSender.LIBRARY_NAME]: likes, playlists and plays, as
     * [WebLibraryExporter.forComputer] reads them). Null when it was written, else why not. It throws only when cancelled:
     * a library that can't be read, is too big for the computer or can't be written is logged, and the send goes without
     * it.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun writeLibrary(sentAtMs: Long): NoLibrary? {
        val why = try {
            val file = library.forComputer(System.currentTimeMillis(), sentAtMs)
            if (file == null) {
                Log.w(TAG, "The library is too big for the computer to read, so the songs go without it")
                return NoLibrary.TOO_BIG
            }
            folder().writeLibrary { out -> WebLibraryFile.write(file, out) }
            Log.i(TAG, "Library file written: ${file.likes.size} likes, ${file.playlists.size} playlists, ${file.history.size} plays")
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Whatever it is (a library too big to hold in memory too), the songs still go.
            e
        }
        Log.w(TAG, "The library file was not written, so the songs go without it", why)
        return when {
            why is OutOfMemoryError -> NoLibrary.TOO_BIG
            SongSender.full(why) -> NoLibrary.NO_ROOM
            else -> NoLibrary.FAILED
        }
    }

    /**
     * Counts what the computer has taken, every second, until it has all of it (the folder is empty), or all it chose
     * (it removed the list: the copies it did not want are cleared, the songs stay in the library). Once it has
     * started, a minute with nothing more taken shows it stopped ([Finished] with some left), and the count goes on: a
     * computer that carries on (the phone plugged back in) is shown taking again. Ends with all taken, [cancel] or
     * [dismiss]. [rest]: what the send left out, told once the computer has all of it. [libraryOnly]: the send is the
     * library alone, and its end says so.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun watch(total: Int, rest: SendMemory.Rest = SendMemory.Rest(), libraryOnly: Boolean = false) {
        val job = currentCoroutineContext()[Job]
        // Shown unless the watch was stopped meanwhile ([cancel], [dismiss]): a look under way doesn't undo that.
        fun show(s: SendToComputerState) {
            synchronized(lock) {
                if (job?.isActive != false && _state.value !is SendToComputerState.Cancelling) _state.value = s
            }
        }
        var taken = 0
        var changedAt = System.currentTimeMillis()
        var seen = -1
        while (true) {
            delay(WATCH_EVERY_MS)
            val all = try {
                folder().files()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No answer from the folder this time is no news: it is asked again next time.
                continue
            }
            // A look that answers once the watch was stopped ([cancel], [dismiss]) is dropped: what it saw going may be a
            // clear-up's own removing, not the computer taking.
            currentCoroutineContext().ensureActive()
            // Each song the computer takes is kept as it goes, so a send stopped partway still counts. A look that could
            // not be noted (the Trash didn't answer) is noted at the next.
            if (all.size != seen && noteTaken(all)) seen = all.size
            val files = all.filter { !it.pending }
            val listLeft = files.any { it.name == SongSender.LIST_NAME }
            // The library file is neither a song nor the list: there or gone, it is no song left or taken.
            val songsLeft = files.count { it.name != SongSender.LIST_NAME && it.name != SongSender.LIBRARY_NAME }
            if (songsLeft == 0 && !listLeft) {
                letGo(all)
                // The computer normally removes the library file; one it left goes now.
                if (all.any { it.name == SongSender.LIBRARY_NAME }) removeLibrary()
                show(SendToComputerState.Finished(total, total, rest = rest, libraryOnly = libraryOnly))
                return
            }
            if (!listLeft) {
                // It is done, and took the songs chosen there: shown at once. The copies it did not want are cleared
                // behind that, with what it took noted first (those were not taken), in a way that outlives the app; a
                // send tapped meanwhile copies once they are gone.
                show(SendToComputerState.Finished((total - songsLeft).coerceIn(0, total), total, chosen = true))
                try {
                    folderBusy.withLock { clearCopies(leftMark, all) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Finished at the next look.
                }
                return
            }
            val nowTaken = (total - songsLeft).coerceIn(0, total)
            if (nowTaken != taken) {
                taken = nowTaken
                changedAt = System.currentTimeMillis()
            }
            if (taken == 0) continue
            show(
                if (System.currentTimeMillis() - changedAt > STOPPED_AFTER_MS) {
                    SendToComputerState.Finished(taken, total)
                } else {
                    SendToComputerState.Taking(taken, total)
                },
            )
        }
    }

    /**
     * Removes a library file the computer left once it had the whole send, with the folder to itself ([folderBusy]) and
     * only while the watch is still on: a send tapped since (which stops the watch) writes its own, never removed here. One
     * that can't be removed now goes with the next send.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun removeLibrary() {
        try {
            folderBusy.withLock {
                currentCoroutineContext().ensureActive()
                folder().delete(SongSender.LIBRARY_NAME)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Left for the next send.
        }
    }

    private fun sendSong(track: TrackEntity, playlists: List<String>): SendSong = SendSong(
        name = fileName(track),
        bytes = sizeOf(track),
        open = { open(track) },
        entry = { name, bytes -> entry(track, name, bytes, playlists) },
        id = track.id,
    )

    private fun open(track: TrackEntity): InputStream? {
        val path = track.filePath ?: return null
        return if (path.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(path))
        } else {
            File(path).takeIf { it.isFile }?.inputStream()
        }
    }

    /** The room left where the copies go (shared storage), or null when it can't be asked. */
    private fun freeBytes(): Long? =
        runCatching { android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path).availableBytes }.getOrNull()

    /** The file's real size now (not the library's note of it), or null when it can't be asked. */
    private fun sizeOf(track: TrackEntity): Long? {
        val path = track.filePath ?: return null
        return runCatching {
            if (path.startsWith("content://")) {
                context.contentResolver.query(Uri.parse(path), arrayOf(OpenableColumns.SIZE), null, null, null)
                    ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
            } else {
                File(path).takeIf { it.isFile }?.length()
            }
        }.getOrNull()?.takeIf { it > 0 }
    }

    companion object {
        const val WORK_NAME = "send_to_computer"
        private const val TAG = "SendToComputer"
        /** [SendMemory]'s file, in the app's own storage. */
        private const val MEMORY_FILE = "send_to_computer.json"
        /** There while a cancel's clear-up removes the copies ([stopMark]). */
        private const val STOP_FILE = "send_to_computer.clearing"
        /** There while the copies the computer left are removed ([leftMark]). */
        private const val LEFT_FILE = "send_to_computer.clearing-left"
        /** There once a clear-up has begun removing copies ([clearedMark]). */
        private const val CLEARED_FILE = "send_to_computer.cleared"
        private const val WATCH_EVERY_MS = 1_000L
        /** Once the computer has started, this long with nothing more taken means it has stopped. */
        private const val STOPPED_AFTER_MS = 60_000L
        /** How long a cancel waits for the copy to stop before removing the files anyway. */
        private const val STOP_WAIT_MS = 30_000L
        private const val NOTIFY_EVERY_MS = 1_000L

        /** What a send of the library alone says when the phone has no room for it. */
        internal const val LIBRARY_NO_ROOM =
            "Not enough room on this phone for your likes, playlists and plays. Free some space, then tap Send to computer again."

        /** What a send that has no room says: how much more it needs, and where room often hides. */
        internal fun noRoom(needed: Long): String {
            val size = if (needed >= 1_000_000_000L) "%.1f GB".format(java.util.Locale.ROOT, needed / 1e9) else "${(needed / 1_000_000L).coerceAtLeast(1)} MB"
            // No more than the room kept free is missing: the copies themselves fit.
            val what = if (needed <= SongSender.SPARE) {
                "the copies would leave it less than 1 GB free, which it needs to keep working well. Free $size more"
            } else {
                "the copies need $size more. Free some space"
            }
            return "Not enough room on this phone: $what, then tap Send to computer again: " +
                "it carries on where it stopped. Files you deleted may still be taking room in the Files app's Trash."
        }

        /** The file's name in the folder: the song's row id (unique) and its own extension. */
        internal fun fileName(track: TrackEntity): String {
            val path = track.filePath.orEmpty()
            val ext = path.substringAfterLast('/').substringAfterLast("%2F").substringAfterLast('.', "")
                .lowercase().takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
                ?: track.fileFormat.lowercase().ifBlank { "m4a" }
            return "${track.id}.$ext"
        }

        /** What the computer needs to know about the song in `name`, and what it is chosen by there (its [playlists]). */
        internal fun entry(t: TrackEntity, name: String, bytes: Long, playlists: List<String> = emptyList()): JsonObject = buildJsonObject {
            put("file", name)
            put("bytes", bytes)
            put("title", t.title)
            put("artist", t.artist)
            if (t.album.isNotBlank()) put("album", t.album)
            if (t.albumArtist.isNotBlank()) put("album_artist", t.albumArtist)
            if (t.durationMs > 0) put("duration_ms", t.durationMs)
            t.isrc?.takeIf { it.isNotBlank() }?.let { put("isrc", it) }
            t.youtubeId?.takeIf { it.isNotBlank() }?.let { put("youtube_id", it) }
            t.spotifyUri?.takeIf { it.isNotBlank() }?.let { put("spotify_uri", it) }
            put("format", t.fileFormat)
            t.bitsPerSample?.let { put("bits", it) }
            t.sampleRateHz?.let { put("sample_rate", it) }
            t.albumArtUrl?.takeIf { it.startsWith("https://") }?.let { put("art_url", it) }
            if (t.stashLikedAt != null) put("liked", true)
            val named = playlists.map(String::trim).filter(String::isNotEmpty).distinct()
            if (named.isNotEmpty()) put("playlists", JsonArray(named.map(::JsonPrimitive)))
        }
    }
}
