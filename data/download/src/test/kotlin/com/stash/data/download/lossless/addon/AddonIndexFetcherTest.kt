package com.stash.data.download.lossless.addon

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Fetching the signed add-on index (add-ons plan phase 4 §1.5, §1.9): what is applied, what is
 * refused and keeps the copy from before, the rollback floor, and the 304. The bodies and
 * signatures are the publisher's own fixtures (see [AddonIndexFixturesTest]), verified with the
 * fixture key set as the fetcher's key, never a `BuildConfig` value (CI builds have none).
 */
class AddonIndexFetcherTest {

    private lateinit var server: MockWebServer
    private val dir: File = Files.createTempDirectory("addon-index").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "addon_index.preferences_pb") }
    private var now = 1_800_000_000_000L

    private fun fixture(path: String): ByteArray =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("addon-index-fixtures/$path")) { "missing fixture $path" }.use { it.readBytes() }

    private val fixtureKey = String(fixture("pubkey.b64")).trim()

    @Before fun setUp() { server = MockWebServer().apply { start(java.net.InetAddress.getByName("127.0.0.1"), 0) } }

    /** An address on the test server, written the same on every machine (never a resolved host name). */
    private fun url(path: String) = "http://127.0.0.1:${server.port}$path"

    @After fun tearDown() {
        runCatching { server.shutdown() }
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun fetcher(debuggable: Boolean = false, key: String = fixtureKey) = AddonIndexFetcher(store, OkHttpClient(), debuggable).also {
        it.publicKeyB64 = key
        it.configUrl = url("/registry/index.json")
        it.nowMs = { now }
    }

    /** Serves a fixture's index and its signature, as the two requests a fetch makes. */
    private fun serve(case: String, etag: String? = null) {
        server.enqueue(MockResponse().setBody(Buffer().write(fixture("$case/index.json"))).apply { if (etag != null) setHeader("ETag", etag) })
        server.enqueue(MockResponse().setBody(Buffer().write(fixture("$case/index.json.sig"))))
    }

    @Test fun `a valid index applies, is kept, and the next run starts from the kept copy`() = runBlocking<Unit> {
        serve("valid")
        val f = fetcher()
        assertThat(f.index.value).isNull()
        assertThat(f.refresh()).isTrue()
        assertThat(f.index.value!!.updatedAt).isEqualTo(1794355200L)
        assertThat(f.index.value!!.addons.map { it.id }).containsExactly("tidal", "example-radio", "org.example.sixty").inOrder()
        assertThat(f.index.value!!.blocked).hasSize(3)
        assertThat(server.takeRequest().path).isEqualTo("/registry/index.json")
        assertThat(server.takeRequest().path).isEqualTo("/registry/index.json.sig")

        // An offline cold start: the kept copy, nothing asked.
        val cold = fetcher()
        cold.loadCached()
        assertThat(cold.index.value).isEqualTo(f.index.value)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `a bad signature keeps the kept copy`() = runBlocking<Unit> {
        serve("valid-launch")
        val f = fetcher()
        assertThat(f.refresh()).isTrue()
        // The same bytes as a newer index, signed by another key; then a body with one byte changed.
        for (bad in listOf("refuse-wrong-signer", "refuse-tampered")) {
            serve(bad)
            assertThat(f.refresh(force = true)).isFalse()
            assertThat(f.index.value!!.addons).hasSize(1)
        }
        // And with no copy at all, nothing is applied.
        val fresh = AddonIndexFetcher(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "other.preferences_pb") }, OkHttpClient(), false).also {
            it.publicKeyB64 = fixtureKey
            it.configUrl = url("/registry/index.json")
        }
        serve("refuse-wrong-signer")
        assertThat(fresh.refresh()).isFalse()
        assertThat(fresh.index.value).isNull()
    }

    @Test fun `an older index is refused - nobody can replay one from before a block`() = runBlocking<Unit> {
        serve("valid")
        val f = fetcher()
        assertThat(f.refresh()).isTrue()
        serve("valid-older")
        assertThat(f.refresh(force = true)).isFalse()
        assertThat(f.index.value!!.updatedAt).isEqualTo(1794355200L)
        // The floor is the kept copy's, so a new run refuses it too.
        serve("valid-older")
        val cold = fetcher()
        assertThat(cold.refresh(force = true)).isFalse()
        assertThat(cold.index.value!!.updatedAt).isEqualTo(1794355200L)
        // By itself the older index is a valid one.
        val other = AddonIndexFetcher(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "older.preferences_pb") }, OkHttpClient(), false).also {
            it.publicKeyB64 = fixtureKey
            it.configUrl = url("/registry/index.json")
        }
        serve("valid-older")
        assertThat(other.refresh()).isTrue()
    }

    @Test fun `a signed lossless json served as the index is refused by its type`() = runBlocking<Unit> {
        serve("valid-launch")
        val f = fetcher()
        assertThat(f.refresh()).isTrue()
        // Signed with the same key, and newer than every index: only `type` stops it.
        serve("refuse-lossless-json")
        assertThat(f.refresh(force = true)).isFalse()
        assertThat(f.index.value!!.addons.single().id).isEqualTo("tidal")
        for (bad in listOf("refuse-wrong-type", "refuse-wrong-v", "refuse-bad-reason", "refuse-too-many-addons", "refuse-too-many-blocked")) {
            serve(bad)
            assertThat(f.refresh(force = true)).isFalse()
        }
        assertThat(f.index.value!!.addons.single().id).isEqualTo("tidal")
    }

    @Test fun `an oversized body is refused before its signature is even asked for`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(Buffer().write(fixture("refuse-oversize/index.json"))))
        val f = fetcher()
        assertThat(f.refresh()).isFalse()
        assertThat(f.index.value).isNull()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `an unchanged index costs a 304 and keeps the copy`() = runBlocking<Unit> {
        serve("valid", etag = "\"abc123\"")
        val f = fetcher()
        assertThat(f.refresh()).isTrue()
        server.takeRequest()
        server.takeRequest()

        now += AddonIndexFetcher.MIN_FETCH_INTERVAL_MS
        server.enqueue(MockResponse().setResponseCode(304))
        assertThat(f.refresh()).isFalse()
        val conditional = server.takeRequest()
        assertThat(conditional.getHeader("If-None-Match")).isEqualTo("\"abc123\"")
        assertThat(f.index.value!!.updatedAt).isEqualTo(1794355200L)
        // No signature fetch for a 304, and the answer counts: nothing more is asked for six hours.
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(f.refresh()).isFalse()
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun `at most one fetch in six hours, a clock set back fetches, and a failed fetch is not a fetch`() = runBlocking<Unit> {
        serve("valid")
        val f = fetcher()
        assertThat(f.refresh()).isTrue()
        now += 60 * 60 * 1000L
        assertThat(f.refresh()).isFalse()
        assertThat(server.requestCount).isEqualTo(2)
        // A new run inside the six hours asks nothing either: the stamp is kept with the copy.
        assertThat(fetcher().refresh()).isFalse()
        assertThat(server.requestCount).isEqualTo(2)
        // The clock was set back: ask.
        now -= 2 * 60 * 60 * 1000L
        server.enqueue(MockResponse().setResponseCode(500))
        assertThat(f.refresh()).isFalse()
        assertThat(server.requestCount).isEqualTo(3)
        // That failure stamped nothing: the next call asks again.
        now += 2 * 60 * 60 * 1000L + 1
        server.enqueue(MockResponse().setResponseCode(500))
        assertThat(f.refresh(force = true)).isFalse()
        assertThat(server.requestCount).isEqualTo(4)
        assertThat(f.index.value!!.updatedAt).isEqualTo(1794355200L)
    }

    @Test fun `with no address or no key, nothing is asked`() = runBlocking<Unit> {
        val noUrl = fetcher().also { it.configUrl = null }
        assertThat(noUrl.refresh(force = true)).isFalse()
        val noKey = fetcher(key = "")
        assertThat(noKey.refresh(force = true)).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `the address moving keeps the floor`() = runBlocking<Unit> {
        serve("valid")
        val f = fetcher()
        assertThat(f.refresh()).isTrue()
        // The config names another address: it is asked at once (no six-hour wait, no ETag), and an older index there is refused.
        f.configUrl = url("/elsewhere/index.json")
        serve("valid-older")
        assertThat(f.refresh()).isFalse()
        assertThat(server.takeRequest().path).isEqualTo("/registry/index.json")
        server.takeRequest()
        val moved = server.takeRequest()
        assertThat(moved.path).isEqualTo("/elsewhere/index.json")
        assertThat(moved.getHeader("If-None-Match")).isNull()
        assertThat(f.index.value!!.updatedAt).isEqualTo(1794355200L)
    }

    // ------------------------------------------------------------------------------------------ the staging source (debug builds only)

    private val staging = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val stagingKey = Base64.getEncoder().encodeToString(staging.public.encoded)

    private fun signStaging(bytes: ByteArray): String = Base64.getEncoder().encodeToString(
        Signature.getInstance("SHA256withECDSA").apply { initSign(staging.private); update(bytes) }.sign(),
    )

    private val stagingIndex = """{"type":"stash-addon-index","v":1,"updated_at":7,"addons":[],"blocked":[{"origin":"https://bad.example","reason":"harmful"}]}""".toByteArray()

    @Test fun `a release build ignores a staging source, stored or asked for`() = runBlocking<Unit> {
        val release = fetcher(debuggable = false)
        assertThat(release.setDebugSource(url("/staging/index.json"), stagingKey)).isFalse()
        // Even one a debug build stored in the same file is ignored.
        assertThat(fetcher(debuggable = true).setDebugSource(url("/staging/index.json"), stagingKey)).isTrue()
        serve("valid-launch")
        assertThat(release.refresh(force = true)).isTrue()
        assertThat(server.takeRequest().path).isEqualTo("/registry/index.json")
    }

    @Test fun `a debug build reads the staging index with the staging key, and clearing it forgets what it applied`() = runBlocking<Unit> {
        val debug = fetcher(debuggable = true)
        assertThat(debug.setDebugSource(url("/staging/index.json"), stagingKey)).isTrue()
        server.enqueue(MockResponse().setBody(Buffer().write(stagingIndex)))
        server.enqueue(MockResponse().setBody(signStaging(stagingIndex)))
        assertThat(debug.refresh(force = true)).isTrue()
        assertThat(server.takeRequest().path).isEqualTo("/staging/index.json")
        assertThat(debug.index.value!!.blocked.single().origin).isEqualTo("https://bad.example")

        // Back to normal: the staging blocks are gone, and so is their floor.
        assertThat(debug.setDebugSource(null, null)).isTrue()
        assertThat(debug.index.value).isNull()
        val cold = fetcher(debuggable = true)
        cold.loadCached()
        assertThat(cold.index.value).isNull()
        // A staging source needs its key, and a plain-http address only on this phone.
        assertThat(debug.setDebugSource("http://staging.example/index.json", stagingKey)).isFalse()
        assertThat(debug.setDebugSource(url("/staging/index.json"), null)).isFalse()
    }
}
