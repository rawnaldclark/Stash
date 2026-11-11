package com.stash.core.addons

import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.Resource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class AddonRegistryTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HttpAddonClient

    @Before fun setUp() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        client = HttpAddonClient(OkHttpClient(), OpenAddonRateGate, NoFirstPartyAddons, UnsignedAddons)
        client.options = AddonValidator.Options(allowLocalHttp = true)
    }

    @After fun tearDown() = server.shutdown()

    private class Defaults(list: List<DefaultAddonEntry>) : DefaultAddons {
        override val entries: StateFlow<List<DefaultAddonEntry>> = MutableStateFlow(list)
    }

    private fun manifestJson(id: String, kind: String = "server", extra: String = "") =
        """{"stash":1,"id":"$id","name":"N","version":"1","kind":"$kind","resources":["lyrics"]$extra}"""

    @Test fun `phase 1 - no defaults, nothing on, nothing asked`() = runBlocking<Unit> {
        val registry = InTreeAddonRegistry(NoDefaultAddons(), client)
        registry.refresh()
        assertThat(registry.addons.value).isEmpty()
        assertThat(registry.offering(Resource.STREAM)).isEmpty()
        assertThat(registry.offering(Resource.LYRICS)).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `defaults are read from their manifests, in order`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(manifestJson("one")))
        server.enqueue(MockResponse().setBody(manifestJson("two")))
        val registry = InTreeAddonRegistry(
            Defaults(listOf(
                DefaultAddonEntry("http://127.0.0.1:${server.port}/a/manifest.json"),
                DefaultAddonEntry("http://127.0.0.1:${server.port}/off/manifest.json", enabled = false),
                DefaultAddonEntry("http://127.0.0.1:${server.port}/b/manifest.json"),
            )),
            client,
        )
        registry.refresh()
        assertThat(registry.addons.value.map { it.id }).containsExactly("one", "two").inOrder()
        assertThat(registry.offering(Resource.LYRICS)).hasSize(2)
        assertThat(registry.offering(Resource.STREAM)).isEmpty()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `an add-on whose manifest fails keeps its last good one`() = runBlocking<Unit> {
        val registry = InTreeAddonRegistry(Defaults(listOf(DefaultAddonEntry("http://127.0.0.1:${server.port}/a/manifest.json"))), client)
        server.enqueue(MockResponse().setBody(manifestJson("one")))
        registry.refresh()
        server.enqueue(MockResponse().setResponseCode(503))
        registry.refresh()
        assertThat(registry.addons.value.map { it.id }).containsExactly("one")
    }

    @Test fun `only server add-ons are loaded`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(
            manifestJson("frame-one", kind = "frame", extra = ""","frame":"https://frame.example/""""),
        ))
        val registry = InTreeAddonRegistry(Defaults(listOf(DefaultAddonEntry("http://127.0.0.1:${server.port}/f/manifest.json"))), client)
        registry.refresh()
        assertThat(registry.addons.value).isEmpty()
    }

    @Test fun `a default entry never prints its URL`() {
        assertThat(DefaultAddonEntry("https://relay.example/s3cret/manifest.json").toString()).doesNotContain("s3cret")
    }
}
