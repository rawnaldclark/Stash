package com.stash.data.cloud.provider.gdrive

import com.stash.core.data.db.dao.CloudDao
import com.stash.core.model.CloudProvider
import com.stash.data.cloud.auth.CloudTokenManager
import com.stash.data.cloud.net.ApiResponse
import com.stash.data.cloud.net.CloudHttp
import com.stash.data.cloud.net.RequestGate
import com.stash.data.cloud.net.RequestPriority
import com.stash.data.cloud.provider.CloudApiException
import com.stash.data.cloud.provider.CloudAuthRequiredException
import com.stash.data.cloud.provider.CloudEntry
import com.stash.data.cloud.provider.CloudItemNotFoundException
import com.stash.data.cloud.provider.CloudLink
import com.stash.data.cloud.provider.CloudStorageProvider
import com.stash.data.cloud.provider.CloudUnavailableException
import com.stash.data.cloud.provider.CursorExpiredException
import com.stash.data.cloud.provider.ScanEntry
import com.stash.data.cloud.provider.ScanPage
import com.stash.data.cloud.provider.ScanRoot
import com.stash.data.cloud.scan.CloudFiles
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Google Drive under `drive.file` (owner decision 2026-10-20): Stash sees only files the user
 * picked in Google's picker. A picked folder grants none of its files (tested 2026-10-20), so
 * there is no folder to list and no change feed. Instead each account has one "picks" folder row
 * ([PICKS_ITEM_ID]) and its scan walks the picked ids with `files.get`:
 *
 *  - **New picks** ([GooglePickStore]) are scanned as soon as they're picked, and only they
 *    (cursor `new:<lastId>`): adding five songs doesn't re-check five hundred.
 *  - **Every other run** (periodic, Rescan now) is a full pass over every picked id (cursor
 *    `full:<lastId>`), so the scanner's generation sweep removes songs that were trashed, deleted
 *    or whose access was withdrawn, and an edited file (new md5) has its tags read again. After a
 *    completed pass the cursor is `done`; a later run with nothing new pending answers
 *    [CursorExpiredException], which is how the scanner is told to start a full pass.
 *
 * Paging walks ids in sorted order after the last id done, so a pass cut short resumes where it
 * stopped and a pick added mid-pass can't shift an existing song out of it.
 *
 * Streaming has no pre-signed link: [link] is `files/{id}?alt=media` plus the bearer token in
 * [CloudLink.headers], with the size from metadata (Drive may send no Content-Length).
 *
 * **Same account.** A file Stash can't see answers 404, exactly like one that was deleted, so a
 * token for the wrong Google account (a silent re-authorization without a stored account name
 * can land on whichever account the phone offers) would read as "every song is gone". Before a
 * page reports any removal, the token's account (`about`) is checked against the account row,
 * once per token; on a mismatch the token is forgotten (the card shows "Reconnect") and nothing
 * is removed.
 *
 * **Saved from Stash.** Songs Stash itself uploaded ("Save to Google Drive", backup) carry the
 * private app property `stashUpload=1`. drive.file lets the app see every file it created, on any
 * phone signed in to the same Google account, so the account's second folder row
 * ([UPLOADS_ITEM_ID]) is scanned with one paged `files.list` for that property. Each run is a full
 * listing (the scanner's generation sweep removes what was trashed); it costs one request per
 * [UPLOADS_PAGE_SIZE] songs. On the phone that uploaded a song, the file is already linked to its
 * local track, so the scan sees it as known and never adds it twice.
 *
 * Quota: Drive allows about 1 TB of downloads a day per Cloud project, shared by every user of
 * the app. Nothing enforces it here.
 */
class GoogleDriveProvider(
    enabled: Boolean,
    override val auth: GoogleDriveAuth,
    private val http: CloudHttp,
    private val tokens: CloudTokenManager,
    private val dao: CloudDao,
    private val picks: GooglePickStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pageSize: Int = PAGE_SIZE,
) : CloudStorageProvider {

    override val provider = CloudProvider.GOOGLE_DRIVE
    override val isConfigured: Boolean = enabled
    override val pickedFiles: Boolean get() = true

    /**
     * At most this long; each link also stops at its bearer token's expiry
     * ([CloudLink.expiresAtMs]), so a cached link never outlives the token inside it. A 401
     * resolves anew anyway.
     */
    override val linkTtlMs: Long = 30 * 60_000L

    /** No folder browsing under drive.file: the picker is the browser. */
    override suspend fun browse(accountId: Long, folderId: String?): List<CloudEntry> = emptyList()

    /** Parent folders aren't granted under drive.file; songs fall back to their own tags. */
    override suspend fun describeFolder(accountId: Long, folderId: String): CloudEntry? = null

    override suspend fun scanPage(accountId: Long, root: ScanRoot, cursor: String?): ScanPage {
        if (root.itemId == UPLOADS_ITEM_ID) return scanUploads(accountId, cursor)
        val folderId = dao.getFolders(accountId).firstOrNull { it.itemId == root.itemId }?.id
            ?: throw CloudItemNotFoundException("Google Drive picks are gone")
        val position = Cursor.parse(cursor)
        val scanned = dao.folderItemIds(folderId).toSet()
        if (position.after == null) {
            // Start of a pass: picks the scan has filed are now cloud_items rows; stop tracking them.
            picks.pending(folderId).filter { it in scanned }.takeIf { it.isNotEmpty() }?.let { picks.remove(folderId, it) }
        }
        val pending = picks.pending(folderId)
        val mode = when (position.mode) {
            Mode.DONE -> if (pending.isEmpty()) throw CursorExpiredException("Google Drive: time for a full re-check") else Mode.NEW
            else -> position.mode
        }
        val all = (if (mode == Mode.FULL) pending + scanned else pending).sorted()
        val remaining = position.after?.let { after -> all.filter { it > after } } ?: all
        val batch = remaining.take(pageSize)

        val entries = mutableListOf<ScanEntry>()
        val prune = mutableListOf<String>()
        for (id in batch) {
            when (val lookup = lookup(accountId, id, RequestPriority.BACKGROUND)) {
                is GoogleDriveJson.Lookup.Present -> {
                    val f = lookup.file
                    val name = f.name.orEmpty()
                    if (!CloudFiles.isAudio(name, f.mimeType)) {
                        prune += id // a folder or some other file picked by mistake: nothing to play
                        continue
                    }
                    entries += ScanEntry.File(
                        id = f.id, name = name, size = f.sizeBytes, version = f.version,
                        eTag = f.headRevisionId, mimeType = f.mimeType, parentId = f.parents?.firstOrNull(),
                        // Never null: a null would make the scanner ask describeFolder for a
                        // parent drive.file can't see. Names come from the tags or the file name.
                        folders = emptyList(), remotePath = null,
                    )
                }
                GoogleDriveJson.Lookup.Gone -> {
                    prune += id
                    entries += ScanEntry.Removed(id, null)
                }
                GoogleDriveJson.Lookup.Throttled -> throw CloudUnavailableException(SLOW_DOWN)
                is GoogleDriveJson.Lookup.Failed -> throw CloudApiException(lookup.status, "Google Drive answered ${lookup.status}")
            }
        }
        // Nothing leaves the library (or the pending list) on the word of another account's token.
        if (entries.any { it is ScanEntry.Removed }) requireSameAccount(accountId)
        if (prune.isNotEmpty()) picks.remove(folderId, prune)
        val hasMore = remaining.size > batch.size
        val next = if (hasMore) Cursor(mode, batch.last()).encode() else Cursor.DONE
        return ScanPage(entries, next, hasMore)
    }

    /**
     * One page of the songs Stash uploaded to this Drive. Cursor: null = first page of a full
     * listing, `page:<token>` = the next page, `done` after the last (the next run is a new full
     * listing: [CursorExpiredException]).
     */
    private suspend fun scanUploads(accountId: Long, cursor: String?): ScanPage {
        if (cursor == Cursor.DONE) throw CursorExpiredException("Google Drive: list saved songs again")
        val pageToken = cursor?.takeIf { it.startsWith(PAGE_PREFIX) }?.removePrefix(PAGE_PREFIX)
        // The listing's sweep removes every saved song it didn't see: never on another account's word.
        requireSameAccount(accountId)
        val r = http.execute(RequestPriority.BACKGROUND, { rejected -> tokens.accessToken(provider, accountId, rejected) }) { token ->
            val url = "$API/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", UPLOADS_QUERY)
                .addQueryParameter("fields", "nextPageToken,files(${GoogleDriveJson.FILE_FIELDS},appProperties)")
                .addQueryParameter("pageSize", UPLOADS_PAGE_SIZE.toString())
                .addQueryParameter("spaces", "drive")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        }
        if (!r.isSuccessful) {
            if (GoogleDriveJson.isSlowDown(r.code, r.body)) {
                http.backOff(RequestGate.parseRetryAfter(r.retryAfter) ?: RequestGate.DEFAULT_BACKOFF_MS)
                throw CloudUnavailableException(SLOW_DOWN)
            }
            throw CloudApiException(r.code, "Google Drive answered ${r.code}")
        }
        val page = GoogleDriveJson.fileList(r.body)
        val entries = page.files.filter { it.trashed != true && CloudFiles.isAudio(it.name.orEmpty(), it.mimeType) }.map { f ->
            ScanEntry.File(
                id = f.id, name = f.name.orEmpty(), size = f.sizeBytes, version = f.version,
                eTag = f.headRevisionId, mimeType = f.mimeType, parentId = f.parents?.firstOrNull(),
                // Names come from the song's own tags (Stash embeds them in its downloads).
                folders = emptyList(), remotePath = null,
                songKey = f.appProperties?.get(com.stash.data.cloud.upload.UploadKeys.TRACK_KEY),
            )
        }
        val next = page.nextPageToken?.takeIf { it.isNotBlank() }
        return ScanPage(entries, next?.let { PAGE_PREFIX + it } ?: Cursor.DONE, hasMore = next != null)
    }

    override suspend fun link(accountId: Long, itemId: String, priority: RequestPriority): CloudLink {
        var usedToken: String? = null
        val lookup = lookup(accountId, itemId, priority) { usedToken = it }
        val file = when (lookup) {
            is GoogleDriveJson.Lookup.Present -> lookup.file
            GoogleDriveJson.Lookup.Gone -> throw CloudItemNotFoundException("Google Drive file is gone or no longer shared with Stash")
            // Connectivity-type, not a problem with this file: a scan pauses rather than filing
            // the song without its tags.
            GoogleDriveJson.Lookup.Throttled -> throw CloudUnavailableException(SLOW_DOWN)
            is GoogleDriveJson.Lookup.Failed -> throw CloudApiException(lookup.status, "Google Drive answered ${lookup.status}")
        }
        // The token that just worked (after any 401 refresh) is the one the content request uses.
        val token = usedToken ?: throw CloudApiException(0, "no Google Drive token")
        // The link dies with its token. A token already replaced meanwhile: don't cache the link at all.
        val now = clock()
        val expiresAt = tokens.usableUntilMs(provider, accountId, token) ?: now
        return CloudLink(mediaUrl(itemId), file.sizeBytes, file.version, now, mapOf("Authorization" to "Bearer $token"), expiresAt)
    }

    /**
     * `files.get` for [id], classified. [onToken] sees the token each attempt used (the last one
     * is the one that answered). A "slow down" pauses every Drive caller through the gate, as a
     * 429 does for the other providers, before the caller gives up for now.
     */
    private suspend fun lookup(
        accountId: Long,
        id: String,
        priority: RequestPriority,
        onToken: (String?) -> Unit = {},
    ): GoogleDriveJson.Lookup {
        val r: ApiResponse = http.execute(priority, { rejected -> tokens.accessToken(provider, accountId, rejected) }) { token ->
            onToken(token)
            metadataRequest(id, token)
        }
        val lookup = GoogleDriveJson.classify(r.code, r.body)
        if (lookup == GoogleDriveJson.Lookup.Throttled) {
            http.backOff(RequestGate.parseRetryAfter(r.retryAfter) ?: RequestGate.DEFAULT_BACKOFF_MS)
        }
        return lookup
    }

    /** accountId → the token last confirmed to belong to that account. */
    private val confirmedTokens = ConcurrentHashMap<Long, String>()

    /**
     * Throws unless the current token is the account row's Google account. A token for another
     * account is forgotten ([CloudAuthRequiredException], "Reconnect"), never used to remove songs.
     */
    private suspend fun requireSameAccount(accountId: Long) {
        val expected = dao.getAccount(accountId)?.remoteId ?: throw CloudItemNotFoundException("Google Drive account is gone")
        if (confirmedTokens[accountId] == tokens.accessToken(provider, accountId)) return
        var usedToken: String? = null
        val r = http.execute(RequestPriority.BACKGROUND, { rejected -> tokens.accessToken(provider, accountId, rejected) }) { token ->
            usedToken = token
            Request.Builder().url(ABOUT_URL).header("Authorization", "Bearer $token").build()
        }
        if (!r.isSuccessful) {
            if (r.code == 429 || r.code >= 500) {
                http.backOff(RequestGate.parseRetryAfter(r.retryAfter) ?: RequestGate.DEFAULT_BACKOFF_MS)
                throw CloudUnavailableException(SLOW_DOWN)
            }
            throw CloudApiException(r.code, "Google Drive account lookup answered ${r.code}")
        }
        val actual = runCatching { GoogleDriveJson.about(r.body).user?.remoteId }.getOrNull()
            ?: throw CloudApiException(r.code, "Google Drive account lookup had no id")
        if (actual != expected) {
            tokens.forget(provider, accountId)
            throw CloudAuthRequiredException(accountId, "Google Drive answered for another Google account")
        }
        usedToken?.let { confirmedTokens[accountId] = it }
    }

    private fun metadataRequest(id: String, token: String?): Request =
        Request.Builder()
            .url("$API/files/${seg(id)}?fields=${GoogleDriveJson.FILE_FIELDS}&supportsAllDrives=true")
            .header("Authorization", "Bearer $token")
            .build()

    internal enum class Mode { FULL, NEW, DONE }

    /** Where a pass stands: its kind and the last id it finished (ids are walked in sorted order). */
    internal data class Cursor(val mode: Mode, val after: String?) {
        fun encode(): String = "${mode.name.lowercase()}:${after.orEmpty()}"

        companion object {
            const val DONE = "done"

            /** null (no cursor) = a full pass from the start; anything unreadable is treated the same. */
            fun parse(raw: String?): Cursor {
                if (raw == null) return Cursor(Mode.FULL, null)
                if (raw == DONE) return Cursor(Mode.DONE, null)
                val mode = when (raw.substringBefore(':')) {
                    "full" -> Mode.FULL
                    "new" -> Mode.NEW
                    else -> return Cursor(Mode.FULL, null)
                }
                return Cursor(mode, raw.substringAfter(':', "").ifEmpty { null })
            }
        }
    }

    companion object {
        const val API = "https://www.googleapis.com/drive/v3"

        /** Who a token belongs to (allowed under drive.file); permissionId is stable per account. */
        const val ABOUT_URL = "$API/about?fields=user(displayName,emailAddress,permissionId)"

        /** The one folder row per account that holds its picked songs. */
        const val PICKS_ITEM_ID = "picked"
        const val PICKS_NAME = "Picked songs"

        /** The folder row per account for songs Stash saved to the user's Drive. */
        const val UPLOADS_ITEM_ID = "stash-uploads"
        const val UPLOADS_NAME = "Saved from Stash"
        const val UPLOADS_PATH = "/Stash"

        /** The private property every file Stash uploads carries, and the search for it. */
        const val UPLOAD_MARKER_KEY = "stashUpload"
        const val UPLOAD_MARKER_VALUE = "1"
        const val UPLOADS_QUERY =
            "appProperties has { key='$UPLOAD_MARKER_KEY' and value='$UPLOAD_MARKER_VALUE' } and trashed = false"

        /** Songs per `files.list` page of saved songs (Drive allows up to 1000). */
        const val UPLOADS_PAGE_SIZE = 200
        private const val PAGE_PREFIX = "page:"

        /** One `files.get` per id; at the gate's ~5/s a page is a few seconds of work, saved after. */
        const val PAGE_SIZE = 25

        /** The file's bytes. No token in the URL: it goes in the Authorization header. */
        fun mediaUrl(itemId: String): String = "$API/files/${seg(itemId)}?alt=media&supportsAllDrives=true"

        /**
         * True when a content request's 403 [body] says Google refuses to serve the file because
         * it flagged it as malware/abuse (`cannotDownloadAbusiveFile`). Stash doesn't send
         * `acknowledgeAbuse`; the user sees "Google blocked this file" instead.
         */
        fun isAbusiveFileRefusal(code: Int, body: ByteArray?): Boolean =
            GoogleDriveJson.isAbusiveFileRefusal(code, body?.let { String(it, Charsets.UTF_8) })

        const val BLOCKED_MESSAGE = "Google blocked this file"

        private const val SLOW_DOWN = "Google Drive asked us to slow down"

        private fun seg(id: String) = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")
    }
}
