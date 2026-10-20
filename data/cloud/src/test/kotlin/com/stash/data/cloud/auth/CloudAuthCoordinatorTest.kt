package com.stash.data.cloud.auth

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.model.ServiceToken
import com.stash.core.data.db.StashDatabase
import com.stash.core.model.CloudProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A pending sign-in ends only with a redirect carrying its own state: anything else (another
 * app opening the exported redirect activity with a made-up URI) leaves it waiting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CloudAuthCoordinatorTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
        .allowMainThreadQueries().build()

    private class MemoryStore : CloudTokenStore {
        val map = mutableMapOf<String, ServiceToken>()
        override suspend fun get(key: String) = map[key]
        override suspend fun save(key: String, token: ServiceToken) { map[key] = token }
        override suspend fun clear(key: String) { map.remove(key) }
    }

    /** Redirects look like `test://cb?code=…&state=…`. */
    private class FakeAuth : ProviderAuth {
        override fun authorizeUrl(state: String, codeChallenge: String) = "https://auth.example/?state=$state"
        override fun parseCallback(uri: String): CallbackResult {
            if (!uri.startsWith("test://cb")) return CallbackResult.NotMine
            val q = Urls.queryParams(uri)
            val state = q["state"]
            return q["code"]?.let { CallbackResult.Code(it, state.orEmpty()) } ?: CallbackResult.Denied("denied", state)
        }
        override suspend fun exchangeCode(code: String, verifier: String) = TokenResult("access", "refresh", 3600)
        override suspend fun refresh(refreshToken: String) = TokenResult("access2", null, 3600)
        override suspend fun identity(accessToken: String) = CloudIdentity("acct-1", "Me", "me@example.com")
    }

    private val store = MemoryStore()
    private val auths = mapOf<CloudProvider, ProviderAuth>(CloudProvider.DROPBOX to FakeAuth())
    private val coordinator = CloudAuthCoordinator(
        ApplicationProvider.getApplicationContext(), auths, CloudTokenManager(store, auths), db.cloudDao(),
    )

    @After fun tearDown() = db.close()

    @Test
    fun `a forged redirect neither cancels nor completes the user's sign-in`() = runTest {
        val state = Urls.queryParams(coordinator.begin(CloudProvider.DROPBOX))["state"]!!

        val forgedCancel = coordinator.complete("test://cb?state=forged")
        val forgedCode = coordinator.complete("test://cb?code=evil&state=forged")
        assertThat(forgedCancel).isInstanceOf(CloudAuthEvent.Failed::class.java)
        assertThat(forgedCode).isInstanceOf(CloudAuthEvent.Failed::class.java)
        assertThat(db.cloudDao().getAccounts()).isEmpty()

        val real = coordinator.complete("test://cb?code=good&state=$state")

        assertThat(real).isInstanceOf(CloudAuthEvent.Connected::class.java)
        val account = db.cloudDao().getAccounts().single()
        assertThat(account.remoteId).isEqualTo("acct-1")
        assertThat(store.map["dropbox_${account.id}"]!!.refreshToken).isEqualTo("refresh")
        // Single-use: the same redirect again does nothing.
        assertThat(coordinator.complete("test://cb?code=good&state=$state")).isInstanceOf(CloudAuthEvent.Failed::class.java)
    }

    @Test
    fun `a matching cancel ends the sign-in`() = runTest {
        val state = Urls.queryParams(coordinator.begin(CloudProvider.DROPBOX))["state"]!!
        assertThat(coordinator.complete("test://cb?state=$state")).isEqualTo(CloudAuthEvent.Failed(CloudProvider.DROPBOX, "denied"))
        assertThat(coordinator.complete("test://cb?code=late&state=$state")).isInstanceOf(CloudAuthEvent.Failed::class.java)
        assertThat(db.cloudDao().getAccounts()).isEmpty()
    }
}
