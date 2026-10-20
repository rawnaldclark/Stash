package com.stash.data.cloud.scan

import android.util.Log
import com.stash.core.data.db.dao.CloudDao
import com.stash.core.data.db.entity.CloudFolderEntity
import com.stash.core.data.db.entity.CloudItemEntity
import com.stash.core.data.db.entity.CloudScanStatus
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.CloudProvider
import com.stash.core.model.CloudRef
import com.stash.core.model.MusicSource
import com.stash.data.cloud.net.LinkExpiredException
import com.stash.data.cloud.net.RequestPriority
import com.stash.data.cloud.playback.CloudLinkResolver
import com.stash.data.cloud.provider.CloudLink
import com.stash.data.cloud.provider.CloudStorageProvider
import com.stash.data.cloud.provider.CursorExpiredException
import com.stash.data.cloud.provider.ScanEntry
import com.stash.data.cloud.provider.ScanRoot
import com.stash.data.cloud.tags.AudioTags
import com.stash.data.cloud.tags.CachingRangeReader
import com.stash.data.cloud.tags.TagReadBudgetExceeded
import com.stash.data.cloud.tags.TagReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.io.IOException

/** Saves cover art bytes and returns where they landed (null when they aren't an image). */
fun interface ArtSaver {
    fun save(bytes: ByteArray, mimeType: String?): String?
}

/** Reads a byte range of a file through a link (with the link's headers, Google Drive's token). */
fun interface RangeSource {
    suspend fun fetch(link: CloudLink, offset: Long, length: Int): ByteArray
}

/** What one folder's scan did. */
data class CloudScanResult(val added: Int, val updated: Int, val removed: Int, val unchanged: Int)

/**
 * Turns one picked cloud folder into library tracks, for any [CloudStorageProvider].
 *
 * **Resumable.** The provider's cursor is saved on the folder row after every page, so a scan
 * cut short (no Wi-Fi, process death) carries on from the page it stopped at. Files whose
 * content version (OneDrive cTag, Dropbox rev) matches what we stored cost nothing more than
 * being listed: only new or changed files get their tags read.
 *
 * **Full and incremental passes.** No cursor = full pass: the generation goes up, every file
 * listed is stamped with it, and when the pass completes, songs still carrying an older
 * generation are gone from the cloud and are removed ([CloudDao.removeStale]). A saved cursor
 * after a completed pass = incremental: the provider lists only changes, deletions included.
 * An expired cursor (OneDrive 410, Dropbox reset) restarts as a full pass.
 *
 * **Tags** come from a ranged read of each new/changed file ([TagReader], 256 KB first, more only
 * when the format needs it) through the provider's short-lived link; anything they leave out is
 * filled from the folder path ([CloudMetadataFallbacks]). Cover art: embedded picture first, else
 * a sibling cover/folder/front/album image in the same folder, which may be listed on a later page
 * than the songs, so songs without art wait for it until the pass ends.
 *
 * Connectivity failures propagate (the worker retries the scan later, from the saved cursor).
 * Any other trouble with ONE file (bytes that can't be parsed, a read past the budget, a link the
 * provider won't give or the host keeps refusing) files that song by its path and moves on, with
 * no content version stored so the next full pass reads it again: one bad file never stalls the
 * folder.
 *
 * **Moves and renames.** A moved or renamed file keeps its id and content version: it is
 * "unchanged", but its path and parent are refreshed so a later deletion of its old folder can't
 * reach it. Deletions are applied at the end of the pass, and never to a file the pass listed as
 * live, whatever order the provider sent them in (Dropbox reports a folder rename as `deleted`
 * for the old path plus the files at the new one; OneDrive a move, then the old folder's delete).
 *
 * **One scan per folder.** The scanner is a singleton and holds a per-folder lock for the whole
 * scan, so the one-off and periodic workers never scan one folder at once; removing a folder or
 * account takes the same lock ([withFolderLock]) and so waits for a running scan to stop.
 */
