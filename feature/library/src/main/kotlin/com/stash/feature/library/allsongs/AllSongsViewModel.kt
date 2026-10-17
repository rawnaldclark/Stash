package com.stash.feature.library.allsongs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.dao.ScannedSongDao
import com.stash.core.data.db.entity.ScanFolderEntity
import com.stash.core.media.PlayerRepository
import com.stash.core.model.PlaybackSource
import com.stash.core.model.Track
import com.stash.feature.library.SortOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class AllSongsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ScannedSongDao,
    private val scanner: AllSongsScanner,
    private val player: PlayerRepository,
    private val covers: ScannedCoverStore,
) : ViewModel() {

    val folders: StateFlow<List<ScanFolderEntity>> = dao.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _sortOrder = MutableStateFlow(SortOrder.ALPHABETICAL)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    /** What the search field shows. The list below filters by it off the main thread. */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** The list as shown: searched, then sorted. Play, shuffle and selection all act on this. */
    val songs: StateFlow<List<Track>> = combine(dao.observeSongs(), _sortOrder, _query) { rows, order, q ->
        sortAndFilter(rows, order, q).map { it.toTrack(covers) }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playingId: StateFlow<Long?> = player.playerState
        .map { it.currentTrack?.id }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val scanMutex = Mutex()

    private var scannedOnce = false

    /** The first time "On this device" opens in this ViewModel's life: scan the folders. Later scans are the Rescan button's. */
    fun rescanOnOpen() {
        if (scannedOnce) return
        scannedOnce = true
        rescan()
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    fun setQuery(query: String) {
        _query.value = query
    }

    /** A line for the status text (the screen's own messages, like a capped Share). */
    fun say(message: String) {
        _status.value = message
    }

    fun rescan() {
        viewModelScope.launch { scanAll() }
    }

    fun addFolder(uri: Uri) {
        viewModelScope.launch {
            if (!takeGrant(uri)) {
                _status.value = "Couldn't get access to that folder"
                return@launch
            }
            // Picked again (typically after access was lost: the SD card was out, or Android dropped
            // the grant). The grant just taken is fresh, so scan it now instead of refusing it.
            if (dao.getFolderByTreeUri(uri.toString()) != null) {
                _status.value = "Already added. Scanning it again."
                scanAll()
                return@launch
            }
            val folders = dao.getFolders()
            scanner.conflictFor(uri, folders)?.let { conflict ->
                releaseGrant(uri, folders)
                _status.value = conflict.message
                return@launch
            }
            val id = dao.insertFolder(
                ScanFolderEntity(
                    treeUri = uri.toString(),
                    displayName = scanner.displayName(uri),
                    addedAt = System.currentTimeMillis(),
                ),
            )
            if (id == -1L) {
                _status.value = "That folder is already added"
                return@launch
            }
            scanAll()
        }
    }

    /** Songs go with the folder (FK cascade), and so do the folder's grant and its cover files. */
    fun removeFolder(folder: ScanFolderEntity) {
        viewModelScope.launch {
            dao.deleteFolder(folder.id)
            releaseGrant(Uri.parse(folder.treeUri), dao.getFolders())
            scanMutex.withLock { scanner.sweepArt() }
        }
    }

    /**
     * Read and write: write is what lets Delete remove a file. A provider that offers no write
     * still gets its read grant, so scanning and playing work and only deleting doesn't.
     */
    private fun takeGrant(uri: Uri): Boolean {
        val resolver = context.contentResolver
        return try {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            true
        } catch (e: SecurityException) {
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                true
            } catch (e2: SecurityException) {
                false
            }
        }
    }

    /**
     * Gives back the persisted grant on [tree] unless something still needs it: Stash's
     * download folder (the same tree URI shares one grant, and releasing it would break downloads)
     * or a scanned folder in [remaining]. Android caps an app's persisted grants, so unused ones
     * shouldn't pile up.
     */
    private suspend fun releaseGrant(tree: Uri, remaining: List<ScanFolderEntity>) {
        if (remaining.any { it.treeUri == tree.toString() }) return
        if (scanner.downloadTreeUri()?.toString() == tree.toString()) return
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                // Already gone (revoked, or never held): nothing to release.
            }
        }
    }

    /**
     * Plays the list from the song with [trackId]. By id, not by list position: a rescan can
     * reorder or shrink the list between the frame the user tapped and this call.
     */
    fun play(trackId: Long) {
        val queue = songs.value
        val index = queue.indexOfFirst { it.id == trackId }
        if (index < 0) return
        viewModelScope.launch { player.setQueue(queue, index, PlaybackSource.AllSongs) }
    }

    fun shuffle() {
        val queue = songs.value.shuffled()
        if (queue.isEmpty()) return
        viewModelScope.launch { player.setQueue(queue, 0, PlaybackSource.AllSongs) }
    }

    /** Each lands right after the playing song; going backwards keeps the selection's order. */
    fun playNext(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            val queued = tracks.asReversed().count { player.addNext(it) }
            if (queued > 0) _status.value = "Playing $queued next"
        }
    }

    fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            if (player.addToQueue(tracks)) _status.value = "Added ${tracks.size} to the queue"
        }
    }

    /**
     * Deletes the files themselves, then their rows, then takes them out of the queue. A file that is
     * already gone loses its row too; one that can't be deleted (no write access) keeps it and is
     * counted in the status. The files and rows go under the scan lock: a scan that listed a file
     * before it was deleted must not insert its row afterwards.
     */
    fun deleteSongs(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            _status.value = "Deleting ${tracks.size}..."
            val gone = ArrayList<Track>()
            var failed = 0
            scanMutex.withLock {
                withContext(Dispatchers.IO) {
                    for (track in tracks) {
                        val path = track.filePath
                        if (path == null) {
                            failed++
                            continue
                        }
                        when (scanner.deleteFile(path)) {
                            FileDeletion.DELETED, FileDeletion.GONE -> gone.add(track)
                            FileDeletion.FAILED -> failed++
                        }
                    }
                }
                gone.mapNotNull { it.filePath }.chunked(500).forEach { dao.deleteByUrisAnyFolder(it) }
            }
            player.evictFromQueue(gone.mapTo(HashSet()) { it.id })
            _status.value = if (failed > 0) {
                "Deleted ${gone.size}. Couldn't delete $failed (add the folder again to allow deleting)."
            } else {
                "Deleted ${gone.size}"
            }
            scanMutex.withLock { scanner.sweepArt() }
        }
    }

    private suspend fun scanAll() = scanMutex.withLock {
        _scanning.value = true
        var added = 0
        var updated = 0
        var removed = 0
        var failed = 0
        var incomplete = 0
        try {
            for (folder in dao.getFolders()) {
                try {
                    val r = scanner.scan(folder)
                    added += r.added
                    updated += r.updated
                    removed += r.removed
                    if (r.incomplete) incomplete++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Removed while it was being scanned (Remove doesn't wait for the scan): its
                    // songs went with it and the next insert hit the missing folder. Not a failure.
                    if (dao.getFolders().none { it.id == folder.id }) continue
                    Log.w(TAG, "scan failed for folder ${folder.id}", e)
                    failed++
                }
            }
            scanner.sweepArt()
        } finally {
            _scanning.value = false
        }
        _status.value = when {
            failed > 0 -> "$failed folder(s) couldn't be read (moved, or access lost?). Add it again to restore access."
            incomplete > 0 -> "Some subfolders couldn't be read; their songs were kept."
            added + updated + removed > 0 -> "Added $added, updated $updated, removed $removed"
            else -> null
        }
    }

    private companion object {
        const val TAG = "AllSongs"
    }
}