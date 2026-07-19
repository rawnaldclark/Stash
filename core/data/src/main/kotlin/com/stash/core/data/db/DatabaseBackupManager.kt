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

            // 2. Close the database to release file locks
            database.close()

            val walFile = File(dbFile.path + "-wal")
            val shmFile = File(dbFile.path + "-shm")

            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        if (entry.isDirectory) {
                            entry = zipIn.nextEntry
                            continue
                        }

                        val outFile = when {
                            entry.name == "stash.db" -> dbFile
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

                                resolved.toFile()
                            }
                            else -> null
                        }

                        if (outFile != null) {
                            if (entry.name == "stash.db" && outFile.canonicalFile.path != dbFile.canonicalFile.path) {
                                throw SecurityException("Invalid DB entry target: ${entry.name}")
                            }

                            outFile.parentFile?.mkdirs()

                            android.util.Log.d("BackupManager", "Restoring ${entry.name} to ${outFile.absolutePath}")

                            FileOutputStream(outFile).use { output ->
                                zipIn.copyTo(output)
                            }
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }
            } ?: throw IllegalStateException("Could not open input stream for URI: $sourceUri")

            // 3. Delete WAL/SHM files so they don't conflict with the new DB
            if (walFile.exists()) {
                android.util.Log.d("BackupManager", "Deleting old WAL file")
                walFile.delete()
            }
            if (shmFile.exists()) {
                android.util.Log.d("BackupManager", "Deleting old SHM file")
                shmFile.delete()
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
