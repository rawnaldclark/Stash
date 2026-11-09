package com.stash.data.download.lossless.qbdlx

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class QbdlxFileUrlRouterTest {
    private val api: QbdlxApiClient = mockk()
    private val store: QbdlxCredentialStore = mockk(relaxUnitFun = true)
    private val router = QbdlxFileUrlRouter(api, store)
    private val login = QbdlxLoginCredential("byo", "798273057", "sec")

    @Test fun `no connected account - no file URL, and Qobuz is not asked`() = runTest {
        coEvery { store.loginCredential() } returns null
        coEvery { store.loginLive() } returns false
        assertThat(router.getFileUrl(42, 27)).isNull()
        coVerify(exactly = 0) { api.getFileUrl(any(), any(), any()) }
    }

    @Test fun `BYO signs locally with the account's own token`() = runTest {
        coEvery { store.loginCredential() } returns login; coEvery { store.loginLive() } returns true
        coEvery { api.getFileUrl(42, 27, "byo") } returns QbdlxResolveResult.Ok("https://cdn/f?etsp=1", "flac", 16, 44_100)
        val r = router.getFileUrl(42, 27)
        assertThat(r).isInstanceOf(QbdlxResolveResult.Ok::class.java)
        coVerify { store.recordAlive("byo") }
    }

    @Test fun `BYO TokenDead rejects the login`() = runTest {
        coEvery { store.loginCredential() } returns login; coEvery { store.loginLive() } returns true
        coEvery { api.getFileUrl(42, 27, "byo") } returns QbdlxResolveResult.TokenDead
        assertThat(router.getFileUrl(42, 27)).isEqualTo(QbdlxResolveResult.TokenDead)
        coVerify { store.rejectLogin("byo") }
        coVerify(exactly = 0) { store.recordAlive(any()) }
    }

    @Test fun `BYO 401 exception is treated as TokenDead`() = runTest {
        coEvery { store.loginCredential() } returns login; coEvery { store.loginLive() } returns true
        coEvery { api.getFileUrl(42, 27, "byo") } throws QbdlxAuthException(401)
        assertThat(router.getFileUrl(42, 27)).isEqualTo(QbdlxResolveResult.TokenDead)
        coVerify { store.rejectLogin("byo") }
    }

    // What rejectLogin then DOES with the token — terminal for a migrated paste,
    // a cooldown for a real account — is the store's decision, covered against a
    // real DataStore in QbdlxCredentialStoreTest. The router's job is only to call it.

    @Test fun `BYO network failure yields null without marking the login dead or alive`() = runTest {
        coEvery { store.loginCredential() } returns login; coEvery { store.loginLive() } returns true
        coEvery { api.getFileUrl(42, 27, "byo") } throws java.io.IOException("reset")
        assertThat(router.getFileUrl(42, 27)).isNull()
        coVerify(exactly = 0) { store.rejectLogin(any()) }
        coVerify(exactly = 0) { store.recordAlive(any()) }
    }

    @Test fun `BYO 5xx yields null without marking the login dead`() = runTest {
        coEvery { store.loginCredential() } returns login; coEvery { store.loginLive() } returns true
        coEvery { api.getFileUrl(42, 27, "byo") } throws QbdlxApiException(503)
        assertThat(router.getFileUrl(42, 27)).isNull()
        coVerify(exactly = 0) { store.rejectLogin(any()) }
    }

    @Test fun `a dead-cooled login is not asked at all`() = runTest {
        coEvery { store.loginCredential() } returns login
        coEvery { store.loginLive() } returns false
        assertThat(router.getFileUrl(42, 27)).isNull()
        coVerify(exactly = 0) { api.getFileUrl(any(), any(), any()) }
    }

    @Test fun `a region-locked answer on the account passes through and counts as alive`() = runTest {
        coEvery { store.loginCredential() } returns login; coEvery { store.loginLive() } returns true
        coEvery { api.getFileUrl(42, 27, "byo") } returns QbdlxResolveResult.RegionLocked
        assertThat(router.getFileUrl(42, 27)).isEqualTo(QbdlxResolveResult.RegionLocked)
        coVerify { store.recordAlive("byo") }
    }
}
