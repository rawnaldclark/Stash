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
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.net.toUri

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

            context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                ZipOutputStream(outputStream).use { zipOut ->
                    // Add DB
                    if (dbFile.exists()) {
                        addToZip(zipOut, dbFile, "stash.db")
                    }

                    // Add all DataStore files (settings, tokens, etc.)
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
            // 1. Close the database to release file locks
            database.close()

            val dbFile = context.getDatabasePath(StashDatabase.DATABASE_NAME)
            val walFile = File(dbFile.path + "-wal")
            val shmFile = File(dbFile.path + "-shm")
            val datastoreDir = File(context.filesDir, "datastore")

            var restoredTreeUri: Uri? = null

            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        if (entry.isDirectory) {
                            entry = zipIn.nextEntry
                            continue
                        }

                        val outFile = when {
                            entry.name == "stash.db" -> {
                                dbFile.parentFile?.mkdirs()
                                dbFile
                            }
                            entry.name.startsWith("datastore/") -> {
                                val file = File(datastoreDir, entry.name.substringAfter("datastore/"))
                                file.parentFile?.mkdirs()
                                file
                            }
                            else -> null
                        }

                        if (outFile != null) {
                            android.util.Log.d("BackupManager", "Restoring ${entry.name} to ${outFile.absolutePath}")

                            // If it's the storage preferences, try to peek at the tree URI
                            if (entry.name.contains("storage_preferences")) {
                                val bytes = zipIn.readBytes()
                                val content = String(bytes, Charsets.UTF_8)
                                // Look for SAF tree URI pattern
                                val regex = Regex("content://[a-zA-Z0-9./%:]+/tree/[a-zA-Z0-9./%:]+")
                                regex.find(content)?.value?.let { restoredTreeUri = it.toUri() }

                                FileOutputStream(outFile).use { output ->
                                    output.write(bytes)
                                }
                            } else {
                                FileOutputStream(outFile).use { output ->
                                    zipIn.copyTo(output)
                                }
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
            android.util.Log.i("BackupManager", "Import completed successfully. Restored URI: $restoredTreeUri")
            restoredTreeUri
        }
    }
}
