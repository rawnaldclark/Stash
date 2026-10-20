package com.stash.data.cloud.provider.dropbox

import com.stash.core.model.CloudProvider
import com.stash.data.cloud.auth.CloudTokenManager
import com.stash.data.cloud.net.ApiResponse
import com.stash.data.cloud.net.CloudHttp
import com.stash.data.cloud.net.RequestPriority
import com.stash.data.cloud.provider.CloudApiException
import com.stash.data.cloud.provider.CloudEntry
import com.stash.data.cloud.provider.CloudItemNotFoundException
import com.stash.data.cloud.provider.CloudLink
import com.stash.data.cloud.provider.CloudStorageProvider
import com.stash.data.cloud.provider.CursorExpiredException
import com.stash.data.cloud.provider.ScanEntry
import com.stash.data.cloud.provider.ScanPage
import com.stash.data.cloud.provider.ScanRoot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Dropbox API v2 (`files.metadata.read` + `files.content.read`, read-only):
 *  - browse: `files/list_folder` (+ `/continue`), one level;
 *  - scan: `files/list_folder` recursive from the picked folder, then `/continue` with the saved
 *    cursor, which after a completed listing returns only changes; a 409 `reset` → full rescan;
 *  - link: `files/get_temporary_link` (valid 4 h, Range-capable, on *.dropboxusercontent.com).
 *
 * Every call goes through [http] (throttle, Retry-After, one refresh on 401).
 */
