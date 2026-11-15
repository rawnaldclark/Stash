package com.stash.core.addons

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/**
 * review-881 J1 / review-882 J1: a manifest is valid with keys the host doesn't know (SPEC.md 1), so
 * a valid one can carry a value nested tens of thousands of objects deep. kotlinx.serialization
 * parses that on the heap, and writing the tree back out (`JsonElement.toString()`) recurses once
 * per level: a `StackOverflowError`, an `Error`, which crashed "Add from a link" and stopped the
 * registry's refresh for every add-on.
 *
 * The client keeps the answer's own text, caps a manifest at 64 KiB, and reads anything thrown
 * while an answer is parsed or checked as an invalid answer. Each test here ends in a
 * `StackOverflowError` on the code before that.
 */
class HostileManifestTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HttpAddonClient

    @Before fun setUp() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        client = HttpAddonClient(OkHttpClient(), OpenAddonRateGate, NoFirstPartyAddons, UnsignedAddons)
        client.options = AddonValidator.Options(allowLocalHttp = true)
    }

    @After fun tearDown() = server.shutdown()

    private fun base(path: String) = "http://127.0.0.1:${server.port}/$path/"

    /** A valid v1 manifest whose unknown key `x` holds [depth] objects, one inside the other. */
    private fun nested(depth: Int, id: String = "deep"): String = buildString(depth * 6 + 200) {
        append("""{"stash":1,"id":"$id","name":"Deep","version":"1","kind":"server","resources":["lyrics"],"x":""")
        repeat(depth) { append("""{"a":""") }
        append("1")
        repeat(depth) { append('}') }
        append('}')
    }

    @Test fun `a manifest nested 100,000 objects deep in an unknown key is an invalid answer, and nothing is thrown`() = runBlocking<Unit> {
        val body = nested(100_000)
        assertThat(body.length.toLong()).isGreaterThan(HttpAddonClient.MANIFEST_BYTES)
        server.enqueue(MockResponse().setBody(body))
        server.enqueue(MockResponse().setBody(body))
        // Both entry points: the Add sheet's read and the registry's.
        assertThat((client.manifest(base("a")) as AddonResult.Failed).failure).isInstanceOf(AddonFailure.InvalidResponse::class.java)
        assertThat((client.manifestWithJson(base("a")) as AddonResult.Failed).failure).isInstanceOf(AddonFailure.InvalidResponse::class.java)
    }

    @Test fun `a deep manifest under the cap is read, and kept as the text it arrived as`() = runBlocking<Unit> {
        // 8,000 levels is about 48 KiB: under the cap, and far more than toString() can walk.
        val body = nested(8_000)
        assertThat(body.length.toLong()).isLessThan(HttpAddonClient.MANIFEST_BYTES)
        server.enqueue(MockResponse().setBody(body))
        val (manifest, json) = (client.manifestWithJson(base("a")) as AddonResult.Ok).value
        assertThat(manifest.manifest.id).isEqualTo("deep")
        assertThat(json).isEqualTo(body)
    }

    @Test fun `a manifest of exactly 64 KiB is read and one byte more is refused`() = runBlocking<Unit> {
        val head = """{"stash":1,"id":"big","name":"Big","version":"1","kind":"server","resources":["lyrics"],"pad":""""
        fun sized(bytes: Long) = head + "p".repeat((bytes - head.length - 2).toInt()) + "\"}"
        server.enqueue(MockResponse().setBody(sized(HttpAddonClient.MANIFEST_BYTES)))
        server.enqueue(MockResponse().setBody(sized(HttpAddonClient.MANIFEST_BYTES + 1)))
        assertThat(client.manifest(base("a"))).isInstanceOf(AddonResult.Ok::class.java)
        val refused = (client.manifest(base("a")) as AddonResult.Failed).failure as AddonFailure.InvalidResponse
        assertThat(refused.reason).isEqualTo("manifest over 64 KiB")
    }

    @Test fun `a registry holding a hostile add-on and a good one still publishes the good one`() = runBlocking<Unit> {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/hostile/manifest.json" -> MockResponse().setBody(nested(100_000, id = "hostile"))
                "/deep/manifest.json" -> MockResponse().setBody(nested(8_000, id = "deep"))
                "/good/manifest.json" ->
                    MockResponse().setBody("""{"stash":1,"id":"good","name":"Good","version":"1","kind":"server","resources":["lyrics"]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val users = object : UserAddons {
            override val entries: StateFlow<List<UserAddonEntry>> = MutableStateFlow(
                listOf(
                    UserAddonEntry(base("hostile") + "manifest.json", consent = setOf("PLAYS", "SAVES")),
                    UserAddonEntry(base("deep") + "manifest.json", consent = setOf("PLAYS", "SAVES")),
                    UserAddonEntry(base("good") + "manifest.json", consent = setOf("PLAYS", "SAVES")),
                ),
            )
        }
        val store = AddonRegistryTest.MapStore()
        val registry = InTreeAddonRegistry(NoDefaultAddons, users, client, store)
        registry.refresh()
        assertThat(registry.addons.value.map { it.id }).containsExactly("deep", "good").inOrder()

        // The kept copy of the deep one is read back at the next cold start (no network) without a throw either.
        val asked = server.requestCount
        val restarted = InTreeAddonRegistry(NoDefaultAddons, users, client, store)
        restarted.loadPersisted()
        assertThat(restarted.addons.value.map { it.id }).containsExactly("deep", "good").inOrder()
        assertThat(server.requestCount).isEqualTo(asked)
    }
}
