package com.stash.data.cloud.provider.gdrive

import com.stash.data.cloud.auth.CallbackResult
import com.stash.data.cloud.auth.CloudIdentity
import com.stash.data.cloud.auth.ProviderAuth
import com.stash.data.cloud.auth.RefreshRejectedException
import com.stash.data.cloud.auth.TokenResult
import com.stash.data.cloud.net.await
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Google Drive's side of [ProviderAuth], so [com.stash.data.cloud.auth.CloudTokenManager] keeps
 * its tokens like any provider's (encrypted, single-flight, "Reconnect" on rejection).
 *
 * There is no browser redirect and no refresh token: sign-in happens inside Google's picker
 * ([GoogleAuthorizer.pickerIntent]), and the token manager's refresh-token slot holds the Google
 * account name, which [refresh] hands to a silent re-authorization. The browser-flow methods are
 * never reached ([parseCallback] says NotMine to every redirect).
 */
class GoogleDriveAuth(
    private val authorizer: GoogleAuthorizer,
    private val client: OkHttpClient,
) : ProviderAuth {

    override fun authorizeUrl(state: String, codeChallenge: String): String =
        throw UnsupportedOperationException("Google Drive signs in through its picker")

    override fun parseCallback(uri: String): CallbackResult = CallbackResult.NotMine

    override suspend fun exchangeCode(code: String, verifier: String): TokenResult =
        throw UnsupportedOperationException("Google Drive signs in through its picker")

    override suspend fun refresh(refreshToken: String): TokenResult = refresh(refreshToken, null)

    /** Silent re-authorization for the account in [refreshToken]; [RefreshRejectedException] = reconnect. */
    override suspend fun refresh(refreshToken: String, staleAccessToken: String?): TokenResult {
        val account = refreshToken.takeIf { it != NO_ACCOUNT && it.isNotBlank() }
        val token = authorizer.silentToken(account, staleAccessToken)
            ?: throw RefreshRejectedException("Google needs you to reconnect Drive")
        return TokenResult(token, refreshToken, TOKEN_LIFETIME_SECONDS, PlayServicesGoogleAuthorizer.DRIVE_FILE_SCOPE)
    }

    /** Who picked: Drive's `about` (allowed under drive.file), permissionId being stable per account. */
    override suspend fun identity(accessToken: String): CloudIdentity {
        val request = Request.Builder()
            .url(GoogleDriveProvider.ABOUT_URL)
            .header("Authorization", "Bearer $accessToken")
            .build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("Google Drive account lookup answered ${response.code}")
            val user = GoogleDriveJson.about(response.body?.string().orEmpty()).user
                ?: throw IOException("Google Drive account lookup had no user")
            val id = user.remoteId ?: throw IOException("Google Drive account lookup had no id")
            return CloudIdentity(id, user.displayName ?: user.emailAddress ?: "Google Drive", user.emailAddress)
        }
    }

    companion object {
        /**
         * Google access tokens live an hour; Play services doesn't say how much is left. A fresh one
         * comes after every clear, so 55 minutes (minus the manager's margin) stays inside it, and
         * a 401 earlier than that refreshes anyway.
         */
        const val TOKEN_LIFETIME_SECONDS = 55 * 60L

        /** The refresh-token slot when Drive didn't tell us the account's email. */
        const val NO_ACCOUNT = "-"
    }
}
