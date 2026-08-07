package com.stash.core.data.library

/**
 * Abstraction over on-disk file existence checks. Defined here in
 * :core:data (rather than importing FileOrganizer directly) because
 * :data:download depends on :core:data, not the reverse — a :core:data
 * class can never hold a direct FileOrganizer reference.
 *
 * The real implementation is storage-mode-aware (internal java.io.File
 * vs external SAF DocumentFile) and lives in FileOrganizer; the binding
 * from this interface to that implementation is registered in
 * :data:download's Hilt module, which is allowed to see both types.
 */
fun interface FileExistenceChecker {
    fun exists(filePath: String): Boolean
}