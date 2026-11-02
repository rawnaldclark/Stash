package com.stash.data.download.lossless.relay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LosslessConfigFetcherTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: MockWebServer
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val pub = Base64.getEncoder().encodeToString(keys.public.encoded)
    private val body = """{"v":1,"relays":[{"base":"https://b.example","priority":2},{"base":"https://a.example/","priority":1}],"updated_at":1}"""

    private fun sign(bytes: ByteArray): String = Base64.getEncoder().encodeToString(
        Signature.getInstance("SHA256withECDSA").apply { initSign(keys.private); update(bytes) }.sign(),
    )
    private fun fetcher() = LosslessConfigFetcher(ctx, OkHttpClient()).also {
        it.configUrl = server.url("/stash/lossless.json").toString()
        it.publicKeyB64 = pub
    }

    @Before fun setUp() { server = MockWebServer(); server.start(); runBlocking { fetcher().clearForTest() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    @Test fun `valid signature applies and caches the list sorted by priority with bases normalised`() = runTest {
        server.enqueue(MockResponse().setBody(body))
        server.enqueue(MockResponse().setBody(sign(body.toByteArray())))
        val f = fetcher()
        assertThat(f.refresh()).isTrue()
        assertThat(f.relays.value.map { it.base }).containsExactly("https://a.example", "https://b.example").inOrder()
        assertThat(server.takeRequest().path).isEqualTo("/stash/lossless.json")
        assertThat(server.takeRequest().path).isEqualTo("/stash/lossless.json.sig")

        val cold = fetcher()
        cold.loadCached()
        assertThat(cold.relays.value.map { it.base }).containsExactly("https://a.example", "https://b.example").inOrder()
    }

    @Test fun `relay_key rides the signed config, survives the cache, and rotates with it`() = runTest {
        val keyed = """{"v":1,"relays":[{"base":"https://a.example","priority":1}],"updated_at":5,"relay_key":"k-one"}"""
        server.enqueue(MockResponse().setBody(keyed))
        server.enqueue(MockResponse().setBody(sign(keyed.toByteArray())))
        val f = fetcher()
        assertThat(f.refresh()).isTrue()
        assertThat(f.relayKey.value).isEqualTo("k-one")

        // Cold start reads the key back from the cache, not the network.
        val cold = fetcher()
        cold.loadCached()
        assertThat(cold.relayKey.value).isEqualTo("k-one")

        // Rotation = a newer config with a different key; and a config that DROPS
        // the key must clear it, not leave the old one armed.
        val unkeyed = """{"v":1,"relays":[{"base":"https://a.example","priority":1}],"updated_at":6}"""
        server.enqueue(MockResponse().setBody(unkeyed))
        server.enqueue(MockResponse().setBody(sign(unkeyed.toByteArray())))
        assertThat(cold.refresh()).isTrue()
        assertThat(cold.relayKey.value).isNull()
    }

    @Test fun `install id is created once and is stable across instances`() = runTest {
        val a = fetcher().installId()
        val b = fetcher().installId()
        assertThat(a).isNotEmpty()
        assertThat(b).isEqualTo(a)
        // Random, not derived from anything: a UUID shape, no PII.
        assertThat(a).matches("[0-9a-f-]{36}")
    }

    @Test fun `tampered body is ignored and the cached copy kept`() = runTest {
        server.enqueue(MockResponse().setBody(body)); server.enqueue(MockResponse().setBody(sign(body.toByteArray())))
        val f = fetcher(); f.refresh()
        val evil = body.replace("a.example", "evil.example")
        server.enqueue(MockResponse().setBody(evil)); server.enqueue(MockResponse().setBody(sign(body.toByteArray())))
        assertThat(f.refresh()).isFalse()
        assertThat(f.relays.value.map { it.base }).containsExactly("https://a.example", "https://b.example").inOrder()
    }

    @Test fun `network failure keeps the cached copy`() = runTest {
        server.enqueue(MockResponse().setBody(body)); server.enqueue(MockResponse().setBody(sign(body.toByteArray())))
        val f = fetcher()
        assertThat(f.refresh()).isTrue()
        server.shutdown()
        assertThat(f.refresh()).isFalse()
        assertThat(f.relays.value.map { it.base }).containsExactly("https://a.example", "https://b.example").inOrder()
    }

    @Test fun `disabled when url or key is blank`() = runTest {
        val f = fetcher().also { it.publicKeyB64 = "" }
        assertThat(f.enabled).isFalse()
        assertThat(f.refresh()).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
        f.loadCached() // no cache means no relays
        assertThat(f.relays.value).isEmpty()
    }

    @Test fun `an oversized config body is rejected without applying`() = runTest {
        server.enqueue(MockResponse().setBody("x".repeat(70_000)))
        val f = fetcher()
        assertThat(f.refresh()).isFalse()
        assertThat(f.relays.value).isEmpty()
        assertThat(server.requestCount).isEqualTo(1) // the .sig is never fetched
    }

    @Test fun `an older validly-signed config is rejected`() = runTest {
        val newer = """{"v":1,"relays":[{"base":"https://c.example","priority":1}],"updated_at":2}"""
        server.enqueue(MockResponse().setBody(newer)); server.enqueue(MockResponse().setBody(sign(newer.toByteArray())))
        val f = fetcher()
        assertThat(f.refresh()).isTrue()

        val older = """{"v":1,"relays":[{"base":"https://d.example","priority":1}],"updated_at":1}"""
        server.enqueue(MockResponse().setBody(older)); server.enqueue(MockResponse().setBody(sign(older.toByteArray())))
        assertThat(f.refresh()).isFalse()
        assertThat(f.relays.value.map { it.base }).containsExactly("https://c.example")

        val cold = fetcher()
        cold.loadCached()
        assertThat(cold.relays.value.map { it.base }).containsExactly("https://c.example")
    }

    @Test fun `missing sig fails closed`() = runTest {
        server.enqueue(MockResponse().setBody(body)); server.enqueue(MockResponse().setResponseCode(404))
        val f = fetcher()
        assertThat(f.refresh()).isFalse()
        assertThat(f.relays.value).isEmpty()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `empty sig fails closed`() = runTest {
        server.enqueue(MockResponse().setBody(body)); server.enqueue(MockResponse().setBody(""))
        val f = fetcher()
        assertThat(f.refresh()).isFalse()
        assertThat(f.relays.value).isEmpty()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `non-https or malformed bases are dropped`() = runTest {
        val b = """{"v":1,"relays":[{"base":"http://plain.example","priority":1},{"base":"https://ok.example?x=1","priority":1}],"updated_at":1}"""
        server.enqueue(MockResponse().setBody(b)); server.enqueue(MockResponse().setBody(sign(b.toByteArray())))
        val f = fetcher(); f.refresh()
        assertThat(f.relays.value.map { it.base }).containsExactly("https://ok.example")
    }

    @Test fun `a good refresh waits 6 h, failures back off 15 min, 30 min, then hourly`() {
        val f = fetcher()
        assertThat(f.nextDelayMs(0)).isEqualTo(6 * HOUR)
        assertThat(f.nextDelayMs(1)).isEqualTo(15 * MINUTE)
        assertThat(f.nextDelayMs(2)).isEqualTo(30 * MINUTE)
        assertThat(f.nextDelayMs(3)).isEqualTo(60 * MINUTE)
        assertThat(f.nextDelayMs(4)).isEqualTo(60 * MINUTE)
        assertThat(f.nextDelayMs(1_000)).isEqualTo(60 * MINUTE) // no overflow on a long outage
    }

    // ── The refresh loop, on virtual time (the refresh itself is a stub that records when it ran) ──

    /** A network the test switches on and off. */
    private class FakeNetwork(online: Boolean, private val watchable: Boolean = true) : ConfigNetworkSignal {
        val online = MutableStateFlow(online)
        override fun isOnline() = online.value
        override suspend fun awaitOnline(): Boolean {
            if (!watchable) return false // no callback could be registered: returns at once
            online.first { it }
            return true
        }
    }

    /** Starts [LosslessConfigFetcher.refreshLoop]; every attempt lands in the returned list as its virtual time. */
    private fun TestScope.loop(network: FakeNetwork, result: () -> Boolean): List<Long> {
        val attempts = mutableListOf<Long>()
        val f = fetcher().also { it.network = network }
        backgroundScope.launch {
            f.refreshLoop {
                attempts += testScheduler.currentTime
                result()
            }
        }
        runCurrent()
        return attempts
    }

    @Test fun `offline at launch, nothing runs on a timer until the network is back, then it refreshes at once`() = runTest {
        val network = FakeNetwork(online = false)
        val attempts = loop(network) { true }

        advanceTimeBy(24 * HOUR); runCurrent()
        assertThat(attempts).isEmpty() // the old loop would have tried 96 times

        network.online.value = true; runCurrent()
        assertThat(attempts).containsExactly(24 * HOUR)
    }

    @Test fun `a good refresh comes back every 6 h`() = runTest {
        val attempts = loop(FakeNetwork(online = true)) { true }

        advanceTimeBy(12 * HOUR); runCurrent()
        assertThat(attempts).containsExactly(0L, 6 * HOUR, 12 * HOUR).inOrder()
    }

    @Test fun `failures while online back off 15 min, 30 min, then hourly`() = runTest {
        val attempts = loop(FakeNetwork(online = true)) { false }

        advanceTimeBy(165 * MINUTE); runCurrent()
        assertThat(attempts).containsExactly(0L, 15 * MINUTE, 45 * MINUTE, 105 * MINUTE, 165 * MINUTE).inOrder()
    }

    @Test fun `a failure and then the network gone, no more attempts until it is back`() = runTest {
        val network = FakeNetwork(online = true)
        val attempts = loop(network) { false }
        assertThat(attempts).containsExactly(0L)

        network.online.value = false
        advanceTimeBy(10 * HOUR); runCurrent()
        assertThat(attempts).containsExactly(0L) // the 15-min wake-up found no network and waited, no request

        network.online.value = true; runCurrent()
        assertThat(attempts).containsExactly(0L, 10 * HOUR).inOrder()
    }

    @Test fun `after waiting for the network the back-off starts over`() = runTest {
        val network = FakeNetwork(online = true)
        val attempts = loop(network) { false }
        advanceTimeBy(45 * MINUTE); runCurrent() // fails at 0, 15 and 45 min: the next wait is an hour

        network.online.value = false
        advanceTimeBy(75 * MINUTE); runCurrent() // 2 h: the hourly wake-up came at 1 h 45 and found no network
        network.online.value = true; runCurrent() // fails at once at 2 h
        advanceTimeBy(15 * MINUTE); runCurrent()

        assertThat(attempts).containsExactly(0L, 15 * MINUTE, 45 * MINUTE, 2 * HOUR, 2 * HOUR + 15 * MINUTE).inOrder()
    }

    /**
     * No network callback could be registered (the per-app limit): awaitOnline returns at once without waiting. The
     * failure count used to reset every time, so offline the loop retried every 15 min forever, as before this change.
     */
    @Test fun `when the network can't be watched, offline failures still back off to hourly`() = runTest {
        val attempts = loop(FakeNetwork(online = false, watchable = false)) { false }
        advanceTimeBy(4 * HOUR); runCurrent()

        assertThat(attempts).containsExactly(
            0L, 15 * MINUTE, 45 * MINUTE, 105 * MINUTE, 165 * MINUTE, 225 * MINUTE,
        ).inOrder()
    }

    private companion object {
        const val MINUTE = 60 * 1000L
        const val HOUR = 60 * MINUTE
    }
}
