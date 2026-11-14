package com.stash.data.download.lossless.relay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.LosslessAvailability
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.qbdlx.QbdlxCredentialStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
    /** A config as published before 2026-11: it still lists the retired Qobuz relays beside the Tidal one. */
    private val body = """{"v":1,"relays":[{"base":"https://b.example","priority":2},{"base":"https://a.example/","priority":1}],"updated_at":1,"tidal_relay_url":"https://t.example/","tidal_relay_token":"tok"}"""

    private fun sign(bytes: ByteArray): String = Base64.getEncoder().encodeToString(
        Signature.getInstance("SHA256withECDSA").apply { initSign(keys.private); update(bytes) }.sign(),
    )
    private fun fetcher() = LosslessConfigFetcher(ctx, OkHttpClient()).also {
        it.configUrl = server.url("/stash/lossless.json").toString()
        it.publicKeyB64 = pub
    }

    @Before fun setUp() { server = MockWebServer(); server.start(); runBlocking { fetcher().clearForTest() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    @Test fun `valid signature applies and caches the Tidal relay with its base normalised`() = runTest {
        server.enqueue(MockResponse().setBody(body))
        server.enqueue(MockResponse().setBody(sign(body.toByteArray())))
        val f = fetcher()
        assertThat(f.refresh()).isTrue()
        assertThat(f.tidalRelay.value).isEqualTo(TidalRelay("https://t.example", "tok"))
        assertThat(f.updatedAt.value).isEqualTo(1L)
        assertThat(server.takeRequest().path).isEqualTo("/stash/lossless.json")
        assertThat(server.takeRequest().path).isEqualTo("/stash/lossless.json.sig")

        val cold = fetcher()
        cold.loadCached()
        assertThat(cold.tidalRelay.value).isEqualTo(TidalRelay("https://t.example", "tok"))
    }

    /**
     * The retired shared Qobuz pool: a config that still lists `relays` (every installed config
     * until the owner republishes) parses and applies its Tidal fields, and its relays turn on no
     * lossless path — the Qobuz rung stays off without the user's own account.
     */
    @Test fun `an old config with a non-empty relays list parses and yields no relay-based availability`() = runTest {
        val old = """{"v":1,"relays":[{"base":"https://stash-relay.example.workers.dev","priority":1}],"updated_at":7,"relay_key":"k","tidal_relay_url":"https://t.example"}"""
        server.enqueue(MockResponse().setBody(old))
        server.enqueue(MockResponse().setBody(sign(old.toByteArray())))
        val f = fetcher()
        assertThat(f.refresh()).isTrue()
        assertThat(f.relayKey.value).isEqualTo("k")
        assertThat(f.tidalRelay.value).isEqualTo(TidalRelay("https://t.example", null))

        val store: QbdlxCredentialStore = mockk {
            every { hasLogin } returns MutableStateFlow(false)
            every { connectedEmailFlow } returns MutableStateFlow(null)
            coEvery { loginLive() } returns false
        }
        val prefs: LosslessSourcePreferences = mockk { every { bitchordEndpoint } returns MutableStateFlow(null) }
        val availability = LosslessAvailability(store, f, com.stash.data.download.lossless.tidal.OwnTidalServer(com.stash.core.addons.NoUserAddons, prefs))
        assertThat(availability.qbdlxEnabledNow()).isFalse()
        assertThat(availability.fileUrlAvailableNow()).isFalse()
        assertThat(availability.anySourceConfigured.first()).isTrue() // Tidal, not the relays
        assertThat(availability.routingRows.first().map { it.id }).containsExactly("qobuz", "tidal").inOrder()
    }

    @Test fun `relay_key rides the signed config, survives the cache, and rotates with it`() = runTest {
        val keyed = """{"v":1,"updated_at":5,"relay_key":"k-one","tidal_relay_url":"https://t.example"}"""
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
        val unkeyed = """{"v":1,"updated_at":6,"tidal_relay_url":"https://t.example"}"""
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
        val evil = body.replace("t.example", "evil.example")
        server.enqueue(MockResponse().setBody(evil)); server.enqueue(MockResponse().setBody(sign(body.toByteArray())))
        assertThat(f.refresh()).isFalse()
        assertThat(f.tidalRelay.value?.base).isEqualTo("https://t.example")
    }

    @Test fun `network failure keeps the cached copy`() = runTest {
        server.enqueue(MockResponse().setBody(body)); server.enqueue(MockResponse().setBody(sign(body.toByteArray())))
        val f = fetcher()
        assertThat(f.refresh()).isTrue()
        server.shutdown()
        assertThat(f.refresh()).isFalse()
        assertThat(f.tidalRelay.value?.base).isEqualTo("https://t.example")
    }

    @Test fun `refreshedAt moves only when a refresh applies a fresh config`() = runTest {
        val f = fetcher()
        // A tampered body: refused, so it is not a refresh.
        val evil = body.replace("t.example", "evil.example")
        server.enqueue(MockResponse().setBody(evil)); server.enqueue(MockResponse().setBody(sign(body.toByteArray())))
        assertThat(f.refresh()).isFalse()
        assertThat(f.refreshedAt.value).isEqualTo(0L)

        server.enqueue(MockResponse().setBody(body)); server.enqueue(MockResponse().setBody(sign(body.toByteArray())))
        val before = System.currentTimeMillis()
        assertThat(f.refresh()).isTrue()
        val stamped = f.refreshedAt.value
        assertThat(stamped).isAtLeast(before)

        // Loading the cache never sets one; the network failing afterwards keeps the stamp.
        val cold = fetcher()
        cold.loadCached()
        assertThat(cold.tidalRelay.value).isNotNull()
        assertThat(cold.refreshedAt.value).isEqualTo(0L)
        server.shutdown()
        assertThat(f.refresh()).isFalse()
        assertThat(f.refreshedAt.value).isEqualTo(stamped)
    }

    @Test fun `disabled when url or key is blank`() = runTest {
        val f = fetcher().also { it.publicKeyB64 = "" }
        assertThat(f.enabled).isFalse()
        assertThat(f.refresh()).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
        f.loadCached() // no cache means no relay
        assertThat(f.tidalRelay.value).isNull()
    }

    @Test fun `an oversized config body is rejected without applying`() = runTest {
        server.enqueue(MockResponse().setBody("x".repeat(70_000)))
        val f = fetcher()
        assertThat(f.refresh()).isFalse()
        assertThat(f.tidalRelay.value).isNull()
        assertThat(server.requestCount).isEqualTo(1) // the .sig is never fetched
    }

    // ---------------------------------------------------------------- the cache is checked again (review-881 M9)

    private val evil = """{"v":1,"updated_at":4102444800,"tidal_relay_url":"https://evil.example","default_addons":[{"manifest":"https://evil.example/v1/x/manifest.json","first_party":true}]}"""

    @Test fun `a fetch keeps the signature beside the cache, and a cold start checks it`() = runTest {
        server.enqueue(MockResponse().setBody(body)); server.enqueue(MockResponse().setBody(sign(body.toByteArray())))
        assertThat(fetcher().refresh()).isTrue()

        // The cache as fetched: applied.
        val cold = fetcher()
        cold.loadCached()
        assertThat(cold.tidalRelay.value?.base).isEqualTo("https://t.example")

        // The same cache with other content under the old signature (a crafted settings backup,
        // before restores stopped writing this file): nothing of it is applied.
        fetcher().writeCacheForTest(evil, sign(body.toByteArray()))
        val tampered = fetcher()
        tampered.loadCached()
        assertThat(tampered.tidalRelay.value).isNull()
        assertThat(tampered.defaultAddons.value).isEmpty()
        assertThat(tampered.relayKey.value).isNull()
        assertThat(tampered.updatedAt.value).isEqualTo(0L)
    }

    @Test fun `a foreign cache with a far-future date does not hold the rollback floor`() = runTest {
        fetcher().writeCacheForTest(evil, sign(body.toByteArray()))
        server.enqueue(MockResponse().setBody(body)); server.enqueue(MockResponse().setBody(sign(body.toByteArray())))
        val f = fetcher()
        // updated_at 1 against the foreign cache's year 2100: applied all the same, and the cache is replaced.
        assertThat(f.refresh()).isTrue()
        assertThat(f.tidalRelay.value?.base).isEqualTo("https://t.example")
        val cold = fetcher()
        cold.loadCached()
        assertThat(cold.tidalRelay.value?.base).isEqualTo("https://t.example")
    }

    @Test fun `a cache an older build wrote, with no signature beside it, is honoured until a fetch rewrites both`() = runTest {
        // An install that updates and then starts offline must still have its Tidal relay.
        fetcher().writeCacheForTest(body, sig = null)
        val offline = fetcher()
        offline.loadCached()
        assertThat(offline.tidalRelay.value).isEqualTo(TidalRelay("https://t.example", "tok"))

        // It still is the rollback floor, as it was.
        val older = """{"v":1,"updated_at":0,"tidal_relay_url":"https://d.example"}"""
        server.enqueue(MockResponse().setBody(older)); server.enqueue(MockResponse().setBody(sign(older.toByteArray())))
        assertThat(offline.refresh()).isFalse()

        // The first successful fetch writes cache and signature together...
        val newer = """{"v":1,"updated_at":2,"tidal_relay_url":"https://c.example"}"""
        server.enqueue(MockResponse().setBody(newer)); server.enqueue(MockResponse().setBody(sign(newer.toByteArray())))
        assertThat(offline.refresh()).isTrue()
        val cold = fetcher()
        cold.loadCached()
        assertThat(cold.tidalRelay.value?.base).isEqualTo("https://c.example")

        // ...and from then on the cache is held to it: other content under that signature is ignored.
        fetcher().writeCacheForTest(evil, sign(newer.toByteArray()))
        val tampered = fetcher()
        tampered.loadCached()
        assertThat(tampered.tidalRelay.value).isNull()
    }

    @Test fun `an older validly-signed config is rejected`() = runTest {
        val newer = """{"v":1,"updated_at":2,"tidal_relay_url":"https://c.example"}"""
        server.enqueue(MockResponse().setBody(newer)); server.enqueue(MockResponse().setBody(sign(newer.toByteArray())))
        val f = fetcher()
        assertThat(f.refresh()).isTrue()

        val older = """{"v":1,"updated_at":1,"tidal_relay_url":"https://d.example"}"""
        server.enqueue(MockResponse().setBody(older)); server.enqueue(MockResponse().setBody(sign(older.toByteArray())))
        assertThat(f.refresh()).isFalse()
        assertThat(f.tidalRelay.value?.base).isEqualTo("https://c.example")

        val cold = fetcher()
        cold.loadCached()
        assertThat(cold.tidalRelay.value?.base).isEqualTo("https://c.example")
    }

    @Test fun `missing sig fails closed`() = runTest {
        server.enqueue(MockResponse().setBody(body)); server.enqueue(MockResponse().setResponseCode(404))
        val f = fetcher()
        assertThat(f.refresh()).isFalse()
        assertThat(f.tidalRelay.value).isNull()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `empty sig fails closed`() = runTest {
        server.enqueue(MockResponse().setBody(body)); server.enqueue(MockResponse().setBody(""))
        val f = fetcher()
        assertThat(f.refresh()).isFalse()
        assertThat(f.tidalRelay.value).isNull()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `a non-https Tidal relay is dropped`() = runTest {
        val b = """{"v":1,"updated_at":1,"tidal_relay_url":"http://plain.example"}"""
        server.enqueue(MockResponse().setBody(b)); server.enqueue(MockResponse().setBody(sign(b.toByteArray())))
        val f = fetcher()
        assertThat(f.refresh()).isTrue()
        assertThat(f.tidalRelay.value).isNull()
    }

    @Test fun `a Tidal relay's query is dropped`() = runTest {
        val b = """{"v":1,"updated_at":1,"tidal_relay_url":"https://ok.example?x=1"}"""
        server.enqueue(MockResponse().setBody(b)); server.enqueue(MockResponse().setBody(sign(b.toByteArray())))
        val f = fetcher(); f.refresh()
        assertThat(f.tidalRelay.value?.base).isEqualTo("https://ok.example")
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
