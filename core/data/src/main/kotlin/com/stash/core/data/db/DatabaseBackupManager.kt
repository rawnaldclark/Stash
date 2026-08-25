package com.stash.core.data.db

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.net.toUri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The 16-byte header every SQLite database file begins with: the ASCII string
 * "SQLite format 3" followed by a NUL terminator. Built rather than written as a
 * literal so the source file stays free of raw NUL bytes.
 */
private val SQLITE_MAGIC: ByteArray = "SQLite format 3".toByteArray(Charsets.US_ASCII) + 0

/**
 * What the user chose to restore from a backup ZIP (issue #235). The classic
 * full overwrite is [EVERYTHING_REPLACE]; the three new scopes exist so users
 * can take just their library or just their settings from a backup, and so a
 * library restore can MERGE instead of replace.
 *
 * @property consumesLibraryDatabase  True when the import stages and reads
 *   the backup's `stash.db` (either to swap it in or to copy rows out of it).
 * @property swapsDatabaseFile        True when the live database FILE is
 *   replaced — implies [consumesLibraryDatabase]. Merge never swaps; it
 *   writes through the still-open live Room instance instead.
 * @property restoresSettingsFiles    True when DataStore files are restored.
 */
enum class BackupImportScope(
    val consumesLibraryDatabase: Boolean,
    val swapsDatabaseFile: Boolean,
    val restoresSettingsFiles: Boolean,
) {
    /**
     * Adds the backup's missing songs/playlists/memberships INTO the current
     * library. Nothing is deleted, nothing is overwritten, settings files are
     * untouched, and — because the live Room instance stays open — NO restart
     * is required.
     */
    LIBRARY_MERGE(
        consumesLibraryDatabase = true,
        swapsDatabaseFile = false,
        restoresSettingsFiles = false,
    ),

    /** Swaps in the backup's database but keeps the current settings. */
    LIBRARY_REPLACE(
        consumesLibraryDatabase = true,
        swapsDatabaseFile = true,
        restoresSettingsFiles = false,
    ),

    /** Restores settings/preferences but keeps the current library. */
    SETTINGS_REPLACE(
        consumesLibraryDatabase = false,
        swapsDatabaseFile = false,
        restoresSettingsFiles = true,
    ),

    /** The historical behavior: replace both the database and the settings. */
    EVERYTHING_REPLACE(
        consumesLibraryDatabase = true,
        swapsDatabaseFile = true,
        restoresSettingsFiles = true,
    ),
}

/**
 * Outcome of a successful [DatabaseBackupManager.importDatabase].
 *
 * @property restoredTreeUri  External storage tree URI found in restored
 *   preferences, when the scope restored settings files and one was present.
 *   Null otherwise (including for library-only scopes — settings were never
 *   touched, so there is nothing to re-grant).
 * @property requiresRestart  True when files behind live singletons (Room /
 *   DataStore) were swapped and the process must be killed to reload them.
 *   False for [BackupImportScope.LIBRARY_MERGE], which writes through the
 *   still-open live database.
 * @property addedTracks      New track rows inserted (merge scope only).
 * @property addedPlaylists   New playlist rows inserted (merge scope only).
 * @property mergedMemberships Playlist memberships appended/reactivated
 *   (merge scope only).
 */
data class BackupImportResult(
    val restoredTreeUri: Uri?,
    val requiresRestart: Boolean,
    val addedTracks: Int = 0,
    val addedPlaylists: Int = 0,
    val mergedMemberships: Int = 0,
)

/** Internal tally of what one merge pass inserted. */
private data class MergeCounts(
    val addedTracks: Int,
    val addedPlaylists: Int,
    val mergedMemberships: Int,
)

