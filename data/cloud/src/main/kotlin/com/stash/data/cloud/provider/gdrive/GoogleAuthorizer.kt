package com.stash.data.cloud.provider.gdrive

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import com.stash.data.cloud.provider.CloudUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What came back from Google's picker. */
sealed interface GooglePickerResult {
    /** [fileIds] may be empty (the user confirmed without picking anything). */
    data class Picked(val accessToken: String, val fileIds: List<String>) : GooglePickerResult
    data object Cancelled : GooglePickerResult
    data class Failed(val message: String) : GooglePickerResult
}

/**
 * Google Identity Services, behind an interface so the rest of Google Drive is testable on the
 * JVM. Android has no refresh token for this flow: once `drive.file` is granted, asking again
 * without UI ([silentToken]) returns a valid access token, and when that needs the user again the
 * account shows "Reconnect".
 */
interface GoogleAuthorizer {
    /**
     * A fresh access token for [accountName] without any UI, after clearing [stale] from Play
     * services' token cache (otherwise the same expired token comes back). Null only when Google
     * needs the user (consent withdrawn, account removed from the phone): see [silentFailureNeedsUser].
     *
     * @throws CloudUnavailableException for anything else (no network, Play services busy, updating
     *   or timing out): retry later, don't ask to reconnect.
     */
    suspend fun silentToken(accountName: String?, stale: String?): String?

    /**
     * Drops [token] from Play services' token cache (on disconnect), so it is never handed out
     * again. It does not revoke the app's access: that is done on the Google account's page.
     */
    suspend fun clearToken(token: String)

    /** The picker, as an IntentSender for the Settings screen to launch. */
    suspend fun pickerIntent(accountName: String?): IntentSender

    /** Reads the Intent the picker activity finished with. */
    fun pickerResult(data: Intent?): GooglePickerResult
}

/**
 * The Android Drive Picker (developers.google.com/workspace/drive/picker/guides/overview-desktop,
 * "Android"): an [AuthorizationRequest] for `drive.file` alone with the
 * [AuthorizationRequest.ResourceParameter.PICKER_OAUTH_TRIGGER] resource parameter, multiple
 * selection and an audio MIME filter; consent is asked every time (that screen hosts the picker).
 * The picked ids come back in [AuthorizationResult.getTokenResponseParams] as `picked_file_ids`.
 *
 * No web client id is needed: nothing here asks for a server auth code (`requestOfflineAccess`)
 * and the token is used on the phone only. Google recognises the app by its package name and
 * signing-certificate SHA-1 (an Android OAuth client in the Cloud project).
 */
@Singleton
class PlayServicesGoogleAuthorizer @Inject constructor(
    @ApplicationContext private val context: Context,
) : GoogleAuthorizer {

    private val client get() = Identity.getAuthorizationClient(context)

    override suspend fun silentToken(accountName: String?, stale: String?): String? {
        if (stale != null) {
            try {
                clearToken(stale)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best effort: at worst the same token comes back and its next 401 asks again.
            }
        }
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .apply { accountName?.let { setAccount(Account(it, GOOGLE_ACCOUNT_TYPE)) } }
            .build()
        val result = try {
            client.authorize(request).await()
        } catch (e: ApiException) {
            if (silentFailureNeedsUser(e.statusCode, e.status.hasResolution())) return null
            // Never the message: only the status code, which says nothing about the account.
            throw CloudUnavailableException("Google authorization unavailable (${e.statusCode})")
        }
        // A resolution means Google wants to show UI: that's a reconnect, not something to do silently.
        if (result.hasResolution()) return null
        return result.accessToken
    }

    override suspend fun clearToken(token: String) {
        client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
    }

    override suspend fun pickerIntent(accountName: String?): IntentSender {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            // Only drive.file in this token, whatever else the app was ever granted.
            .setOptOutIncludingGrantedScopes(true)
            .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_OAUTH_TRIGGER, "true")
            .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_ALLOW_MULTIPLE, "true")
            .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_MIMETYPES, PICKER_MIME_TYPES)
            .apply {
                if (accountName != null) {
                    setAccount(Account(accountName, GOOGLE_ACCOUNT_TYPE))
                    setPrompt(AuthorizationRequest.Prompt.CONSENT)
                } else {
                    setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT or AuthorizationRequest.Prompt.CONSENT)
                }
            }
            .build()
        val result = client.authorize(request).await()
        return result.pendingIntent?.intentSender ?: throw IOException("Google returned no picker to show")
    }

    override fun pickerResult(data: Intent?): GooglePickerResult {
        if (data == null) return GooglePickerResult.Cancelled
        val result = try {
            client.getAuthorizationResultFromIntent(data)
        } catch (e: ApiException) {
            return if (e.statusCode == CommonStatusCodes.CANCELED) GooglePickerResult.Cancelled
            else GooglePickerResult.Failed("Google sign-in failed (${e.statusCode}).")
        }
        val token = result.accessToken ?: return GooglePickerResult.Failed("Google gave no access to the files.")
        val ids = GoogleDriveJson.pickedIds(result.tokenResponseParams?.get(PICKED_FILE_IDS))
        return GooglePickerResult.Picked(token, ids)
    }

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
        private const val PICKED_FILE_IDS = "picked_file_ids"

        /**
         * Audio Stash plays, as Drive labels it, plus folders so the user can walk into them
         * (folders themselves can't be selected: PICKER_ALLOW_FOLDER_SELECTION is left off, since
         * under drive.file a picked folder grants none of its files).
         */
        internal const val PICKER_MIME_TYPES =
            "audio/mpeg,audio/mp3,audio/flac,audio/x-flac,audio/mp4,audio/x-m4a,audio/m4a,audio/aac," +
                "audio/ogg,audio/opus,audio/wav,audio/x-wav,audio/vnd.wave," +
                "application/vnd.google-apps.folder"
    }
}

/**
 * True when a silent authorization failed because Google needs the user (the account shows
 * "Reconnect"): Google offers a resolution (a consent or account screen), says sign-in or a
 * resolution is required, or the account is no longer on the phone. Every other status
 * (NETWORK_ERROR, INTERNAL_ERROR, INTERRUPTED, TIMEOUT, API_NOT_CONNECTED, Play services updating
 * or unavailable, ...) is transient and must not cost the user a sign-in.
 */
internal fun silentFailureNeedsUser(statusCode: Int, hasResolution: Boolean): Boolean =
    hasResolution || statusCode in NEEDS_USER_CODES

private val NEEDS_USER_CODES = setOf(
    CommonStatusCodes.SIGN_IN_REQUIRED,
    CommonStatusCodes.RESOLUTION_REQUIRED,
    CommonStatusCodes.INVALID_ACCOUNT,
)

/** Awaits a Play services [Task] without blocking (kotlinx-coroutines-play-services isn't a dependency here). */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
