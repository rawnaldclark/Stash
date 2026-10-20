package com.stash.data.cloud.provider.onedrive

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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Microsoft identity platform sign-in for OneDrive: authorization code + PKCE against the
 * `common` endpoint (personal and work/school accounts), public client, scopes
 * `Files.Read offline_access User.Read` (read-only, no admin consent).
 *
 * The redirect is `<applicationId>://oauth/onedrive`, so debug (`com.stash.app.debug`) and
 * release builds each have their own; both must be listed as "Mobile and desktop" redirect URIs
 * on the app registration.
 */
class OneDriveAuth(
    private val clientId: String,
    applicationId: String,
    private val client: OkHttpClient,
) : ProviderAuth {

    val redirectUri: String = "$applicationId://oauth/onedrive"

    private val json = Json { ignoreUnknownKeys = true }

    override fun authorizeUrl(state: String, codeChallenge: String): String =
        "https://$LOGIN_HOST/common/oauth2/v2.0/authorize?" + Urls.query(
            listOf(
                "client_id" to clientId,
                "response_type" to "code",
                "redirect_uri" to redirectUri,
                "response_mode" to "query",
                "scope" to SCOPES,
                "state" to state,
                "code_challenge" to codeChallenge,
                "code_challenge_method" to "S256",
                "prompt" to "select_account",
            ),
        )

    override fun parseCallback(uri: String): CallbackResult {
        if (Urls.base(uri) != Urls.base(redirectUri)) return CallbackResult.NotMine
        val q = Urls.queryParams(uri)
        val code = q["code"]
        val state = q["state"]
        return if (!code.isNullOrEmpty() && !state.isNullOrEmpty()) {
            CallbackResult.Code(code, state)
        } else {
            CallbackResult.Denied(q["error_description"] ?: q["error"] ?: "Sign-in was cancelled", state)
        }
    }

    override suspend fun exchangeCode(code: String, verifier: String): TokenResult = token(
        FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("code_verifier", verifier)
            .add("scope", SCOPES)
            .build(),
        refreshing = false,
    )

    override suspend fun refresh(refreshToken: String): TokenResult = token(
        FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("scope", SCOPES)
            .build(),
        refreshing = true,
    )

    private suspend fun token(form: FormBody, refreshing: Boolean): TokenResult {
        val request = Request.Builder().url("https://$LOGIN_HOST/common/oauth2/v2.0/token").post(form).build()
        client.newCall(request).await().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (refreshing && response.code == 400 && ("invalid_grant" in body || "interaction_required" in body)) {
                    throw RefreshRejectedException("Microsoft sign-in expired")
                }
                throw IOException("Microsoft token endpoint answered ${response.code}")
            }
            val t = json.decodeFromString(TokenJson.serializer(), body)
            return TokenResult(t.accessToken, t.refreshToken, t.expiresIn ?: 3600, t.scope.orEmpty())
        }
    }

    override suspend fun identity(accessToken: String): CloudIdentity {
        val request = Request.Builder()
            .url("${OneDriveProvider.GRAPH}/me?\$select=id,displayName,mail,userPrincipalName")
            .header("Authorization", "Bearer $accessToken")
            .build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("Microsoft profile lookup answered ${response.code}")
            val me = json.decodeFromString(MeJson.serializer(), response.body!!.string())
            val email = me.mail ?: me.userPrincipalName
            return CloudIdentity(me.id, me.displayName ?: email ?: "OneDrive", email)
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
    private data class MeJson(
        val id: String,
        val displayName: String? = null,
        val mail: String? = null,
        val userPrincipalName: String? = null,
    )

    companion object {
        const val LOGIN_HOST = "login.microsoftonline.com"
        const val SCOPES = "Files.Read offline_access User.Read"
    }
}