class DropboxProvider(
    appKey: String,
    override val auth: DropboxAuth,
    private val http: CloudHttp,
    private val tokens: CloudTokenManager,
    private val clock: () -> Long = System::currentTimeMillis,
) : CloudStorageProvider {

    override val provider = CloudProvider.DROPBOX
    override val isConfigured: Boolean = appKey.isNotBlank()

    /** Temporary links last 4 h; reuse one for 3.5 h so it never expires mid-song. */
    override val linkTtlMs: Long = 210 * 60_000L

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun browse(accountId: Long, folderId: String?): List<CloudEntry> {
        val out = mutableListOf<CloudEntry>()
        var page = listFolder(accountId, folderId.orEmpty(), recursive = false, RequestPriority.INTERACTIVE)
        while (true) {
            page.entries.forEach { e ->
                when (e.tag) {
                    "folder" -> out += CloudEntry(e.id ?: return@forEach, e.name, true, e.pathDisplay ?: "/${e.name}")
                    "file" -> out += CloudEntry(e.id ?: return@forEach, e.name, false, e.pathDisplay ?: "/${e.name}", e.size ?: 0)
                }
            }
            if (!page.hasMore) break
            page = continueFolder(accountId, page.cursor, RequestPriority.INTERACTIVE)
        }
        return out
    }

    override suspend fun scanPage(accountId: Long, root: ScanRoot, cursor: String?): ScanPage {
        val page = if (cursor == null) {
            listFolder(accountId, root.itemId, recursive = true, RequestPriority.BACKGROUND)
        } else {
            continueFolder(accountId, cursor, RequestPriority.BACKGROUND)
        }
        return ScanPage(page.entries.mapNotNull { toScanEntry(it, root) }, page.cursor, page.hasMore)
    }

    override suspend fun describeFolder(accountId: Long, folderId: String): CloudEntry? {
        val r = call(accountId, "files/get_metadata", buildJsonObject { put("path", folderId) }, RequestPriority.BACKGROUND)
        if (r.code == 409) return null
        val e = json.decodeFromString(EntryJson.serializer(), ok(r).body)
        return CloudEntry(e.id ?: folderId, e.name, e.tag == "folder", e.pathDisplay ?: "/${e.name}")
    }

    override suspend fun link(accountId: Long, itemId: String, priority: RequestPriority): CloudLink {
        val r = call(accountId, "files/get_temporary_link", buildJsonObject { put("path", itemId) }, priority)
        if (r.code == 409) throw CloudItemNotFoundException("Dropbox has no file $itemId")
        val t = json.decodeFromString(TemporaryLinkJson.serializer(), ok(r).body)
        return CloudLink(t.link, t.metadata.size ?: 0, t.metadata.rev, clock())
    }

    // ── API plumbing ─────────────────────────────────────────────────────

    private suspend fun listFolder(accountId: Long, path: String, recursive: Boolean, priority: RequestPriority): ListFolderJson {
        val body = buildJsonObject {
            put("path", path)
            put("recursive", recursive)
            put("include_deleted", false)
            put("include_non_downloadable_files", false)
            put("limit", PAGE_LIMIT)
        }
        val r = call(accountId, "files/list_folder", body, priority)
        if (r.code == 409) throw CloudItemNotFoundException("Dropbox folder $path is gone")
        return json.decodeFromString(ListFolderJson.serializer(), ok(r).body)
    }

    private suspend fun continueFolder(accountId: Long, cursor: String, priority: RequestPriority): ListFolderJson {
        val r = call(accountId, "files/list_folder/continue", buildJsonObject { put("cursor", cursor) }, priority)
        if (r.code == 409) {
            // `reset`: the cursor is no longer valid (Dropbox's 410). `path/not_found`: the folder went.
            if ("reset" in r.body) throw CursorExpiredException("Dropbox reset the cursor")
            throw CloudItemNotFoundException("Dropbox folder is gone")
        }
        return json.decodeFromString(ListFolderJson.serializer(), ok(r).body)
    }

    private suspend fun call(accountId: Long, route: String, body: JsonObject, priority: RequestPriority): ApiResponse =
        http.execute(priority, { rejected -> tokens.accessToken(provider, accountId, rejected) }) { token ->
            Request.Builder()
                .url("https://${DropboxAuth.API_HOST}/2/$route")
                .header("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody(JSON))
                .build()
        }

    private fun ok(r: ApiResponse): ApiResponse {
        if (!r.isSuccessful) throw CloudApiException(r.code, "Dropbox answered ${r.code}: ${r.body.take(200)}")
        return r
    }

    private fun toScanEntry(e: EntryJson, root: ScanRoot): ScanEntry? = when (e.tag) {
        "file" -> e.id?.let { id ->
            ScanEntry.File(
                id = id,
                name = e.name,
                size = e.size ?: 0,
                version = e.rev,
                eTag = e.contentHash,
                mimeType = null,
                parentId = null,
                folders = relativeFolders(root.path, e.pathDisplay),
                remotePath = e.pathLower,
            )
        }
        "folder" -> e.id?.let { ScanEntry.Folder(it, e.name, null, e.pathDisplay) }
        "deleted" -> ScanEntry.Removed(null, e.pathLower)
        else -> null
    }

    @Serializable
    internal data class ListFolderJson(
        val entries: List<EntryJson> = emptyList(),
        val cursor: String,
        @SerialName("has_more") val hasMore: Boolean = false,
    )

    @Serializable
    internal data class EntryJson(
        // get_temporary_link's `metadata` is a plain FileMetadata with no ".tag" (it is only
        // a union in list_folder / get_metadata answers), so a missing tag means a file.
        @SerialName(".tag") val tag: String = "file",
        val name: String,
        val id: String? = null,
        @SerialName("path_lower") val pathLower: String? = null,
        @SerialName("path_display") val pathDisplay: String? = null,
        val size: Long? = null,
        val rev: String? = null,
        @SerialName("content_hash") val contentHash: String? = null,
    )

    @Serializable
    internal data class TemporaryLinkJson(val metadata: EntryJson, val link: String)

    companion object {
        private const val PAGE_LIMIT = 2000
        private val JSON = "application/json".toMediaType()

        /**
         * The folder names between [rootPath] and the file at [filePath], e.g. root `/Music`,
         * file `/Music/Artist/Album/01.flac` → [Artist, Album]. Case-insensitive like Dropbox.
         * Empty when the file isn't under the root as far as its path says (moved meanwhile).
         */
        internal fun relativeFolders(rootPath: String, filePath: String?): List<String> {
            val file = filePath ?: return emptyList()
            val root = rootPath.trimEnd('/')
            if (!file.startsWith("$root/", ignoreCase = true)) return emptyList()
            return file.substring(root.length + 1).split('/').dropLast(1).filter { it.isNotEmpty() }
        }
    }
}
