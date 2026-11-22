package com.stash.core.data.share

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.listen.OkHttpRoomConnector
import com.stash.core.model.share.ShareConfig
import kotlinx.coroutines.test.runTest
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test

/**
 * A debug build pointed at a share Worker on this PC (`share.baseUrl=http://127.0.0.1:8811`, Sessions plan step 2 task
 * 2). The app's own client speaks TLS only, so the share clients have to allow cleartext themselves when, and only
 * when, the override is a plain-http address. On the Pixel 5 (2026-11-21) every call failed before this:
 * "CLEARTEXT communication not enabled for client".
 */
class ShareHttpLocalWorkerTest {
    /** As core:network's shared client. */
    private val tlsOnly = OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.MODERN_TLS)).build()
    private val server = MockWebServer()

    @After fun tearDown() {
        ShareConfig.apiOverride = null
        runCatching { server.shutdown() }
    }

    @Test fun `without an override the share clients stay TLS only`() {
        assertThat(tlsOnly.newBuilder().allowLocalShareWorker().build().connectionSpecs).containsExactly(ConnectionSpec.MODERN_TLS)
        assertThat(OkHttpRoomConnector(tlsOnly).client.connectionSpecs).containsExactly(ConnectionSpec.MODERN_TLS)
    }

    @Test fun `an https override stays TLS only too`() {
        ShareConfig.apiOverride = "https://staging.example"
        assertThat(tlsOnly.newBuilder().allowLocalShareWorker().build().connectionSpecs).containsExactly(ConnectionSpec.MODERN_TLS)
    }

    @Test fun `a plain-http override reaches the Worker through the app's TLS-only client`() = runTest {
        server.enqueue(MockResponse().setBody("hello"))
        server.start()
        ShareConfig.apiOverride = server.url("/").toString().trimEnd('/')
        val body = ShareHttp(tlsOnly).exchange(ShareConfig.API_BASE_URLS, "/v1/community/live", { get() }) { _, text -> text }
        assertThat(body).isEqualTo("hello")
    }

    @Test fun `the room socket's client allows it as well`() {
        ShareConfig.apiOverride = "http://127.0.0.1:8811"
        assertThat(OkHttpRoomConnector(tlsOnly).client.connectionSpecs).contains(ConnectionSpec.CLEARTEXT)
    }
}
