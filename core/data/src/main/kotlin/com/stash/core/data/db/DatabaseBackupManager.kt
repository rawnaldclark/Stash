package com.stash.core.data.db

import android.content.Context
import android.net.Uri
import androidx.sqlite.db.SimpleSQLiteQuery
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.nio.file.Path
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
 * Handles exporting and importing the internal Room database, DataStore settings,
 * and encrypted tokens. Bundles everything into a single ZIP archive.
 *
 * Exporting uses a checkpoint-then-zip approach to ensure WAL-mode
 * consistency. Importing replaces the database and preferences files on disk
 * and requires an app restart to take effect.
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
     * Opens [file] read-only as a SQLite database and runs `PRAGMA
     * integrity_check`, throwing if it is not a valid, intact database. This is
     * the gate that keeps a truncated/corrupt backup from ever replacing the
     * live library. Internal for testing.
     */
    internal fun verifyDatabaseIntegrity(file: File) {
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
     * Imports a ZIP backup from [sourceUri], replacing the current DB and settings.
     * Returns the restored external storage URI if found in the preferences.
     */
    suspend fun importDatabase(sourceUri: Uri): Result<Uri?> = withContext(Dispatchers.IO) {
        runCatching {
            android.util.Log.i("BackupManager", "Starting import from $sourceUri")

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

            val walFile = File(dbFile.path + "-wal")
            val shmFile = File(dbFile.path + "-shm")

            // 2. Extract everything to STAGING temp files first — never write
            // over the live database straight from the zip stream. The manifest
            // sits early in the archive, so a truncated/corrupt backup validates
            // fine (step 1) and only fails partway through the copy; writing
            // directly would half-overwrite stash.db and then the old WAL is
            // deleted below, destroying the user's only library with no rollback.
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
                            entry.name == "stash.db" -> stagedDb
                            entry.name.startsWith("datastore/") -> {
                                val relativeName = entry.name.substringAfter("datastore/")
                                if (relativeName.isBlank() || Path.of(relativeName).isAbsolute) {
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
            // BEFORE we touch the live one. If it doesn't, bail — the live
            // library is completely untouched.
            try {
                if (!stagedDb.exists()) {
                    throw IllegalStateException("The selected backup contains no database.")
                }
                verifyDatabaseIntegrity(stagedDb)
            } catch (e: Exception) {
                cleanupStaging(stagedDb, stagedDatastore)
                throw e
            }

            // 4. Commit. Close the live DB, keep a rollback copy, then swap the
            // verified staged files into place. A failure mid-swap restores the
            // pre-import database, so a restore can never lose data.
            database.close()
            val rollback = File(dbFile.path + ".rollback")
            if (dbFile.exists()) dbFile.copyTo(rollback, overwrite = true) else rollback.delete()
            try {
                moveInto(stagedDb, dbFile)
                // Drop the old WAL/SHM so they don't conflict with the new DB.
                if (walFile.exists()) walFile.delete()
                if (shmFile.exists()) shmFile.delete()
                stagedDatastore.forEach { (tmp, finalFile) ->
                    finalFile.parentFile?.mkdirs()
                    moveInto(tmp, finalFile)
                }
                rollback.delete()
            } catch (e: Exception) {
                android.util.Log.e("BackupManager", "Restore swap failed — rolling back", e)
                if (rollback.exists()) rollback.copyTo(dbFile, overwrite = true)
                cleanupStaging(stagedDb, stagedDatastore)
                throw e
            }

            // 4. Read the restored Tree URI from DataStore.
            // We use a fresh DataStore instance to bypass the singleton's cache
            // and read the actual protobuf-serialized state we just restored.
            val externalTreeUriKey = stringPreferencesKey("external_tree_uri")
            val restoredTreeUri = try {
                val restoredFile = File(datastoreDir, "storage_preferences.preferences_pb")
                if (restoredFile.exists()) {
                    // Create a temporary copy to avoid "multiple datastores" error on the main file
                    val tmpFile = File(context.cacheDir, "restored_prefs_peek.preferences_pb")
                    restoredFile.copyTo(tmpFile, overwrite = true)
                    
                    val uri = PreferenceDataStoreFactory.create { tmpFile }
                        .data.firstOrNull()?.get(externalTreeUriKey)?.toUri()
                    
                    tmpFile.delete()
                    uri
                } else null
            } catch (e: Exception) {
                android.util.Log.e("BackupManager", "Failed to peek restored URI", e)
                null
            }

            android.util.Log.i("BackupManager", "Import completed successfully. Restored URI: $restoredTreeUri")
            restoredTreeUri
        }
    }
}
