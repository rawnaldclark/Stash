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
import java.util.concurrent.ConcurrentHashMap

class AddonRegistryTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HttpAddonClient
    private val store = MapStore()

    @Before fun setUp() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        client = HttpAddonClient(OkHttpClient(), OpenAddonRateGate, NoFirstPartyAddons, UnsignedAddons)
        client.options = AddonValidator.Options(allowLocalHttp = true)
    }

    @After fun tearDown() = server.shutdown()

    private class Defaults(list: List<DefaultAddonEntry>) : DefaultAddons {
        val flow = MutableStateFlow(list)
        override val entries: StateFlow<List<DefaultAddonEntry>> = flow
    }

    private class Users(list: List<UserAddonEntry>) : UserAddons {
        override val entries: StateFlow<List<UserAddonEntry>> = MutableStateFlow(list)
    }

    class MapStore : AddonManifestStore {
        val rows = ConcurrentHashMap<String, String>()
        override suspend fun read(key: String) = rows[key]
        override suspend fun write(key: String, json: String) { rows[key] = json }
        override suspend fun retain(keys: Set<String>) { rows.keys.retainAll(keys) }
    }

    private fun url(path: String) = "http://127.0.0.1:${server.port}/$path/manifest.json"

    private fun manifestJson(id: String, kind: String = "server", extra: String = "", resources: String = "\"lyrics\"") =
        """{"stash":1,"id":"$id","name":"N","version":"1","kind":"$kind","resources":[$resources]$extra}"""

    private fun registry(defaults: DefaultAddons = NoDefaultAddons, users: UserAddons = NoUserAddons) =
        InTreeAddonRegistry(defaults, users, client, store)

    @Test fun `no entries - nothing on, nothing asked`() = runBlocking<Unit> {
        val registry = registry()
        registry.refresh()
        assertThat(registry.addons.value).isEmpty()
        assertThat(registry.offering(Resource.STREAM)).isEmpty()
        assertThat(registry.offering(Resource.LYRICS)).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `defaults are read from their manifests, in order`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(manifestJson("one")))
        server.enqueue(MockResponse().setBody(manifestJson("two")))
        val registry = registry(
            Defaults(listOf(
                DefaultAddonEntry(url("a")),
                DefaultAddonEntry(url("off"), enabled = false),
                DefaultAddonEntry(url("b")),
            )),
        )
        registry.refresh()
        assertThat(registry.addons.value.map { it.id }).containsExactly("one", "two").inOrder()
        assertThat(registry.offering(Resource.LYRICS)).hasSize(2)
        assertThat(registry.offering(Resource.STREAM)).isEmpty()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `an add-on whose manifest fails keeps its last good one`() = runBlocking<Unit> {
        val registry = registry(Defaults(listOf(DefaultAddonEntry(url("a")))))
        server.enqueue(MockResponse().setBody(manifestJson("one")))
        registry.refresh()
        server.enqueue(MockResponse().setResponseCode(503))
        registry.refresh()
        assertThat(registry.addons.value.map { it.id }).containsExactly("one")
    }

    @Test fun `an offline cold start keeps the kept manifest, asking nothing`() = runBlocking<Unit> {
        val defaults = Defaults(listOf(DefaultAddonEntry(url("v1/tok"), firstParty = true)))
        server.enqueue(MockResponse().setBody(manifestJson("tidal", resources = "\"search\",\"stream\"", extra = ""","stream":{"cors":false}""")))
        registry(defaults).refresh()
        // The kept copy is keyed by a hash: the URL (and its token) is never stored.
        assertThat(store.rows.keys).containsExactly(InTreeAddonRegistry.keyOf(url("v1/tok")))
        assertThat(store.rows.values.single()).doesNotContain("tok")

        // A new process, offline: the kept manifest is published before any network call.
        server.shutdown()
        val restarted = registry(defaults)
        restarted.loadPersisted()
        val tidal = restarted.firstParty(NamedRungAddons.TIDAL)
        assertThat(tidal).isNotNull()
        assertThat(tidal!!.firstParty).isTrue()
        // And a refresh that cannot reach the add-on keeps it too.
        restarted.refresh()
        assertThat(restarted.firstParty(NamedRungAddons.TIDAL)).isNotNull()
    }

    @Test fun `a kept manifest that no longer passes the checks is not used`() = runBlocking<Unit> {
        store.rows[InTreeAddonRegistry.keyOf(url("a"))] = """{"stash":1,"id":"BAD ID","name":"N","version":"1","kind":"server","resources":[]}"""
        val registry = registry(Defaults(listOf(DefaultAddonEntry(url("a")))))
        registry.loadPersisted()
        assertThat(registry.addons.value).isEmpty()
    }

    @Test fun `the Tidal add-on is the named rung's, never a generic one`() = runBlocking<Unit> {
        val streamy = """"search","resolve","stream","lyrics""""
        server.enqueue(MockResponse().setBody(manifestJson("tidal", resources = streamy, extra = ""","stream":{"cors":false}""")))
        server.enqueue(MockResponse().setBody(manifestJson("other", resources = streamy, extra = ""","stream":{"cors":false}""")))
        val registry = registry(Defaults(listOf(DefaultAddonEntry(url("t"), firstParty = true), DefaultAddonEntry(url("o")))))
        registry.refresh()
        assertThat(registry.addons.value.map { it.id }).containsExactly("tidal", "other").inOrder()
        assertThat(registry.generic().map { it.id }).containsExactly("other")
        assertThat(registry.offering(Resource.STREAM).map { it.id }).containsExactly("other")
        assertThat(registry.firstParty("tidal")!!.id).isEqualTo("tidal")
    }

    @Test fun `a third-party add-on named tidal is generic, and a first-party default wins its id`() = runBlocking<Unit> {
        val body = manifestJson("tidal", resources = "\"search\",\"stream\"", extra = ""","stream":{"cors":false}""")
        server.enqueue(MockResponse().setBody(body)) // the user's
        server.enqueue(MockResponse().setBody(body)) // the default
        val both = registry(Defaults(listOf(DefaultAddonEntry(url("t"), firstParty = true))), Users(listOf(UserAddonEntry(url("u")))))
        both.refresh()
        assertThat(both.addons.value).hasSize(1)
        assertThat(both.addons.value.single().firstParty).isTrue()

        server.enqueue(MockResponse().setBody(body))
        val userOnly = registry(users = Users(listOf(UserAddonEntry(url("u")))))
        userOnly.refresh()
        assertThat(userOnly.generic().map { it.id }).containsExactly("tidal")
        assertThat(userOnly.firstParty("tidal")).isNull()
    }

    @Test fun `user add-ons come first, with their token`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(manifestJson("mine", extra = ""","auth":{"token":"header"}""")))
        server.enqueue(MockResponse().setBody(manifestJson("default")))
        val registry = registry(Defaults(listOf(DefaultAddonEntry(url("d")))), Users(listOf(UserAddonEntry(url("m"), token = "s3cret"))))
        registry.refresh()
        assertThat(registry.addons.value.map { it.id }).containsExactly("mine", "default").inOrder()
        assertThat(registry.addons.value.first().userAdded).isTrue()
        assertThat(registry.addons.value.first().token).isEqualTo("s3cret")
    }

    @Test fun `the user's own Tidal server is never loaded as an add-on`() = runBlocking<Unit> {
        // Add-ons phase 4 §2.10: it serves no manifest, and the Tidal rung plays it.
        val own = UserAddonEntry("http://127.0.0.1:${server.port}/own", token = "own-token", kind = UserAddonKind.OWN_TIDAL_SERVER)
        val registry = registry(users = Users(listOf(own)))
        registry.refresh()
        assertThat(registry.addons.value).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `an applied config re-reads at most every 10 minutes when nothing changed`() = runBlocking<Unit> {
        var now = 1_000_000L
        val registry = registry(Defaults(listOf(DefaultAddonEntry(url("a")))))
        registry.nowMs = { now }
        repeat(3) { server.enqueue(MockResponse().setBody(manifestJson("one"))) }
        registry.refresh(force = false)
        now += 60_000
        registry.refresh(force = false)
        assertThat(server.requestCount).isEqualTo(1)
        now += InTreeAddonRegistry.MIN_REFRESH_MS
        registry.refresh(force = false)
        assertThat(server.requestCount).isEqualTo(2)
        registry.refresh(force = true)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun `only server add-ons are loaded`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(
            manifestJson("frame-one", kind = "frame", extra = ""","frame":"https://frame.example/""""),
        ))
        val registry = registry(Defaults(listOf(DefaultAddonEntry(url("f")))))
        registry.refresh()
        assertThat(registry.addons.value).isEmpty()
    }

    @Test fun `a v0 manifest is read as a v1 server add-on`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(
            """{"stash":0,"id":"jiosaavn","name":"JioSaavn","version":"0.3.0","runtime":"remote","resources":["search","resolve","stream"],"stream":{"cors":true}}""",
        ))
        val registry = registry(Defaults(listOf(DefaultAddonEntry(url("j")))))
        registry.refresh()
        val addon = registry.addons.value.single()
        assertThat(addon.manifest.stash).isEqualTo(0)
        assertThat(addon.manifest.stream!!.knownManifests.map { it.wire }).containsExactly("none")
    }

    @Test fun `entries never print their URL or token`() {
        assertThat(DefaultAddonEntry("https://relay.example/s3cret/manifest.json").toString()).doesNotContain("s3cret")
        val user = UserAddonEntry("https://mine.example/s3cret/manifest.json", token = "t0ken").toString()
        assertThat(user).doesNotContain("s3cret")
        assertThat(user).doesNotContain("t0ken")
    }
}