class CloudScanner(
    private val dao: CloudDao,
    private val providers: Map<CloudProvider, CloudStorageProvider>,
    private val links: CloudLinkResolver,
    private val ranges: RangeSource,
    private val art: ArtSaver,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val folderLocks = ConcurrentHashMap<Long, Mutex>()

    /** Runs [block] while no scan of [folderId] runs (and none starts). */
    suspend fun <T> withFolderLock(folderId: Long, block: suspend () -> T): T =
        folderLocks.getOrPut(folderId) { Mutex() }.withLock { block() }

    /** Scans folder row [folderId]; returns null when it no longer exists. */
    /**
     * Scans [folderId]. [full] forces a full pass even with a saved cursor ("Rescan now"): the
     * listing is cheap, unchanged files are skipped by version, and files whose tags couldn't be
     * read before (no version stored) are read again.
     */
    suspend fun scan(folderId: Long, full: Boolean = false, onProgress: (processed: Int) -> Unit = {}): CloudScanResult? =
        withFolderLock(folderId) { scanLocked(folderId, full, onProgress) }

    private suspend fun scanLocked(folderId: Long, full: Boolean, onProgress: (processed: Int) -> Unit): CloudScanResult? {
        var folder = dao.getFolder(folderId) ?: return null
        val account = dao.getAccount(folder.accountId) ?: return null
        val providerKind = CloudProvider.fromId(account.provider) ?: return null
        val provider = providers[providerKind]?.takeIf { it.isConfigured } ?: return null
        val root = ScanRoot(folder.itemId, folder.path)

        if (full || folder.scanCursor == null) folder = startFullPass(folder)
        folder = folder.copy(lastScanStatus = CloudScanStatus.RUNNING.name, lastScanError = null)
        dao.updateFolder(folder)

        val pass = Pass(provider, providerKind, folder, root)
        var cursor = folder.scanCursor
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = try {
                provider.scanPage(folder.accountId, root, cursor)
            } catch (e: CursorExpiredException) {
                folder = startFullPass(folder)
                dao.updateFolder(folder.copy(lastScanStatus = CloudScanStatus.RUNNING.name))
                pass.folder = folder
                cursor = null
                continue
            }
            // Unpicked or disconnected meanwhile: stop before writing rows that point at it.
            if (dao.getFolder(folder.id) == null) return null
            page.entries.forEach { if (it is ScanEntry.Folder) pass.knownFolders[it.id] = it }
            for (entry in page.entries) {
                currentCoroutineContext().ensureActive()
                when (entry) {
                    is ScanEntry.File -> pass.file(entry)
                    is ScanEntry.Removed -> pass.pendingRemovals += entry
                    is ScanEntry.Folder -> Unit
                }
                onProgress(pass.processed)
            }
            pass.applyCovers()
            folder = folder.copy(scanCursor = page.cursor, cursorIsDelta = !page.hasMore)
            dao.updateFolder(folder)
            if (!page.hasMore) break
            cursor = page.cursor
        }

        pass.applyRemovals()
        if (folder.fullScan) {
            pass.removed += dao.removeStale(folder.id, folder.scanGeneration).let { it.deletedTracks + it.keptAsLocal }
            folder = folder.copy(fullScan = false)
        }
        dao.updateFolder(folder.copy(lastScanAt = clock(), lastScanStatus = CloudScanStatus.OK.name, lastScanError = null))
        return CloudScanResult(pass.added, pass.updated, pass.removed, pass.unchanged)
    }

    private suspend fun startFullPass(folder: CloudFolderEntity): CloudFolderEntity {
        val next = folder.copy(scanCursor = null, cursorIsDelta = false, fullScan = true, scanGeneration = folder.scanGeneration + 1)
        dao.updateFolder(next)
        return next
    }

    /** State of one scan run: folder paths learned so far, covers waiting for their songs, counts. */
    private inner class Pass(
        val provider: CloudStorageProvider,
        val kind: CloudProvider,
        var folder: CloudFolderEntity,
        val root: ScanRoot,
    ) {
        val knownFolders = HashMap<String, ScanEntry.Folder>()
        private val resolvedFolders = HashMap<String, List<String>>()

        /** Best sibling cover per folder key, and songs in that folder still without art. */
        private val covers = HashMap<String, Pair<Int, ScanEntry.File>>()
        private val needArt = HashMap<String, MutableList<Long>>()
        /** A folder's fetched cover (null = tried and failed), reused for its songs on later pages. */
        private val coverPaths = HashMap<String, String?>()

        /** Audio files this pass listed as present: no deletion may take them. */
        private val liveIds = HashSet<String>()
        val pendingRemovals = mutableListOf<ScanEntry.Removed>()

        var added = 0
        var updated = 0
        var removed = 0
        var unchanged = 0
        val processed get() = added + updated + unchanged

        suspend fun file(f: ScanEntry.File) {
            val folders = f.folders ?: foldersOf(f.parentId)
            val key = folderKey(f, folders)
            CloudFiles.coverRank(f.name)?.let { rank ->
                val best = covers[key]
                if (best == null || rank < best.first) covers[key] = rank to f
                return
            }
            if (!CloudFiles.isAudio(f.name, f.mimeType)) return
            liveIds += f.id

            val existing = dao.getItem(folder.accountId, f.id)
            if (existing != null && f.version != null && existing.cTag == f.version && dao.getTrack(existing.trackId) != null) {
                // Same bytes, maybe a new place (rename / move): the location follows the file.
                dao.refreshLocation(
                    trackId = existing.trackId, folderId = folder.id, name = f.name,
                    relativePath = folders.joinToString("/"), parentItemId = f.parentId,
                    remotePath = f.remotePath, generation = folder.scanGeneration, now = clock(),
                )
                unchanged++
                return
            }

            val read = readTags(f)
            val tags = read ?: AudioTags.EMPTY
            val names = CloudMetadataFallbacks.resolve(tags, f.name, folders)
            val artPath = tags.picture?.let { art.save(it.bytes, it.mimeType) }
            val ref = CloudRef(kind, folder.accountId, f.id, f.version)
            val format = CloudFiles.formatFor(f.name, tags.codec)
            val kbps = tags.bitrateKbps ?: tags.durationMs?.takeIf { it > 0 }?.let { (f.size * 8 / it).toInt() } ?: 0
            val item = CloudItemEntity(
                trackId = existing?.trackId ?: 0, accountId = folder.accountId, folderId = folder.id, itemId = f.id,
                name = f.name, relativePath = folders.joinToString("/"), parentItemId = f.parentId,
                // No version when the tags couldn't be read: the next full pass tries this file again.
                cTag = if (read == null) null else f.version,
                eTag = f.eTag, size = f.size, mimeType = f.mimeType, seenGeneration = folder.scanGeneration,
                scannedAt = clock(), remotePath = f.remotePath,
            )
            val trackId = if (existing != null && dao.getTrack(existing.trackId) != null) {
                dao.updateScannedTrack(
                    trackId = existing.trackId, title = names.title, artist = names.artist, album = names.album,
                    albumArtist = names.albumArtist, durationMs = tags.durationMs ?: 0, fileFormat = format,
                    qualityKbps = kbps, bitsPerSample = tags.bitsPerSample, sampleRateHz = tags.sampleRateHz,
                    albumArtPath = artPath, cloudUri = ref.toUri(), sizeBytes = f.size,
                )
                dao.updateItem(item)
                updated++
                existing.trackId
            } else {
                // Canonical words stay blank on purpose: every lookup by words skips a blank key,
                // so a cloud song is never matched to, merged with or swapped for an online one.
                val id = dao.insertScanned(
                    TrackEntity(
                        title = names.title, artist = names.artist, album = names.album, albumArtist = names.albumArtist,
                        durationMs = tags.durationMs ?: 0, filePath = null, fileFormat = format, qualityKbps = kbps,
                        fileSizeBytes = f.size, source = MusicSource.CLOUD, albumArtPath = artPath,
                        isDownloaded = false, bitsPerSample = tags.bitsPerSample, sampleRateHz = tags.sampleRateHz,
                        cloudUri = ref.toUri(),
                    ),
                    item,
                )
                added++
                id
            }
            if (artPath == null) needArt.getOrPut(key) { mutableListOf() } += trackId
        }

        /**
         * Applies this pass's deletions, sparing every file the pass listed as live: a folder
         * rename or move reports the old location gone while the same files (same ids) appear at
         * the new one, in either order and on any page.
         */
        suspend fun applyRemovals() {
            if (pendingRemovals.isEmpty()) return
            val doomed = LinkedHashMap<Long, String>()
            for (r in pendingRemovals) {
                r.id?.let { id ->
                    dao.getItem(folder.accountId, id)?.let { doomed[it.trackId] = it.itemId }
                    // A deleted OneDrive folder reports only itself, not its files.
                    dao.itemsInParent(folder.accountId, id).forEach { doomed[it.trackId] = it.itemId }
                }
                r.remotePath?.let { path ->
                    dao.itemsUnderPath(folder.id, path.lowercase()).forEach { doomed[it.trackId] = it.itemId }
                }
            }
            val ids = doomed.filterValues { it !in liveIds }.keys.toList()
            if (ids.isNotEmpty()) dao.removeItems(ids).let { removed += it.deletedTracks + it.keptAsLocal }
            pendingRemovals.clear()
        }

        /** Gives waiting songs the best sibling cover their folder has shown so far. */
        suspend fun applyCovers() {
            for ((key, ids) in needArt.entries.toList()) {
                val cover = covers[key]?.second ?: continue
                // Fetched once per folder; songs of that folder on later pages reuse it.
                val path = if (coverPaths.containsKey(key)) coverPaths[key] else downloadCover(cover).also { coverPaths[key] = it }
                needArt.remove(key)
                if (path != null) dao.setArtIfMissing(ids, path)
            }
        }

        private suspend fun downloadCover(cover: ScanEntry.File): String? {
            if (cover.size <= 0 || cover.size > MAX_COVER_BYTES) return null
            return try {
                val bytes = withLink(cover) { link -> ranges.fetch(link, 0, cover.size.toInt()) }
                art.save(bytes, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "cover not fetched: ${e.javaClass.simpleName}")
                null
            }
        }

        /**
         * The file's tags; [AudioTags.EMPTY] when it has none; null when they couldn't be read for
         * a reason specific to this file (the song is then filed by its path and retried on the
         * next full pass). Only connectivity and sign-in failures escape: those stop the page.
         */
        private suspend fun readTags(f: ScanEntry.File): AudioTags? {
            if (f.size <= 0) return AudioTags.EMPTY
            val reader = CachingRangeReader(f.size) { offset, length -> withLink(f) { link -> ranges.fetch(link, offset, length) } }
            return try {
                TagReader.read(reader)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TagReadBudgetExceeded) {
                AudioTags.EMPTY
            } catch (e: Exception) {
                if (isConnectivityFailure(e)) throw e
                Log.w(TAG, "tags not read for one file: ${e.javaClass.simpleName}")
                null
            }
        }

        private var lastLink: Pair<String, CloudLink>? = null

        /** Runs [block] with a link to [f], resolving once more if the content host refuses it. */
        private suspend fun <T> withLink(f: ScanEntry.File, block: suspend (CloudLink) -> T): T {
            val ref = CloudRef(kind, folder.accountId, f.id, f.version)
            val cached = lastLink?.takeIf { it.first == f.id }?.second
            val link = cached ?: f.downloadUrl?.let { CloudLink(it, f.size, f.version, clock()) }
                ?: links.resolve(ref, RequestPriority.BACKGROUND)
            lastLink = f.id to link
            return try {
                block(link)
            } catch (e: LinkExpiredException) {
                val fresh = links.resolve(ref, RequestPriority.BACKGROUND, fresh = true)
                lastLink = f.id to fresh
                block(fresh)
            }
        }

        /** Folder names from below the root to [parentId], from this pass's listing or the provider. */
        private suspend fun foldersOf(parentId: String?): List<String> {
            if (parentId == null || parentId == root.itemId) return emptyList()
            resolvedFolders[parentId]?.let { return it }
            val chain = mutableListOf<String>()
            var id: String? = parentId
            var guard = 0
            while (id != null && id != root.itemId && guard++ < MAX_DEPTH) {
                val known = knownFolders[id]
                if (known == null) {
                    // Not listed in this pass (an incremental page): ask where it is, once.
                    val described = provider.describeFolder(folder.accountId, id)
                    val rel = described?.path?.let { relative(root.path, it) } ?: emptyList()
                    resolvedFolders[parentId] = rel + chain.asReversed()
                    return resolvedFolders[parentId]!!
                }
                chain += known.name
                id = known.parentId
            }
            return chain.asReversed().toList().also { resolvedFolders[parentId] = it }
        }

        private fun folderKey(f: ScanEntry.File, folders: List<String>) = f.parentId ?: folders.joinToString("/")
    }

    companion object {
        private const val TAG = "CloudScanner"

        /**
         * Failures that are about the connection or the sign-in, not about one file: the page
         * stops and the worker retries later from the saved cursor.
         */
        internal fun isConnectivityFailure(e: Throwable): Boolean =
            e is com.stash.data.cloud.provider.CloudAuthRequiredException ||
                e is com.stash.data.cloud.provider.CloudUnavailableException ||
                e is java.net.UnknownHostException ||
                e is java.net.ConnectException ||
                e is java.net.NoRouteToHostException ||
                e is java.io.InterruptedIOException || // includes SocketTimeoutException
                e is javax.net.ssl.SSLException ||
                e is java.net.SocketException
        private const val MAX_DEPTH = 64
        private const val MAX_COVER_BYTES = 8L * 1024 * 1024

        /** "/Music" + "/Music/Artist/Album" → [Artist, Album] (case-insensitive). */
        internal fun relative(rootPath: String, folderPath: String): List<String> {
            val r = rootPath.trimEnd('/')
            val p = folderPath.trimEnd('/')
            if (p.equals(r, ignoreCase = true)) return emptyList()
            if (!p.startsWith("$r/", ignoreCase = true)) return emptyList()
            return p.substring(r.length + 1).split('/').filter { it.isNotEmpty() }
        }
    }
}
