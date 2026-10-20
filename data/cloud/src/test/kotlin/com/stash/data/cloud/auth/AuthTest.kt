package com.stash.data.cloud.auth

import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.model.ServiceToken
import com.stash.core.model.CloudProvider
import com.stash.data.cloud.provider.CloudAuthRequiredException
import com.stash.data.cloud.provider.dropbox.DropboxAuth
import com.stash.data.cloud.provider.onedrive.OneDriveAuth
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class AuthTest {

    // ── PKCE ─────────────────────────────────────────────────────────────

    @Test
    fun `pkce verifier and S256 challenge follow RFC 7636`() {
        val v = Pkce.newVerifier()
        assertThat(v.length).isIn(43..128)
        assertThat(v).matches("[A-Za-z0-9_-]+")
        // RFC 7636 appendix B test vector.
        assertThat(Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
            .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        assertThat(Pkce.newState()).isNotEqualTo(Pkce.newState())
    }

    // ── Dropbox ──────────────────────────────────────────────────────────

    private val dropbox = DropboxAuth("i6dkb4pjyqst9sx", OkHttpClient())

    @Test
    fun `dropbox authorize url is the SDK's 1-connect with PKCE in extra_query_params`() {
        val url = dropbox.authorizeUrl("st4te", "ch4llenge")
        assertThat(url).startsWith("https://www.dropbox.com/1/connect?")
        val q = Urls.queryParams(url)
        assertThat(q["k"]).isEqualTo("i6dkb4pjyqst9sx")
        assertThat(q["state"]).isEqualTo("st4te")
        val extra = Urls.queryParams("x://y/?" + q["extra_query_params"])
        assertThat(extra).containsEntry("code_challenge", "ch4llenge")
        assertThat(extra).containsEntry("code_challenge_method", "S256")
        assertThat(extra).containsEntry("token_access_type", "offline")
        assertThat(extra).containsEntry("response_type", "code")
    }

    @Test
    fun `dropbox redirect gives the code from oauth_token_secret`() {
        val r = dropbox.parseCallback("db-i6dkb4pjyqst9sx://1/connect?oauth_token=oauth2code%3A&oauth_token_secret=THE_CODE&uid=42&state=st4te")
        assertThat(r).isEqualTo(CallbackResult.Code("THE_CODE", "st4te"))
    }

    @Test
    fun `dropbox cancel, and other apps' redirects`() {
        assertThat(dropbox.parseCallback("db-i6dkb4pjyqst9sx://1/connect?state=s")).isInstanceOf(CallbackResult.Denied::class.java)
        assertThat(dropbox.parseCallback("db-otherkey://1/connect?oauth_token=oauth2code%3A&oauth_token_secret=c&state=s"))
            .isEqualTo(CallbackResult.NotMine)
        assertThat(dropbox.parseCallback("https://evil.example/1/connect?oauth_token_secret=c")).isEqualTo(CallbackResult.NotMine)
    }

    // ── OneDrive ─────────────────────────────────────────────────────────

    private val oneDrive = OneDriveAuth("client-123", "com.stash.app.debug", OkHttpClient())

    @Test
    fun `onedrive authorize url carries client, redirect, scopes and PKCE`() {
        val q = Urls.queryParams(oneDrive.authorizeUrl("s", "c"))
        assertThat(q["client_id"]).isEqualTo("client-123")
        assertThat(q["redirect_uri"]).isEqualTo("com.stash.app.debug://oauth/onedrive")
        assertThat(q["scope"]).isEqualTo("Files.Read offline_access User.Read")
        assertThat(q["code_challenge_method"]).isEqualTo("S256")
        assertThat(q["response_type"]).isEqualTo("code")
    }

    @Test
    fun `onedrive redirect parsing`() {
        assertThat(oneDrive.parseCallback("com.stash.app.debug://oauth/onedrive?code=abc&state=s")).isEqualTo(CallbackResult.Code("abc", "s"))
        assertThat(oneDrive.parseCallback("com.stash.app.debug://oauth/onedrive?error=access_denied&state=s"))
            .isEqualTo(CallbackResult.Denied("access_denied", "s"))
        // The release build's redirect is not the debug build's.
        assertThat(oneDrive.parseCallback("com.stash.app://oauth/onedrive?code=abc&state=s")).isEqualTo(CallbackResult.NotMine)
    }

    // ── Token manager ────────────────────────────────────────────────────

    private class MemoryStore : CloudTokenStore {
        val map = mutableMapOf<String, ServiceToken>()
        override suspend fun get(key: String) = map[key]
        override suspend fun save(key: String, token: ServiceToken) { map[key] = token }
        override suspend fun clear(key: String) { map.remove(key) }
    }

    private class FakeAuth : ProviderAuth {
        var refreshes = 0
        var reject = false
        override fun authorizeUrl(state: String, codeChallenge: String) = ""
        override fun parseCallback(uri: String): CallbackResult = CallbackResult.NotMine
        override suspend fun exchangeCode(code: String, verifier: String) = TokenResult("a0", "r0", 3600)
        override suspend fun refresh(refreshToken: String): TokenResult {
            delay(50)
            if (reject) throw RefreshRejectedException("revoked")
            refreshes++
            // Rotates the refresh token on the first refresh only.
            return TokenResult("a$refreshes", if (refreshes == 1) "r$refreshes" else null, 3600)
        }
        override suspend fun identity(accessToken: String) = CloudIdentity("id", "Me", null)
    }

    @Test
    fun `concurrent callers share one refresh, rotated refresh token is saved`() = runTest {
        val store = MemoryStore()
        val auth = FakeAuth()
        val tm = CloudTokenManager(store, mapOf(CloudProvider.DROPBOX to auth))
        tm.nowSeconds = { 10_000 }
        store.map["dropbox_1"] = ServiceToken("old", "r0", expiresAtEpoch = 10_050) // inside the 120 s margin

        val tokens = (1..5).map { async { tm.accessToken(CloudProvider.DROPBOX, 1) } }.awaitAll()

        assertThat(tokens.toSet()).containsExactly("a1")
        assertThat(auth.refreshes).isEqualTo(1)
        assertThat(store.map["dropbox_1"]!!.refreshToken).isEqualTo("r1")
    }

    @Test
    fun `a 401 refreshes once even when several requests saw it`() = runTest {
        val store = MemoryStore()
        val auth = FakeAuth()
        val tm = CloudTokenManager(store, mapOf(CloudProvider.ONEDRIVE to auth))
        tm.nowSeconds = { 0 }
        store.map["onedrive_2"] = ServiceToken("t0", "r0", expiresAtEpoch = 99_999)

        val a = tm.accessToken(CloudProvider.ONEDRIVE, 2, rejected = "t0")
        val b = tm.accessToken(CloudProvider.ONEDRIVE, 2, rejected = "t0") // stale report, already refreshed
        assertThat(a).isEqualTo("a1")
        assertThat(b).isEqualTo("a1")
        assertThat(auth.refreshes).isEqualTo(1)
        // Refresh token not rotated on the second (none happened), and kept when the answer omits one.
        assertThat(store.map["onedrive_2"]!!.refreshToken).isEqualTo("r1")
    }

    @Test(expected = CloudAuthRequiredException::class)
    fun `a refused refresh means reconnect`() = runTest {
        val store = MemoryStore()
        val auth = FakeAuth().apply { reject = true }
        val tm = CloudTokenManager(store, mapOf(CloudProvider.DROPBOX to auth))
        tm.nowSeconds = { 1_000_000 }
        store.map["dropbox_3"] = ServiceToken("t", "r", expiresAtEpoch = 0)
        tm.accessToken(CloudProvider.DROPBOX, 3)
    }

    @Test(expected = CloudAuthRequiredException::class)
    fun `no token means not signed in`() = runTest {
        CloudTokenManager(MemoryStore(), emptyMap()).accessToken(CloudProvider.DROPBOX, 9)
    }

    @Test
    fun `storing or forgetting a sign-in bumps changes, so the account card re-checks`() = runTest {
        val store = MemoryStore()
        val tm = CloudTokenManager(store, emptyMap())
        val before = tm.changes.value
        tm.saveNew(CloudProvider.DROPBOX, 4, TokenResult("a", "r", 3600))
        assertThat(tm.hasToken(CloudProvider.DROPBOX, 4)).isTrue()
        assertThat(tm.changes.value).isEqualTo(before + 1)
        tm.forget(CloudProvider.DROPBOX, 4)
        assertThat(tm.hasToken(CloudProvider.DROPBOX, 4)).isFalse()
        assertThat(tm.changes.value).isEqualTo(before + 2)
    }

    @Test
    fun `verifier never equals challenge`() {
        val v = Pkce.newVerifier()
        val c = Pkce.challenge(v)
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(v.toByteArray()))
        assertThat(c).isEqualTo(expected)
        assertThat(c).isNotEqualTo(v)
    }
}