/**
 * Handles exporting and importing the internal Room database, DataStore settings,
 * and encrypted tokens. Bundles everything into a single ZIP archive.
 *
 * Exporting uses a checkpoint-then-zip approach to ensure WAL-mode
 * consistency. Importing replaces the database and/or preferences files on disk
 * (per [BackupImportScope]) and normally requires an app restart to take
 * effect — except the library-MERGE scope, which copies rows into the live
 * database transactionally and needs no restart.
 */
@Singleton
class DatabaseBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: StashDatabase,
) {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class BackupManifest(
        val dbSchemaVersion: Int,
        val exportTimestamp: Long,
        val appVersionName: String? = null
    )

    /**
     * Exports the database and settings to the provided [targetUri] as a ZIP.
     */
    suspend fun exportDatabase(targetUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // 1. Force a checkpoint to ensure the .db file is up to date
            database.openHelper.writableDatabase.query(
                SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)")
            ).use { it.moveToFirst() }

            val dbFile = context.getDatabasePath(StashDatabase.DATABASE_NAME)
            val datastoreDir = File(context.filesDir, "datastore")
            val appVersionName = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull()

            context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                ZipOutputStream(outputStream).use { zipOut ->
                    // 2. Add Manifest first so import can validate it early
                    val manifest = BackupManifest(
                        dbSchemaVersion = database.openHelper.readableDatabase.version,
                        exportTimestamp = System.currentTimeMillis(),
                        appVersionName = appVersionName
                    )
                    zipOut.putNextEntry(ZipEntry("manifest.json"))
                    zipOut.write(json.encodeToString(manifest).toByteArray())
                    zipOut.closeEntry()

                    // 3. Add DB
                    if (dbFile.exists()) {
                        addToZip(zipOut, dbFile, "stash.db")
                    }

                    // 4. Add all DataStore files (settings, tokens, etc.)
                    if (datastoreDir.exists()) {
                        datastoreDir.listFiles()?.forEach { file ->
                            if (file.isFile) {
                                addToZip(zipOut, file, "datastore/${file.name}")
                            }
                        }
                    }
                }
            } ?: throw IllegalStateException("Could not open output stream for URI: $targetUri")
        }
    }

    private fun addToZip(zipOut: ZipOutputStream, file: File, zipPath: String) {
        FileInputStream(file).use { input ->
            zipOut.putNextEntry(ZipEntry(zipPath))
            input.copyTo(zipOut)
            zipOut.closeEntry()
        }
    }

    /**
     * Opens [file] as a SQLite database and runs `PRAGMA integrity_check`,
     * throwing if it is not a valid, intact database. This is the gate that
     * keeps a truncated/corrupt backup from ever replacing the live library.
     * Internal for testing.
     *
     * Opened **READ-WRITE on purpose** — do not "tighten" this back to
     * `OPEN_READONLY`. A real backup contains `tracks_fts` (FTS4), and on an FTS
     * table `integrity_check` dispatches the FTS module's own check as
     * `INSERT INTO tracks_fts(tracks_fts) VALUES('integrity-check')`. That is
     * write-shaped even though it persists nothing, so a read-only handle throws
     * "attempt to write a readonly database" and EVERY import fails the gate,
     * valid backups included (#370, shipped broken in v0.9.83).
     *
     * This is safe: [file] is always the throwaway `.import-tmp` staged copy,
     * never the live database — it is moved into place or deleted immediately
     * after. A genuinely corrupt backup still fails the check and is rejected.
     *
     * Note no unit test can guard this: Robolectric does not enforce
     * `OPEN_READONLY` (verified 2026-07-26 — a READONLY handle accepts INSERT
     * there), so the read-only failure is only reproducible on a device.
     */
    internal fun verifyDatabaseIntegrity(file: File) {
        // Structural gate FIRST, before SQLite sees the file. Every SQLite
        // database begins with the 16-byte magic "SQLite format 3"; a
        // truncated download or a wrong file picked in the chooser does not.
        //
        // This is not belt-and-braces. Opening READ-WRITE (required above) means
        // we can no longer lean on the opener to reject a malformed file the way
        // a read-only open did — the platform is free to treat a writable handle
        // to a junk path as "create/repair", and Robolectric demonstrably does:
        // after the READWRITE change the existing corrupt-backup test stopped
        // throwing entirely. Checking the header ourselves makes rejection
        // deterministic and independent of any SQLite/host quirk, which is what
        // a gate protecting someone's only library should be.
        val header = ByteArray(SQLITE_MAGIC.size)
        val read = file.inputStream().use { it.read(header) }
        if (read != header.size || !header.contentEquals(SQLITE_MAGIC)) {
            throw IllegalStateException("The backup database is corrupt and was not restored.")
        }

        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
        )
        db.use {
            val ok = it.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
            }
            if (!ok) {
                throw IllegalStateException("The backup database is corrupt and was not restored.")
            }
        }
    }

    /** Move [src] onto [dest], falling back to copy+delete across filesystems. */
    private fun moveInto(src: File, dest: File) {
        if (!src.renameTo(dest)) {
            src.copyTo(dest, overwrite = true)
            src.delete()
        }
    }

    private fun cleanupStaging(stagedDb: File, stagedDatastore: List<Pair<File, File>>) {
        stagedDb.delete()
        stagedDatastore.forEach { (tmp, _) -> tmp.delete() }
    }

    /**
     * Imports a ZIP backup from [sourceUri], honoring the chosen [scope]:
     *
     *  - [BackupImportScope.EVERYTHING_REPLACE] — the historical behavior;
     *    replaces the current DB AND settings files. Restart required.
     *  - [BackupImportScope.LIBRARY_REPLACE] — swaps only the DB file, keeping
     *    the current settings. Restart required.
     *  - [BackupImportScope.SETTINGS_REPLACE] — swaps only the DataStore
     *    files, keeping the current library. Restart required.
     *  - [BackupImportScope.LIBRARY_MERGE] — adds the backup's missing tracks,
     *    playlists, and playlist memberships into the CURRENT library without
     *    deleting or overwriting anything (issue #235). Settings untouched.
     *    No restart: the merge writes through the still-open live Room
     *    instance inside one atomic transaction.
     *
     * Returns a [BackupImportResult]; for scopes that restore settings files
     * this includes the restored external-storage tree URI if one was found in
     * the preferences (so callers can re-grant SAF permission before restart).
     */
    suspend fun importDatabase(
        sourceUri: Uri,
        scope: BackupImportScope = BackupImportScope.EVERYTHING_REPLACE,
    ): Result<BackupImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            android.util.Log.i("BackupManager", "Starting $scope import from $sourceUri")

            val currentDbVersion = database.openHelper.readableDatabase.version
            val dbFile = context.getDatabasePath(StashDatabase.DATABASE_NAME)
            val datastoreDir = File(context.filesDir, "datastore")

            // 1. Validate manifest before touching any files
            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipIn ->
                    var manifestFound = false
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        if (entry.name == "manifest.json") {
                            val manifest = json.decodeFromString<BackupManifest>(zipIn.readBytes().decodeToString())
                            if (manifest.dbSchemaVersion > currentDbVersion) {
                                throw IllegalStateException(
                                    "Backup is from a newer version of Stash (Schema ${manifest.dbSchemaVersion}). " +
                                        "Please update the app before importing."
                                )
                            }
                            manifestFound = true
                            break
                        }
                        entry = zipIn.nextEntry
                    }
                    if (!manifestFound) {
                        throw IllegalStateException("The selected file is not a valid Stash backup.")
                    }
                }
            } ?: throw IllegalStateException("Could not open input stream for validation")

            // 2. Extract ONLY the entries this scope consumes to STAGING temp
            //    files first — never write over live files straight from the
            //    zip stream. The manifest sits early in the archive, so a
            //    truncated/corrupt backup validates fine (step 1) and only
            //    fails partway through the copy; writing directly would
            //    half-overwrite stash.db and then the old WAL is deleted
            //    below, destroying the user's only library with no rollback.
            val walFile = File(dbFile.path + "-wal")
            val shmFile = File(dbFile.path + "-shm")
            val stagedDb = File(dbFile.parentFile, StashDatabase.DATABASE_NAME + ".import-tmp")
            stagedDb.delete()
            val stagedDatastore = mutableListOf<Pair<File, File>>() // tmp -> final

            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        if (entry.isDirectory) {
                            entry = zipIn.nextEntry
                            continue
                        }

                        val target: File? = when {
                            entry.name == "stash.db" && scope.consumesLibraryDatabase -> stagedDb
                            entry.name.startsWith("datastore/") && scope.restoresSettingsFiles -> {
                                val relativeName = entry.name.substringAfter("datastore/")
                                if (relativeName.isBlank() || File(relativeName).isAbsolute) {
                                    throw SecurityException("Invalid datastore entry path: ${entry.name}")
                                }

                                val datastoreRoot = datastoreDir.toPath().normalize()
                                val resolved = datastoreRoot.resolve(relativeName).normalize()
                                if (!resolved.startsWith(datastoreRoot)) {
                                    throw SecurityException("Entry escapes target directory: ${entry.name}")
                                }

                                val finalFile = resolved.toFile()
                                val tmp = File(finalFile.path + ".import-tmp")
                                stagedDatastore.add(tmp to finalFile)
                                tmp
                            }
                            else -> null
                        }

                        if (target != null) {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { output ->
                                zipIn.copyTo(output)
                            }
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }
            } ?: throw IllegalStateException("Could not open input stream for URI: $sourceUri")

            // 3. Verify the staged database opens and passes integrity_check
            //    BEFORE we touch anything live. Every scope that reads the
            //    backup DB — swap AND merge — goes through this gate; if it
            //    doesn't pass, bail and the live library is untouched.
            //    Scopes that don't consume the DB simply skip it (a
            //    settings-only import from a standard full backup is
            //    legitimate).
            if (scope.consumesLibraryDatabase) {
                try {
                    if (!stagedDb.exists()) {
                        throw IllegalStateException("The selected backup contains no database.")
                    }
                    verifyDatabaseIntegrity(stagedDb)
                } catch (e: Exception) {
                    cleanupStaging(stagedDb, stagedDatastore)
                    throw e
                }
            }

            // 4. Commit.
            if (scope == BackupImportScope.LIBRARY_MERGE) {
                // Merge path: copy rows into the LIVE database. No file is
                // swapped, the singleton Room instance stays open, flows emit
                // the new data immediately, and NO restart is needed.
                try {
                    val counts = mergeLibraryFrom(stagedDb)
                    android.util.Log.i(
                        "BackupManager",
                        "Merge completed: +${counts.addedTracks} tracks, " +
                            "+${counts.addedPlaylists} playlists, " +
                            "${counts.mergedMemberships} memberships",
                    )
                    cleanupStaging(stagedDb, stagedDatastore)
                    return@runCatching BackupImportResult(
                        restoredTreeUri = null,
                        requiresRestart = false,
                        addedTracks = counts.addedTracks,
                        addedPlaylists = counts.addedPlaylists,
                        mergedMemberships = counts.mergedMemberships,
                    )
                } catch (e: Exception) {
                    // The merge itself is one transaction — on any failure it
                    // rolled back internally, leaving the live library intact.
                    cleanupStaging(stagedDb, stagedDatastore)
                    throw e
                }
            }

            // File-swap path. Close the live DB (only being swapped when the
            // scope includes it), keep a rollback copy, then swap the verified
            // staged files into place. A failure mid-swap restores the
            // pre-import database, so a restore can never lose data.
            if (scope.swapsDatabaseFile) database.close()
            val rollback = File(dbFile.path + ".rollback")
            if (scope.swapsDatabaseFile) {
                if (dbFile.exists()) dbFile.copyTo(rollback, overwrite = true) else rollback.delete()
            }
            try {
                if (scope.swapsDatabaseFile) {
                    moveInto(stagedDb, dbFile)
                    // Drop the old WAL/SHM so they don't conflict with the new DB.
                    if (walFile.exists()) walFile.delete()
                    if (shmFile.exists()) shmFile.delete()
                }
                if (scope.restoresSettingsFiles) {
                    stagedDatastore.forEach { (tmp, finalFile) ->
                        finalFile.parentFile?.mkdirs()
                        moveInto(tmp, finalFile)
                    }
                }
                rollback.delete()
            } catch (e: Exception) {
                android.util.Log.e("BackupManager", "Restore swap failed — rolling back", e)
                if (scope.swapsDatabaseFile && rollback.exists()) rollback.copyTo(dbFile, overwrite = true)
                cleanupStaging(stagedDb, stagedDatastore)
                throw e
            }

            // 5. Read the restored Tree URI from DataStore — only meaningful
            //    when this scope actually restored settings files. We use a
            //    fresh DataStore instance to bypass the singleton's cache and
            //    read the actual protobuf-serialized state we just restored.
            val restoredTreeUri = if (scope.restoresSettingsFiles) {
                peekRestoredTreeUri(datastoreDir)
            } else null

            android.util.Log.i("BackupManager", "Import completed successfully. Restored URI: $restoredTreeUri")
            BackupImportResult(
                restoredTreeUri = restoredTreeUri,
                requiresRestart = true,
            )
        }
    }

    /**
     * Peeks `external_tree_uri` out of a freshly-restored
     * `storage_preferences.preferences_pb`, bypassing the cached DataStore
     * singleton by reading a throwaway copy in cacheDir.
     */
    private suspend fun peekRestoredTreeUri(datastoreDir: File): Uri? = try {
        val restoredFile = File(datastoreDir, "storage_preferences.preferences_pb")
        if (restoredFile.exists()) {
            // Create a temporary copy to avoid "multiple datastores" error on the main file
            val tmpFile = File(context.cacheDir, "restored_prefs_peek.preferences_pb")
            restoredFile.copyTo(tmpFile, overwrite = true)

            val uri = PreferenceDataStoreFactory.create { tmpFile }
                .data.firstOrNull()?.get(stringPreferencesKey("external_tree_uri"))?.toUri()

            tmpFile.delete()
            uri
        } else null
    } catch (e: Exception) {
        android.util.Log.e("BackupManager", "Failed to peek restored URI", e)
        null
    }

    /**
     * The library-merge engine (issue #235): opens the staged backup with a
     * THROWAWAY Room instance sharing the production migration chain
     * ([StashDatabase.ALL_MIGRATIONS]), rolling an older-schema backup forward
     * to the live schema, then copies its rows into the live database inside
     * ONE transaction:
     *
     *  1. **Tracks** are matched by identity — Spotify URI first, then YouTube
     *     id, then canonical (title, artist), mirroring
     *     TrackDao.findByAnyIdentity's priority — and only MISSING rows are
     *     inserted. An existing song is never modified or duplicated.
     *  2. **Playlists** are matched by `source_id` (unique index). Existing
     *     playlists keep ALL their current metadata; only genuinely new
     *     playlists are inserted. Imported-new playlists arrive with sync OFF
     *     so a restore can never silently opt the user into mass downloads.
     *  3. **Memberships** are UNIONED: each backup membership whose track and
     *     playlist resolve to the live library is appended (in backup order)
     *     unless that track is already an active member. Soft-deleted
     *     memberships are reactivated — the backup's content is being added,
     *     which is exactly the requested semantics. All imported memberships
     *     are marked `locally_added` so REFRESH-mode syncs won't wipe them,
     *     and cached `track_count`s of touched playlists are recomputed.
     *
     * Any exception anywhere rolls the entire transaction back, leaving the
     * live library byte-for-byte untouched.
     */
    private suspend fun mergeLibraryFrom(stagedDb: File): MergeCounts {
        if (!stagedDb.exists()) {
            throw IllegalStateException("The selected backup contains no database.")
        }
        val backupDb = Room.databaseBuilder(
            context, StashDatabase::class.java, stagedDb.absolutePath,
        )
            .addMigrations(*StashDatabase.ALL_MIGRATIONS)
            // TRUNCATE, not the WAL default: this is a throwaway single-
            // connection copy read once and closed — WAL's sidecar files
            // (-wal/-shm) buy nothing next to a `.import-tmp` staging name,
            // and Robolectric's legacy SQLite chokes on WAL for file-backed
            // databases, which keeps the import path unit-testable.
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()
        try {
            // Touching the helper forces Room to run the full migration chain
            // on the staged file and validate the result — a backup that
            // cannot be rolled forward fails HERE, before anything is copied.
            backupDb.openHelper.writableDatabase

            val backupTracks = backupDb.trackDao().getAllForIntegrityScan()
            val backupPlaylists = backupDb.playlistDao().getAllForBackupMerge()
            val backupRefs = backupDb.playlistDao().getAllCrossRefsForBackupMerge()

            var addedTracks = 0
            var addedPlaylists = 0
            var mergedMemberships = 0

            database.withTransaction {
                val trackDao = database.trackDao()
                val playlistDao = database.playlistDao()

                // ── 1. Tracks ────────────────────────────────────────────
                // Identity indexes over the live library, built once so N
                // backup tracks cost zero extra queries. First row wins on
                // canonical collisions (uri/yt are unique-indexed anyway).
                val liveBySpotifyUri = HashMap<String, Long>()
                val liveByYoutubeId = HashMap<String, Long>()
                val liveByCanonical = HashMap<String, Long>()
                for (t in trackDao.getAllForIntegrityScan()) {
                    t.spotifyUri?.let { liveBySpotifyUri.putIfAbsent(it, t.id) }
                    t.youtubeId?.let { liveByYoutubeId.putIfAbsent(it, t.id) }
                    if (hasCanonicalIdentity(t.canonicalTitle, t.canonicalArtist)) {
                        liveByCanonical.putIfAbsent(
                            canonicalKey(t.canonicalTitle, t.canonicalArtist), t.id,
                        )
                    }
                }

                /** Backup track id → live track id (inserted or matched). */
                val trackIdMap = HashMap<Long, Long>(backupTracks.size)
                for (track in backupTracks.sortedBy { it.id }) {
                    val existing = track.spotifyUri?.let { liveBySpotifyUri[it] }
                        ?: track.youtubeId?.let { liveByYoutubeId[it] }
                        ?: if (hasCanonicalIdentity(track.canonicalTitle, track.canonicalArtist)) {
                            liveByCanonical[canonicalKey(track.canonicalTitle, track.canonicalArtist)]
                        } else null
                    if (existing != null) {
                        trackIdMap[track.id] = existing
                        continue
                    }

                    // id = 0 → fresh autoincrement PK; every other column —
                    // including like timestamps and play stats — travels as-is.
                    val newId = trackDao.insert(track.copy(id = 0))
                    track.spotifyUri?.let { liveBySpotifyUri.putIfAbsent(it, newId) }
                    track.youtubeId?.let { liveByYoutubeId.putIfAbsent(it, newId) }
                    if (hasCanonicalIdentity(track.canonicalTitle, track.canonicalArtist)) {
                        liveByCanonical.putIfAbsent(
                            canonicalKey(track.canonicalTitle, track.canonicalArtist), newId,
                        )
                    }
                    trackIdMap[track.id] = newId
                    addedTracks++
                }

                // ── 2. Playlists ─────────────────────────────────────────
                val liveBySourceId = HashMap<String, Long>()
                for (p in playlistDao.getAllForBackupMerge()) {
                    liveBySourceId.putIfAbsent(p.sourceId, p.id)
                }

                /** Backup playlist id → live playlist id. */
                val playlistIdMap = HashMap<Long, Long>(backupPlaylists.size)
                for (playlist in backupPlaylists.sortedBy { it.id }) {
                    val existingId = liveBySourceId[playlist.sourceId]
                    if (existingId != null) {
                        // Exists here already — keep the live metadata
                        // (name, art, pinning, activation…) exactly as-is.
                        playlistIdMap[playlist.id] = existingId
                        continue
                    }

                    val newId = playlistDao.insert(
                        playlist.copy(
                            id = 0,
                            // Never inherit download consent from a backup —
                            // see PlaylistEntity.syncEnabled's KDoc.
                            syncEnabled = false,
                            dateAdded = if (playlist.dateAdded.toEpochMilli() > 0) {
                                playlist.dateAdded
                            } else Instant.now(),
                        )
                    )
                    liveBySourceId.putIfAbsent(playlist.sourceId, newId)
                    playlistIdMap[playlist.id] = newId
                    addedPlaylists++
                }

                // ── 3. Memberships (union) ───────────────────────────────
                val targetIds = playlistIdMap.values.distinct()
                val nextPosition = HashMap<Long, Int>(targetIds.size * 2)
                val refByKey = HashMap<Pair<Long, Long>, PlaylistTrackCrossRef>()
                targetIds.chunkedForBind { ids ->
                    playlistDao.getCrossRefsForPlaylists(ids)
                }.forEach { ref ->
                    refByKey[ref.playlistId to ref.trackId] = ref
                    if (ref.removedAt == null) {
                        nextPosition.merge(ref.playlistId, ref.position + 1, ::maxOf)
                    }
                }

                val touchedPlaylists = sortedSetOf<Long>()
                for ((backupPlaylistId, refs) in backupRefs.groupBy { it.playlistId }) {
                    val livePlaylistId = playlistIdMap[backupPlaylistId] ?: continue
                    for (ref in refs.sortedBy { it.position }) {
                        val liveTrackId = trackIdMap[ref.trackId] ?: continue
                        val key = livePlaylistId to liveTrackId
                        val existing = refByKey[key]
                        if (existing != null && existing.removedAt == null) {
                            continue // already an active member — leave it alone
                        }

                        // Append (or reactivate) at the end of the live
                        // ordering, preserving the backup's addedAt stamp.
                        val position = nextPosition.getOrDefault(livePlaylistId, 0)
                            .also { nextPosition[livePlaylistId] = it + 1 }
                        playlistDao.insertCrossRef(
                            existing?.copy(
                                removedAt = null,
                                position = position,
                                addedAt = ref.addedAt,
                                locallyAdded = true,
                            ) ?: PlaylistTrackCrossRef(
                                playlistId = livePlaylistId,
                                trackId = liveTrackId,
                                position = position,
                                addedAt = ref.addedAt,
                                locallyAdded = true,
                            )
                        )
                        mergedMemberships++
                        touchedPlaylists.add(livePlaylistId)
                    }
                }

                // Recompute the cached count of every playlist we changed so
                // Library/Home badges don't drift from reality.
                for (playlistId in touchedPlaylists) {
                    playlistDao.updateTrackCount(
                        playlistId,
                        playlistDao.getOrderedTrackIdsForPlaylist(playlistId).size,
                    )
                }
            }

            return MergeCounts(addedTracks, addedPlaylists, mergedMemberships)
        } finally {
            backupDb.close()
        }
    }

    private fun hasCanonicalIdentity(title: String, artist: String): Boolean =
        title.isNotBlank() && artist.isNotBlank()

    /** Stable composite key for canonical-identity matching. */
    private fun canonicalKey(title: String, artist: String): String = "$title\u0001$artist"
}
