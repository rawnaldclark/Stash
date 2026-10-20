package com.stash.data.cloud.upload

import com.stash.core.model.CloudProvider
import com.stash.data.cloud.auth.CloudTokenManager
import com.stash.data.cloud.net.ApiResponse
import com.stash.data.cloud.net.CloudHttp
import com.stash.data.cloud.net.RequestGate
import com.stash.data.cloud.net.RequestPriority
import com.stash.data.cloud.provider.CloudApiException
import com.stash.data.cloud.provider.CloudUnavailableException
import com.stash.data.cloud.provider.gdrive.GoogleDriveJson
import com.stash.data.cloud.provider.gdrive.GoogleDriveJson.FileJson
import com.stash.data.cloud.provider.gdrive.GoogleDriveProvider
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.add

/**
 * The Drive API calls behind uploading (drive.file only: everything here touches files and
 * folders Stash itself created). Sent through Google Drive's [CloudHttp] (the provider's own
 * request gate, so uploads and the scan share Drive's rate limits, and a "slow down" pauses both)
 * with the bearer token from [tokens]; a 401 refreshes it once.
 *
 * Every answer is classified the same way: Drive full → [DriveFullException]; a "slow down"
 * (429, 5xx, a 403 rate-limit reason) → back off every Drive caller, then
 * [CloudUnavailableException] (the worker tries again later); anything else unexpected →
 * [CloudApiException]. A gone sign-in surfaces from [tokens] as CloudAuthRequiredException.
 */
class DriveUploadApi(
    private val http: CloudHttp,
    private val tokens: CloudTokenManager,
) {
    /**
     * Files Stash uploaded earlier for the song [trackKey] and not trashed (this phone, another
     * phone, before a reinstall). The caller keeps one whose md5 matches its file.
     */
    suspend fun findByTrackKey(accountId: Long, trackKey: String): List<FileJson> {
        val q = "appProperties has { key=${GoogleDriveJson.quote(UploadKeys.TRACK_KEY)} and value=${GoogleDriveJson.quote(trackKey)} } " +
            "and trashed = false"
        return list(accountId, q, pageSize = 10)
    }

    /** The folder [name] Stash made inside [parentId] ("root" = top of My Drive), if any. */
    suspend fun findFolder(accountId: Long, parentId: String, name: String): String? {
        val q = "name = ${GoogleDriveJson.quote(name)} and ${GoogleDriveJson.quote(parentId)} in parents " +
            "and mimeType = '$FOLDER_MIME' and trashed = false"
        return list(accountId, q, pageSize = 5).firstOrNull()?.id
    }

    /** True when the folder [id] is still there and not in the trash. */
    suspend fun folderUsable(accountId: Long, id: String): Boolean {
        val r = send(accountId) { token ->
            Request.Builder()
                .url("$API/files/${seg(id)}?fields=id,mimeType,trashed")
                .header("Authorization", "Bearer $token")
                .build()
        }
        if (r.code == 404) return false
        val f = check(r).let { GoogleDriveJson.file(it.body) }
        return f.trashed != true && f.mimeType == FOLDER_MIME
    }

    /** Makes the folder [name] inside [parentId], tagged as Stash's; returns its id. */
    suspend fun createFolder(accountId: Long, parentId: String, name: String): String {
        val body = buildJsonObject {
            put("name", name)
            put("mimeType", FOLDER_MIME)
            put("parents", buildJsonArray { add(parentId) })
            putJsonObject("appProperties") { put(UploadKeys.FOLDER_KEY, "1") }
        }.toString()
        val r = send(accountId) { token ->
            Request.Builder()
                .url("$API/files?fields=id")
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody(JSON))
                .build()
        }
        return GoogleDriveJson.file(check(r).body).id
    }

    /**
     * Opens a resumable upload of [size] bytes as [fileName] in [parentId], tagged with the
     * marker the Drive scan looks for and the song's [trackKey]. Returns the session address
     * (a credential: stored only in the app's database, never logged).
     */
    suspend fun startSession(
        accountId: Long,
        parentId: String,
        fileName: String,
        mimeType: String,
        size: Long,
        trackKey: String,
    ): String {
        val body = buildJsonObject {
            put("name", fileName)
            put("mimeType", mimeType)
            put("parents", buildJsonArray { add(parentId) })
            putJsonObject("appProperties") {
                put(GoogleDriveProvider.UPLOAD_MARKER_KEY, GoogleDriveProvider.UPLOAD_MARKER_VALUE)
                put(UploadKeys.TRACK_KEY, trackKey)
            }
        }.toString()
        val r = send(accountId) { token ->
            Request.Builder()
                .url("$UPLOAD_API/files?uploadType=resumable&fields=${GoogleDriveJson.FILE_FIELDS}")
                .header("Authorization", "Bearer $token")
                .header("X-Upload-Content-Type", mimeType)
                .header("X-Upload-Content-Length", size.toString())
                .post(body.toRequestBody(JSON))
                .build()
        }
        check(r)
        return r.location?.takeIf { it.startsWith("https://") }
            ?: throw CloudApiException(r.code, "Google Drive gave no upload address")
    }

    /**
     * Deletes [fileId], which this upload created a moment ago and turned out corrupt (its md5
     * differs from the phone's file). Never called for any other file.
     */
    suspend fun deleteOwnUpload(accountId: Long, fileId: String) {
        val r = send(accountId) { token ->
            Request.Builder().url("$API/files/${seg(fileId)}").header("Authorization", "Bearer $token").delete().build()
        }
        if (r.code != 404) check(r)
    }

    private suspend fun list(accountId: Long, q: String, pageSize: Int): List<FileJson> {
        val r = send(accountId) { token ->
            val url = "$API/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", q)
                .addQueryParameter("fields", GoogleDriveJson.LIST_FIELDS)
                .addQueryParameter("pageSize", pageSize.toString())
                .addQueryParameter("spaces", "drive")
                .build()
            Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        }
        return GoogleDriveJson.fileList(check(r).body).files
    }

    private suspend fun send(accountId: Long, build: (String?) -> Request): ApiResponse =
        http.execute(RequestPriority.BACKGROUND, { rejected -> tokens.accessToken(CloudProvider.GOOGLE_DRIVE, accountId, rejected) }, build)

    /** Returns [r] when it succeeded; otherwise throws what it means (see the class KDoc). */
    private fun check(r: ApiResponse): ApiResponse {
        if (r.isSuccessful) return r
        if (GoogleDriveJson.isStorageFull(r.code, r.body)) throw DriveFullException()
        if (GoogleDriveJson.isSlowDown(r.code, r.body)) {
            http.backOff(RequestGate.parseRetryAfter(r.retryAfter) ?: RequestGate.DEFAULT_BACKOFF_MS)
            throw CloudUnavailableException("Google Drive asked us to slow down")
        }
        throw CloudApiException(r.code, "Google Drive answered ${r.code}")
    }

    companion object {
        const val API = GoogleDriveProvider.API
        const val UPLOAD_API = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private val JSON = "application/json; charset=UTF-8".toMediaType()

        private fun seg(id: String) = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")
    }
}
