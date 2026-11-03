package com.stash.core.data.community

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The local heard marks and their sending (contract 2026-11-03; the Worker's route may not be deployed yet). */
@RunWith(RobolectricTestRunner::class)
class CommunityHeardTest {
    private lateinit var server: MockWebServer
    private lateinit var file: File
    private val key = "k".repeat(43)
    private var existingKey: String? = key
    private val on = MutableStateFlow(true)
    private var now = 1_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var api: CommunityApiClient
    private lateinit var heard: CommunityHeard

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        file = File.createTempFile("community_heard", ".preferences_pb").also { it.delete() }
        api = CommunityApiClient(OkHttpClient()).apply { baseUrls = listOf(server.url("/").toString().removeSuffix("/")) }
        val keys = mockk<CommunityKeyStore> { coEvery { existingKey() } answers { existingKey } }
        heard = CommunityHeard(PreferenceDataStoreFactory.create { file }, api, keys, on, scope, clock = { now })
    }

    @After fun tearDown() { server.shutdown(); file.delete() }

    /** Waits for the background sends [CommunityHeard.mark] and [CommunityHeard.retry] started. */
    private suspend fun settle() = scope.coroutineContext.job.children.forEach { it.join() }

    private fun answer(code: Int, body: String = "") = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    @Test fun `a mark is kept at once, then sent once under the key`() = runBlocking<Unit> {
        answer(204)
        heard.mark(A); settle()
        assertThat(heard.ids.first()).containsExactly(A)
        val sent = server.takeRequest()
        assertThat(sent.method).isEqualTo("PUT")
        assertThat(sent.path).isEqualTo("/v1/community/posts/$A/heard")
        assertThat(sent.getHeader(CommunityApiClient.KEY_HEADER)).isEqualTo(key)
        heard.mark(A); heard.retry(); settle()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `a Worker without the route yet (404 not_found) keeps it, and the next read sends it again`() = runBlocking<Unit> {
        answer(404, """{"error":"not_found"}""")
        heard.mark(A); settle()
        assertThat(server.requestCount).isEqualTo(1)
        answer(204)
        heard.retry(); settle()
        assertThat(server.requestCount).isEqualTo(2)
        heard.retry(); settle()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `offline, a 5xx or rate limited keeps it`() = runBlocking<Unit> {
        answer(503, """{"error":"unavailable"}""")
        heard.mark(A); settle()
        answer(429)
        heard.retry(); settle()
        answer(403, "<!DOCTYPE html><title>Just a moment...</title>") // Cloudflare's challenge, not the Worker
        heard.retry(); settle()
        server.shutdown() // offline
        heard.retry(); settle()
        assertThat(heard.ids.first()).containsExactly(A)
        // Back online: it's still waiting, and goes.
        server = MockWebServer().also { it.start() }
        api.baseUrls = listOf(server.url("/").toString().removeSuffix("/"))
        answer(204)
        heard.retry(); settle()
        assertThat(server.takeRequest().path).isEqualTo("/v1/community/posts/$A/heard")
    }

    @Test fun `a gone post or a 403 drops it for good, and the local mark stays`() = runBlocking<Unit> {
        answer(404, """{"error":"gone"}""")
        heard.mark(A); settle()
        answer(403, """{"error":"own_post"}""")
        heard.mark(B); settle()
        heard.retry(); settle()
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(heard.ids.first()).containsExactly(A, B)
    }

    @Test fun `one failure stops the round, so a missing route costs one request per read`() = runBlocking<Unit> {
        existingKey = null
        heard.mark(A); heard.mark(B); settle()
        existingKey = key
        answer(404, """{"error":"not_found"}""")
        heard.retry(); settle()
        assertThat(server.requestCount).isEqualTo(1)
        answer(204); answer(204)
        heard.retry(); settle()
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun `nothing goes without a key or while Community is off, and a vote sends its post's mark first`() = runBlocking<Unit> {
        existingKey = null
        heard.mark(A); settle()
        assertThat(heard.ids.first()).containsExactly(A)
        assertThat(server.requestCount).isEqualTo(0)
        existingKey = key
        on.value = false
        heard.retry(); settle()
        assertThat(server.requestCount).isEqualTo(0)
        on.value = true
        answer(204)
        assertThat(heard.beforeVote(A)).isTrue()
        assertThat(server.takeRequest().path).isEqualTo("/v1/community/posts/$A/heard")
        assertThat(heard.beforeVote(A)).isTrue() // sent already: nothing more
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `a vote whose mark can't go yet doesn't go either, and the mark keeps waiting`() = runBlocking<Unit> {
        existingKey = null
        heard.mark(A); settle()
        existingKey = key
        answer(404, """{"error":"not_found"}""")
        assertThat(heard.beforeVote(A)).isFalse()
        answer(503)
        assertThat(heard.beforeVote(A)).isFalse()
        assertThat(heard.ids.first()).containsExactly(A)
        answer(204)
        heard.retry(); settle()
        assertThat(server.requestCount).isEqualTo(3)
        // Refused for good (the post is gone): the vote goes, and gets the Worker's own answer.
        existingKey = null
        heard.mark(B); settle()
        existingKey = key
        answer(404, """{"error":"gone"}""")
        assertThat(heard.beforeVote(B)).isTrue()
        // No mark here at all (the Worker's own heard: true): nothing to send.
        assertThat(heard.beforeVote("CCCCCCCC")).isTrue()
        assertThat(server.requestCount).isEqualTo(4)
    }

    @Test fun `a vote doesn't wait behind a round stuck on another post`() = runBlocking<Unit> {
        existingKey = null
        heard.mark(B); settle()
        existingKey = key
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.contains(B)) MockResponse().setResponseCode(204).setHeadersDelay(3, TimeUnit.SECONDS)
                else MockResponse().setResponseCode(204)
        }
        heard.retry()
        assertThat(server.takeRequest().path).contains(B) // the round is out, waiting on B
        existingKey = null
        heard.mark(A)
        existingKey = key
        val start = System.nanoTime()
        assertThat(heard.beforeVote(A)).isTrue()
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_000)
        settle()
        // Each mark went once: the next round found nothing waiting.
        heard.retry(); settle()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `a vote while a round is sending the same post shares that request`() = runBlocking<Unit> {
        existingKey = null
        heard.mark(A); settle()
        existingKey = key
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(204).setHeadersDelay(500, TimeUnit.MILLISECONDS)
        }
        heard.retry()
        assertThat(server.takeRequest().path).contains(A)
        assertThat(heard.beforeVote(A)).isTrue()
        settle()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `a vote answered listen_first forgets the mark, and a new listen sends it again`() = runBlocking<Unit> {
        answer(204)
        heard.mark(A); settle()
        assertThat(heard.forget(A)).isTrue()
        assertThat(heard.ids.first()).isEmpty()
        answer(204)
        heard.mark(A); settle()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `listen_first never erases a listen the Worker hasn't had yet`() = runBlocking<Unit> {
        answer(404, """{"error":"not_found"}""")
        heard.mark(A); settle()
        assertThat(heard.forget(A)).isFalse()
        assertThat(heard.ids.first()).containsExactly(A)
        // Still waiting: the next read sends it.
        answer(204)
        heard.retry(); settle()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `marks are forgotten once their post can't be live any more`() = runBlocking<Unit> {
        existingKey = null
        heard.mark(A)
        now += CommunityHeard.KEEP_MS
        heard.mark(B)
        assertThat(heard.ids.first()).containsExactly(B)
    }

    private companion object {
        const val A = "AAAAAAAA"
        const val B = "BBBBBBBB"
    }
}
