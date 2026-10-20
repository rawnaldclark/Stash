package com.stash.data.cloud.upload

import java.util.concurrent.ConcurrentHashMap

/** Remembered Drive folder ids, per account and folder path ("Stash/artist/album"). */
interface DriveFolderCache {
    suspend fun get(accountId: Long, path: String): String?
    suspend fun put(accountId: Long, path: String, id: String)
    suspend fun remove(accountId: Long, path: String)
    suspend fun clear(accountId: Long)
}

/**
 * Finds or makes My Drive / Stash / <artist> / <album> for an upload.
 *
 * Each level is, in order: the id remembered in [cache] (checked once per [verifyEvery] window that
 * it is still a folder and not in the trash, else forgotten), a folder of that name Stash already
 * made in the parent (another phone, a reinstall: drive.file lists only what the app created, so
 * a folder of the user's own called "Stash" is never written into), or a new one tagged as
 * Stash's. So a deleted or trashed folder is made again, and uploads on two phones end up in the
 * same tree.
 */
class DriveFolders(
    private val api: DriveUploadApi,
    private val cache: DriveFolderCache,
    private val clock: () -> Long = System::currentTimeMillis,
    private val verifyEvery: Long = 60 * 60_000L,
) {
    /** "account:path" → when this process last confirmed the remembered id. */
    private val confirmed = ConcurrentHashMap<String, Long>()

    /** The id of the folder for [folders] (below "Stash"), made where missing. */
    suspend fun resolve(accountId: Long, folders: List<String>): String {
        var parent = ROOT
        var path = ""
        var parentRemembered = true
        for (name in listOf(ROOT_NAME) + folders) {
            path = if (path.isEmpty()) name else "$path/$name"
            val (id, remembered) = level(accountId, parent, path, name, useCache = parentRemembered)
            parent = id
            // Below a folder found or made anew, remembered ids belong to the old tree.
            parentRemembered = remembered
        }
        return parent
    }

    /** The folder at [path] and whether it was the remembered one. */
    private suspend fun level(accountId: Long, parent: String, path: String, name: String, useCache: Boolean): Pair<String, Boolean> {
        val key = "$accountId:$path"
        if (!useCache) {
            cache.remove(accountId, path)
            confirmed.remove(key)
        }
        cache.get(accountId, path)?.let { id ->
            val seen = confirmed[key]
            if (seen != null && clock() - seen < verifyEvery) return id to true
            if (api.folderUsable(accountId, id)) {
                confirmed[key] = clock()
                return id to true
            }
            // Trashed or deleted: forget it.
            cache.remove(accountId, path)
            confirmed.remove(key)
        }
        val id = api.findFolder(accountId, parent, name) ?: api.createFolder(accountId, parent, name)
        cache.put(accountId, path, id)
        confirmed[key] = clock()
        return id to false
    }

    /** Forgets what this process confirmed for [accountId] (the account was disconnected). */
    fun forget(accountId: Long) {
        confirmed.keys.removeAll { it.startsWith("$accountId:") }
    }

    companion object {
        /** The top of My Drive, as Drive's queries and `parents` name it. */
        const val ROOT = "root"
        const val ROOT_NAME = "Stash"
    }
}
