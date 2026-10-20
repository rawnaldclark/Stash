package com.stash.data.cloud.provider.dropbox

import com.stash.data.cloud.auth.CallbackResult
import com.stash.data.cloud.auth.CloudIdentity
import com.stash.data.cloud.auth.ProviderAuth
import com.stash.data.cloud.auth.RefreshRejectedException
import com.stash.data.cloud.auth.TokenResult
import com.stash.data.cloud.auth.Urls
import com.stash.data.cloud.net.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale

/**
 * Dropbox sign-in: PKCE, `token_access_type=offline` (so we get a refresh token), public client.
 *
 * **Redirect.** This uses the same browser flow as Dropbox's official Android SDK
 * (`com.dropbox.core.android.AuthActivity.startWebAuth`): the browser opens
 * `https://www.dropbox.com/1/connect?k=<app key>&state=…&extra_query_params=<PKCE params>`, and
 * Dropbox sends the user back to `db-<app key>://1/connect?oauth_token=oauth2code:&oauth_token_secret=<code>&uid=…&state=…`.
 * That `db-<app key>` scheme is accepted for every app without registering a redirect URI in the
 * console (it is how every app built on the official SDK signs in), so nothing beyond the app
 * key is needed. The code is then exchanged at `/oauth2/token` with the PKCE verifier and no
 * `redirect_uri` (none was sent), exactly as the SDK's `DbxPKCEManager` does.
 *
 * If Dropbox ever retires `/1/connect`, switch [authorizeUrl] to the standard
 * `https://www.dropbox.com/oauth2/authorize` with a `redirect_uri` registered in the app console.
 */
class DropboxAuth(
    private val appKey: String,
    private val client: OkHttpClient,
) : ProviderAuth {

    private val json = Json { ignoreUnknownKeys = true }

    /** The redirect Dropbox sends the browser to. */
    val redirectBase: String get() = "db-${appKey.lowercase(Locale.ROOT)}://1/connect"

    override fun authorizeUrl(state: String, codeChallenge: String): String {
        val extra = Urls.query(
            listOf(
                "code_challenge" to codeChallenge,
                "code_challenge_method" to "S256",
                "token_access_type" to "offline",
                "response_type" to "code",
            ),
        )
        val locale = Locale.getDefault().let { "${it.language}_${it.country}" }.trimEnd('_')
        return "https://$WEB_HOST/1/connect?" + Urls.query(
            listOf("k" to appKey, "n" to "0", "state" to state, "extra_query_params" to extra, "locale" to locale),
        )
    }

    override fun parseCallback(uri: String): CallbackResult {
        if (Urls.base(uri) != redirectBase) return CallbackResult.NotMine
        val q = Urls.queryParams(uri)
        val state = q["state"]
        val token = q["oauth_token"]
        val code = q["oauth_token_secret"]
        return if (token == OAUTH2_CODE && !code.isNullOrEmpty() && !state.isNullOrEmpty()) {
            CallbackResult.Code(code, state)
        } else {
            // A cancel or a denial comes back without a code (sometimes with error / error_description).
            CallbackResult.Denied(q["error_description"] ?: q["error"] ?: "Sign-in was cancelled", state)
        }
    }

    override suspend fun exchangeCode(code: String, verifier: String): TokenResult = token(
        FormBody.Builder()
            .add("code", code)
            .add("grant_type", "authorization_code")
            .add("code_verifier", verifier)
            .add("client_id", appKey)
            .build(),
        refreshing = false,
    )

    override suspend fun refresh(refreshToken: String): TokenResult = token(
        FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", appKey)
            .build(),
        refreshing = true,
    )

    private suspend fun token(form: FormBody, refreshing: Boolean): TokenResult {
        val request = Request.Builder().url("https://$API_HOST/oauth2/token").post(form).build()
        client.newCall(request).await().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // invalid_grant = the refresh token was revoked or expired: sign in again.
                if (refreshing && response.code in 400..401 && "invalid_grant" in body) {
                    throw RefreshRejectedException("Dropbox sign-in expired")
                }
                throw IOException("Dropbox token endpoint answered ${response.code}")
            }
            val t = json.decodeFromString(TokenJson.serializer(), body)
            return TokenResult(t.accessToken, t.refreshToken, t.expiresIn ?: DEFAULT_EXPIRES_SECONDS, t.scope.orEmpty())
        }
    }

    override suspend fun identity(accessToken: String): CloudIdentity {
        val request = Request.Builder()
            .url("https://$API_HOST/2/users/get_current_account")
            .header("Authorization", "Bearer $accessToken")
            .post("null".toRequestBody(JSON))
            .build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("Dropbox account lookup answered ${response.code}")
            val a = json.decodeFromString(AccountJson.serializer(), response.body!!.string())
            return CloudIdentity(a.accountId, a.name?.displayName ?: a.email ?: "Dropbox", a.email)
        }
    }

    @Serializable
    private data class TokenJson(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null,
        val scope: String? = null,
    )

    @Serializable
    private data class AccountJson(
        @SerialName("account_id") val accountId: String,
        val name: NameJson? = null,
        val email: String? = null,
    )

    @Serializable
    private data class NameJson(@SerialName("display_name") val displayName: String? = null)

    companion object {
        const val WEB_HOST = "www.dropbox.com"
        const val API_HOST = "api.dropboxapi.com"
        private const val OAUTH2_CODE = "oauth2code:"
        private const val DEFAULT_EXPIRES_SECONDS = 4 * 3600L
        private val JSON = "application/json".toMediaType()
    }
}
