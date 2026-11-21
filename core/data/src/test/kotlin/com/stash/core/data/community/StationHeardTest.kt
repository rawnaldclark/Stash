package com.stash.core.data.community

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Two minutes in a station's sessions before a vote (Sessions; the Worker's `PUT stations/{id}/heard`). */
@RunWith(RobolectricTestRunner::class)
class StationHeardTest {
    private lateinit var server: MockWebServer
    private lateinit var file: File
    private val key = "k".repeat(43)
    private var existingKey: String? = key
    private val on = MutableStateFlow(true)
    private var now = 1_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var heard: StationHeard

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        file = File.createTempFile("station_heard", ".preferences_pb").also { it.delete() }
        val api = StationsApiClient(OkHttpClient()).apply { baseUrls = listOf(server.url("/").toString().removeSuffix("/")) }
        val keys = mockk<CommunityKeyStore> { coEvery { existingKey() } answers { existingKey } }
        val store = PreferenceDataStoreFactory.create { file }
        val marks = CommunityHeard(store, api::heard, keys, on, scope, { now }, "station_heard", "station_heard_waiting")
        heard = StationHeard(store, marks) { now }
    }

    @After fun tearDown() { server.shutdown(); file.delete() }

    private suspend fun settle() = scope.coroutineContext.job.children.forEach { it.join() }

    @Test fun `time adds up across sessions, and at two minutes the station is heard and the Worker told once`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(204))
        heard.listened(A, 60_000); heard.listened(A, 45_000); settle()
        assertThat(heard.ids.first()).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
        heard.listened(A, 15_000); settle()
        assertThat(heard.ids.first()).containsExactly(A)
        val sent = server.takeRequest()
        assertThat(sent.method).isEqualTo("PUT")
        assertThat(sent.path).isEqualTo("/v1/community/stations/$A/heard")
        assertThat(sent.getHeader(CommunityApiClient.KEY_HEADER)).isEqualTo(key)
        heard.listened(A, 300_000); heard.retry(); settle()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `each station counts on its own`() = runBlocking<Unit> {
        heard.listened(A, 90_000); heard.listened(B, 90_000); settle()
        assertThat(heard.ids.first()).isEmpty()
    }

    @Test fun `without a key the mark is kept and goes once the key exists`() = runBlocking<Unit> {
        existingKey = null
        heard.listened(A, 120_000); settle()
        assertThat(heard.ids.first()).containsExactly(A)
        assertThat(server.requestCount).isEqualTo(0)
        existingKey = key
        server.enqueue(MockResponse().setResponseCode(204))
        heard.retry(); settle()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `nothing is sent while Community is off`() = runBlocking<Unit> {
        on.value = false
        heard.listened(A, 120_000); heard.retry(); settle()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `a vote sends the waiting mark first, and an answer that it can't go now holds the vote`() = runBlocking<Unit> {
        existingKey = null
        heard.listened(A, 120_000); settle()
        existingKey = key
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"unavailable"}"""))
        assertThat(heard.beforeVote(A)).isFalse()
        server.enqueue(MockResponse().setResponseCode(204))
        assertThat(heard.beforeVote(A)).isTrue()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `time is forgotten a month after its last listen`() = runBlocking<Unit> {
        heard.listened(A, 100_000)
        now += StationHeard.KEEP_MS
        heard.listened(A, 30_000); settle()
        assertThat(heard.ids.first()).isEmpty()
    }

    @Test fun `at most 200 stations are kept, the longest unheard forgotten first`() = runBlocking<Unit> {
        heard.listened(A, 100_000)
        repeat(StationHeard.MAX_STATIONS) { i -> now += 1; heard.listened("S${i.toString().padStart(7, '0')}", 1_000) }
        heard.listened(A, 30_000); settle()
        assertThat(heard.ids.first()).isEmpty() // A's 100 s went to make room
    }

    private companion object {
        const val A = "Ab3dEf9h"
        const val B = "Zz9yXw8v"
    }
}
