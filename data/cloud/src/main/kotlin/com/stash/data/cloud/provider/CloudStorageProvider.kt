package com.stash.data.cloud.provider

import com.stash.core.model.CloudProvider
import com.stash.data.cloud.auth.ProviderAuth
import com.stash.data.cloud.net.RequestPriority
import java.io.IOException

/**
 * One cloud storage service (Dropbox, OneDrive) as the rest of Stash sees it: sign-in, folder
 * browsing, a paged change feed for the library scan, and short-lived links to a file's bytes.
 *
 * Everything above this interface (scanner, tag reader, link cache, the player's resolving
 * DataSource, downloads, Settings) is provider-agnostic. A provider maps its own API onto these
 * shapes and nothing else: Dropbox's `list_folder` + cursor and OneDrive's `delta` + deltaLink
 * both become [scanPage]; `get_temporary_link` and `@microsoft.graph.downloadUrl` both become
 * [link].
 *
 * Read-only: no implementation ever writes to the user's storage.
 */
interface CloudStorageProvider {
    val provider: CloudProvider

    /** False in a build without this provider's app key / client id: the feature is hidden. */
    val isConfigured: Boolean

    val auth: ProviderAuth

    /** How long a [link] stays usable; the link cache keeps one a little less than this. */
    val linkTtlMs: Long

    /** The folders and files directly inside [folderId] (null = the top of the drive), all pages. */
    suspend fun browse(accountId: Long, folderId: String?): List<CloudEntry>

    /**
     * One page of the change feed under [root]. [cursor] null starts a full listing (every file
     * under the folder, recursively); otherwise it continues from a cursor a previous page
     * returned, which after a completed listing yields only what changed since.
     *
     * @throws CursorExpiredException when the provider no longer accepts [cursor]
     *   (OneDrive 410, Dropbox `reset`): the caller starts a full listing again.
     */
    suspend fun scanPage(accountId: Long, root: ScanRoot, cursor: String?): ScanPage

    /** Name and path of a folder, for files whose page didn't say where they sit (OneDrive). */
    suspend fun describeFolder(accountId: Long, folderId: String): CloudEntry?

    /** A link to [itemId]'s bytes that takes HTTP Range and needs no auth header. */
    suspend fun link(accountId: Long, itemId: String, priority: RequestPriority): CloudLink
}

/** A picked folder as a provider needs it: its id and its display path from the drive's top. */
data class ScanRoot(val itemId: String, val path: String)

/** A browse result. [path] is the display path from the top of the drive ("/Music/Rips"). */
data class CloudEntry(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val path: String,
    val size: Long = 0,
    val mimeType: String? = null,
)

/** What one page of the change feed says. */
sealed interface ScanEntry {
    /**
     * A file under the root (audio or not; the scanner filters).
     *
     * @property folders Folder names from just below the root down to the file's parent, when
     *   the provider's page says (Dropbox, from the file's path). Null when only [parentId] is
     *   known (OneDrive delta omits paths): the scanner resolves it.
     * @property remotePath Lower-cased full path, for providers that report deletions by path.
     * @property downloadUrl A ready link when the page carried one (saves a request).
     */
    data class File(
        val id: String,
        val name: String,
        val size: Long,
        val version: String?,
        val eTag: String?,
        val mimeType: String?,
        val parentId: String?,
        val folders: List<String>?,
        val remotePath: String?,
        val downloadUrl: String? = null,
    ) : ScanEntry

    /** A folder under (or equal to) the root; feeds the scanner's id → path map. */
    data class Folder(val id: String, val name: String, val parentId: String?, val path: String?) : ScanEntry

    /** Something removed: by id (OneDrive) or by lower-cased path, possibly a whole folder (Dropbox). */
    data class Removed(val id: String?, val remotePath: String?) : ScanEntry
}

/**
 * @property cursor Where the next call continues: the next page while [hasMore], else the
 *   position to ask "what changed since" next time (saved on the folder row).
 */
data class ScanPage(val entries: List<ScanEntry>, val cursor: String, val hasMore: Boolean)

/** A file's bytes, for [CloudStorageProvider.linkTtlMs]. [size] and [version] come from the provider's metadata. */
data class CloudLink(val url: String, val size: Long, val version: String?, val fetchedAtMs: Long)

/** The provider no longer accepts a saved cursor; start a full listing. */
class CursorExpiredException(message: String) : IOException(message)

/** The account's sign-in is gone (revoked, expired refresh token, never restored): reconnect. */
class CloudAuthRequiredException(val accountId: Long, message: String) : IOException(message)

/** A provider API error that retrying won't fix ([status] is the HTTP code). */
class CloudApiException(val status: Int, message: String) : IOException(message)

/** The item no longer exists (404/409 not_found): a file deleted or moved since the scan. */
class CloudItemNotFoundException(message: String) : IOException(message)
