package com.stash.data.cloud.provider.onedrive

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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * OneDrive through Microsoft Graph v1.0 (`Files.Read`, read-only):
 *  - browse: `/me/drive/root/children`, `/me/drive/items/{id}/children`, paged by `@odata.nextLink`;
 *  - scan: `/me/drive/items/{folder}/delta`, then the saved `@odata.deltaLink`; 410 → full rescan;
 *  - link: `/me/drive/items/{id}?select=…,@microsoft.graph.downloadUrl` (pre-authenticated, Range
 *    capable, short-lived).
 *
 * Paging links come back from the server as absolute URLs; one is only followed when it points
 * at graph.microsoft.com over https, so a tampered response can never make us send the bearer
 * token to another host.
 *
 * Known limit: Graph only supports `delta` on a non-root folder for personal accounts. A work or
 * school account picking a subfolder gets a [CloudApiException] from the scan (see the report).
 */
class OneDriveProvider(
    clientId: String,
    override val auth: OneDriveAuth,
    private val http: CloudHttp,
    private val tokens: CloudTokenManager,
    private val clock: () -> Long = System::currentTimeMillis,
) : CloudStorageProvider {

    override val provider = CloudProvider.ONEDRIVE
    override val isConfigured: Boolean = clientId.isNotBlank()

    /** downloadUrl may expire within minutes (Graph docs); every seek reopens, so cache briefly. */
    override val linkTtlMs: Long = 5 * 60_000L

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun browse(accountId: Long, folderId: String?): List<CloudEntry> {
        val first = if (folderId == null) "$GRAPH/me/drive/root/children" else "$GRAPH/me/drive/items/${seg(folderId)}/children"
        var url: String? = "$first?\$select=$CHILD_SELECT&\$top=200"
        val out = mutableListOf<CloudEntry>()
        while (url != null) {
            val page = json.decodeFromString(PageJson.serializer(), ok(get(accountId, url, RequestPriority.INTERACTIVE)).body)
            page.value.forEach { item ->
                if (item.deleted != null) return@forEach
                out += CloudEntry(item.id, item.name.orEmpty(), item.folder != null, pathOf(item), item.size ?: 0, item.file?.mimeType)
            }
            url = page.nextLink?.let(::requireGraph)
        }
        return out
    }

    override suspend fun scanPage(accountId: Long, root: ScanRoot, cursor: String?): ScanPage {
        val url = cursor?.let(::requireGraph) ?: "$GRAPH/me/drive/items/${seg(root.itemId)}/delta"
        val r = get(accountId, url, RequestPriority.BACKGROUND)
        if (r.code == 410) throw CursorExpiredException("OneDrive delta link expired")
        if (r.code == 404) throw CloudItemNotFoundException("OneDrive folder is gone")
        val page = json.decodeFromString(PageJson.serializer(), ok(r).body)
        val entries = page.value.mapNotNull { toScanEntry(it) }
        val next = page.nextLink
        return if (next != null) {
            ScanPage(entries, requireGraph(next), hasMore = true)
        } else {
            val delta = page.deltaLink ?: throw CloudApiException(r.code, "OneDrive delta page had no link")
            ScanPage(entries, requireGraph(delta), hasMore = false)
        }
    }

    override suspend fun describeFolder(accountId: Long, folderId: String): CloudEntry? {
        val r = get(accountId, "$GRAPH/me/drive/items/${seg(folderId)}?\$select=id,name,folder,parentReference,root", RequestPriority.BACKGROUND)
        if (r.code == 404) return null
        val item = json.decodeFromString(ItemJson.serializer(), ok(r).body)
        return CloudEntry(item.id, item.name.orEmpty(), true, pathOf(item))
    }

    override suspend fun link(accountId: Long, itemId: String, priority: RequestPriority): CloudLink {
        val r = get(accountId, "$GRAPH/me/drive/items/${seg(itemId)}?\$select=id,size,cTag,eTag,file,@microsoft.graph.downloadUrl", priority)
        if (r.code == 404) throw CloudItemNotFoundException("OneDrive has no item $itemId")
        var item = json.decodeFromString(ItemJson.serializer(), ok(r).body)
        // Personal accounts can answer a $select with the old `@content.downloadUrl` name, or
        // leave the link out of a $select answer entirely (seen 2026-10-20): ask once without it.
        if (item.link == null) {
            val full = get(accountId, "$GRAPH/me/drive/items/${seg(itemId)}", priority)
            if (full.code == 404) throw CloudItemNotFoundException("OneDrive has no item $itemId")
            item = json.decodeFromString(ItemJson.serializer(), ok(full).body)
        }
        val url = item.link ?: throw CloudApiException(r.code, "OneDrive item has no download link")
        return CloudLink(url, item.size ?: 0, item.cTag, clock())
    }

    private fun toScanEntry(item: ItemJson): ScanEntry? = when {
        item.deleted != null -> ScanEntry.Removed(item.id, null)
        item.folder != null || item.root != null -> ScanEntry.Folder(item.id, item.name.orEmpty(), item.parentReference?.id, null)
        item.file != null -> ScanEntry.File(
            id = item.id,
            name = item.name.orEmpty(),
            size = item.size ?: 0,
            version = item.cTag,
            eTag = item.eTag,
            mimeType = item.file.mimeType,
            parentId = item.parentReference?.id,
            folders = null, // delta omits parentReference.path; the scanner resolves it from ids
            remotePath = null,
            downloadUrl = item.link,
        )
        else -> null
    }

    private suspend fun get(accountId: Long, url: String, priority: RequestPriority): ApiResponse =
        http.execute(priority, { rejected -> tokens.accessToken(provider, accountId, rejected) }) { token ->
            Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        }

    private fun ok(r: ApiResponse): ApiResponse {
        if (!r.isSuccessful) throw CloudApiException(r.code, "OneDrive answered ${r.code}: ${r.body.take(200)}")
        return r
    }

    @Serializable
    internal data class PageJson(
        val value: List<ItemJson> = emptyList(),
        @SerialName("@odata.nextLink") val nextLink: String? = null,
        @SerialName("@odata.deltaLink") val deltaLink: String? = null,
    )

    @Serializable
    internal data class ItemJson(
        val id: String,
        val name: String? = null,
        val size: Long? = null,
        val cTag: String? = null,
        val eTag: String? = null,
        val file: FileJson? = null,
        val folder: FolderJson? = null,
        val root: RootJson? = null,
        val deleted: DeletedJson? = null,
        val parentReference: ParentJson? = null,
        @SerialName("@microsoft.graph.downloadUrl") val downloadUrl: String? = null,
        @SerialName("@content.downloadUrl") val contentDownloadUrl: String? = null,
    ) {
        /** The pre-authenticated download link under either name Graph uses. */
        val link: String? get() = downloadUrl ?: contentDownloadUrl
    }

    @Serializable internal data class FileJson(val mimeType: String? = null)
    @Serializable internal data class FolderJson(val childCount: Int? = null)
    @Serializable internal class RootJson
    @Serializable internal data class DeletedJson(val state: String? = null)
    @Serializable internal data class ParentJson(val id: String? = null, val path: String? = null)

    companion object {
        const val GRAPH = "https://graph.microsoft.com/v1.0"
        private const val CHILD_SELECT = "id,name,size,folder,file,parentReference"

        private fun seg(id: String) = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")

        /** Only ever follows a Graph paging link to Graph itself (the bearer token rides along). */
        internal fun requireGraph(link: String): String {
            val url = link.toHttpUrlOrNull()
            if (url == null || !url.isHttps || url.host != "graph.microsoft.com") {
                throw CloudApiException(0, "refusing to follow a paging link off graph.microsoft.com")
            }
            return link
        }

        /** "/drive/root:/Music" + "Rips" → "/Music/Rips"; the drive's top is "". */
        internal fun pathOf(item: ItemJson): String {
            if (item.root != null) return ""
            val parent = item.parentReference?.path?.substringAfter("root:", "")?.trimEnd('/').orEmpty()
            return "$parent/${item.name.orEmpty()}"
        }
    }
}
