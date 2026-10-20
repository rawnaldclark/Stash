package com.stash.data.cloud.provider.gdrive

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Drive API v3 shapes and the pure decisions made on them, kept apart from HTTP so the tests can
 * feed recorded answers straight in.
 */
object GoogleDriveJson {

    private val json = Json { ignoreUnknownKeys = true }

    /** `files.get?fields=` [FILE_FIELDS]. Drive sends int64 values (size) as JSON strings. */
    @Serializable
    data class FileJson(
        val id: String,
        val name: String? = null,
        val mimeType: String? = null,
        val size: String? = null,
        val md5Checksum: String? = null,
        val headRevisionId: String? = null,
        val modifiedTime: String? = null,
        val parents: List<String>? = null,
        val trashed: Boolean? = null,
        /** Private to Stash (set on the files it uploads); only asked for by the upload side. */
        val appProperties: Map<String, String>? = null,
    ) {
        val sizeBytes: Long get() = size?.toLongOrNull()?.coerceAtLeast(0) ?: 0

        /**
         * The file's content version, which becomes the `?v=` of its `stash-cloud://` ref and so
         * the stream-cache key. md5Checksum changes exactly when the bytes do (a rename doesn't
         * touch it); headRevisionId and modifiedTime are fallbacks for files Drive sends no md5
         * for.
         */
        val version: String? get() = md5Checksum ?: headRevisionId ?: modifiedTime
    }

    /** `files.list`: one page of files and where the next starts. */
    @Serializable
    data class FileListJson(val files: List<FileJson> = emptyList(), val nextPageToken: String? = null)

    @Serializable
    data class AboutJson(val user: UserJson? = null)

    @Serializable
    data class UserJson(
        val displayName: String? = null,
        val emailAddress: String? = null,
        /** Stable per Google account; the `cloud_accounts.remote_id`. */
        val permissionId: String? = null,
    ) {
        /** The `cloud_accounts.remote_id` this user is stored under (email when Drive gave no permissionId). */
        val remoteId: String? get() = permissionId ?: emailAddress
    }

    @Serializable
    private data class ErrorEnvelope(val error: ErrorJson? = null)

    @Serializable
    private data class ErrorJson(val code: Int? = null, val errors: List<ErrorItem>? = null)

    @Serializable
    private data class ErrorItem(val reason: String? = null)

    /** Fields the scan and the link need: no more (each one costs response bytes on every song). */
    const val FILE_FIELDS = "id,name,mimeType,size,md5Checksum,headRevisionId,modifiedTime,parents,trashed"

    fun file(body: String): FileJson = json.decodeFromString(FileJson.serializer(), body)

    fun fileList(body: String): FileListJson = json.decodeFromString(FileListJson.serializer(), body)

    /** `files.list` fields for songs: the file fields above, per file, plus the paging token. */
    const val LIST_FIELDS = "nextPageToken,files($FILE_FIELDS)"

    /**
     * True when Drive refused because the user's storage is full (`storageQuotaExceeded`, 403):
     * backup pauses and the card says so. Not a rate limit: waiting won't fix it.
     */
    fun isStorageFull(code: Int, body: String): Boolean =
        code == 403 && STORAGE_FULL_REASON in errorReasons(body)

    /** True when [code]/[body] is Drive's "slow down" (429, 5xx, or a 403 rate-limit reason). */
    fun isSlowDown(code: Int, body: String): Boolean =
        code == 429 || code >= 500 || (code == 403 && errorReasons(body).any { it in RATE_LIMIT_REASONS })

    /**
     * A value for a Drive search query (`q`), quoted: backslash and single quote escaped, so a
     * folder named "Guns N' Roses" can't end the string early.
     */
    fun quote(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

    fun about(body: String): AboutJson = json.decodeFromString(AboutJson.serializer(), body)

    /** The `error.errors[].reason` values of a Drive error body; empty for anything unreadable. */
    fun errorReasons(body: String): Set<String> = runCatching {
        json.decodeFromString(ErrorEnvelope.serializer(), body).error?.errors.orEmpty().mapNotNull { it.reason }.toSet()
    }.getOrDefault(emptySet())

    /** What a `files.get` answer means for one picked file. */
    sealed interface Lookup {
        /** Readable and not in the trash. */
        data class Present(val file: FileJson) : Lookup

        /** In the trash, deleted, or Stash lost access to it: the song leaves the library. */
        data object Gone : Lookup

        /** Drive asked us to slow down: retry later, decide nothing about the file. */
        data object Throttled : Lookup

        /** Anything else: the scan stops here (and removes nothing) rather than guess. */
        data class Failed(val status: Int) : Lookup
    }

    /**
     * Classifies a `files.get` answer. A drive.file app gets 404 for a file it has no grant for
     * (the user removed Stash's access, or the file was deleted for good); 403 is either a lost
     * grant (`appNotAuthorizedToFile`, `insufficientFilePermissions`) or Drive's rate limiting,
     * which it reports as 403 too and must never be read as "gone".
     */
    fun classify(code: Int, body: String): Lookup = when {
        code in 200..299 -> {
            val f = file(body)
            if (f.trashed == true) Lookup.Gone else Lookup.Present(f)
        }
        code == 404 -> Lookup.Gone
        code == 403 -> {
            val reasons = errorReasons(body)
            when {
                reasons.any { it in LOST_ACCESS_REASONS } -> Lookup.Gone
                reasons.any { it in RATE_LIMIT_REASONS } -> Lookup.Throttled
                else -> Lookup.Failed(code)
            }
        }
        code == 429 || code >= 500 -> Lookup.Throttled
        else -> Lookup.Failed(code)
    }

    /** True when a 403 on `alt=media` is Google refusing to serve a file it flagged as abusive. */
    fun isAbusiveFileRefusal(code: Int, body: String?): Boolean =
        code == 403 && body != null && ABUSE_REASON in body

    /**
     * The `picked_file_ids` value the Android picker returns in the authorization result's token
     * response params: a comma-separated string per the docs (an array is accepted too). Ids are
     * trimmed, de-duplicated (first order kept) and anything that isn't a Drive id dropped, so a
     * malformed answer can never put a path or URL piece into a request.
     */
    fun pickedIds(raw: Any?): List<String> {
        val parts: List<String> = when (raw) {
            null -> emptyList()
            is String -> raw.split(',')
            is Array<*> -> raw.map { it.toString() }
            is Iterable<*> -> raw.map { it.toString() }
            else -> raw.toString().split(',')
        }
        return parts.map { it.trim() }.filter { DRIVE_ID.matches(it) }.distinct()
    }

    const val ABUSE_REASON = "cannotDownloadAbusiveFile"
    const val STORAGE_FULL_REASON = "storageQuotaExceeded"
    private val LOST_ACCESS_REASONS = setOf("appNotAuthorizedToFile", "insufficientFilePermissions", "notFound")
    /**
     * Drive's 403 "slow down" reasons: per-user and per-project rate limits, and the daily
     * request/download quotas (the project's ~1 TB a day is shared by every user of the app).
     */
    private val RATE_LIMIT_REASONS = setOf(
        "rateLimitExceeded", "userRateLimitExceeded", "sharingRateLimitExceeded",
        "dailyLimitExceeded", "quotaExceeded", "downloadQuotaExceeded",
    )

    /** Drive file ids are URL-safe base64-ish; bounded so a hostile value can't be huge. */
    private val DRIVE_ID = Regex("[A-Za-z0-9_-]{8,256}")
}
