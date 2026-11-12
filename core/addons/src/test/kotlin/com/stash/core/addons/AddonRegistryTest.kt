package com.stash.core.addons

import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.Resource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AddonRegistryTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HttpAddonClient
    private val store = MapStore()

    /** What each path answers (`a` for `/a/manifest.json`); a path with no answer is a 404. Add-ons are read side by side, so answers go by path, never by order. */
    private val answers = ConcurrentHashMap<String, () -> MockResponse>()

    /** The paths asked, in the order they arrived. */
    private val asked = CopyOnWriteArrayList<String>()

    @Before fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty().removePrefix("/").removeSuffix("/manifest.json")
                    asked += path
                    return answers[path]?.invoke() ?: MockResponse().setResponseCode(404)
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        client = HttpAddonClient(OkHttpClient(), OpenAddonRateGate, NoFirstPartyAddons, UnsignedAddons)
        client.options = AddonValidator.Options(allowLocalHttp = true)
    }

    @After fun tearDown() = server.shutdown()

    private class Defaults(list: List<DefaultAddonEntry>) : DefaultAddons {
        val flow = MutableStateFlow(list)
        override val entries: StateFlow<List<DefaultAddonEntry>> = flow
    }

    private class Users(list: List<UserAddonEntry>) : UserAddons {
        val flow = MutableStateFlow(list)
        override val entries: StateFlow<List<UserAddonEntry>> = flow
    }

    class MapStore : AddonManifestStore {
        val rows = ConcurrentHashMap<String, String>()
        override suspend fun read(key: String) = rows[key]
        override suspend fun write(key: String, json: String) { rows[key] = json }
        override suspend fun retain(keys: Set<String>) { rows.keys.retainAll(keys) }
        override suspend fun forget(keys: Set<String>) { rows.keys.removeAll(keys) }
    }

    /** A catalog a test sets: what is blocked (by origin, or origin and id), and a tick for "the index changed". */
    private class Catalog : AddonCatalog {
        @Volatile var blocked: (base: String, id: String?) -> BlockReason? = { _, _ -> null }
        val ticks = MutableSharedFlow<Any?>(extraBufferCapacity = 8)
        override fun trustOf(base: String, id: String?) = if (blocked(base, id) != null) Trust.BLOCKED else Trust.UNOFFICIAL
        override fun blockReason(base: String, id: String?) = blocked(base, id)
        override val official: StateFlow<List<OfficialEntry>> = MutableStateFlow(emptyList())
        override val changes: Flow<Any?> = ticks
    }

    private class Switches(off: Set<String> = emptySet()) : AddonSwitches {
        val flow = MutableStateFlow(off)
        override val defaultsOff: Flow<Set<String>> = flow
    }

    private fun url(path: String) = "http://127.0.0.1:${server.port}/$path/manifest.json"

    private fun manifestJson(id: String, kind: String = "server", extra: String = "", resources: String = "\"lyrics\"") =
        """{"stash":1,"id":"$id","name":"N","version":"1","kind":"$kind","resources":[$resources]$extra}"""

    private fun serve(path: String, body: String) { answers[path] = { MockResponse().setBody(body) } }

    private fun registry(
        defaults: DefaultAddons = NoDefaultAddons,
        users: UserAddons = NoUserAddons,
        catalog: AddonCatalog = NoAddonCatalog,
        switches: AddonSwitches = AllDefaultsOn,
    ) = InTreeAddonRegistry(defaults, users, client, store, catalog, switches)

    @Test fun `no entries - nothing on, nothing asked`() = runBlocking<Unit> {
        val registry = registry()
        registry.refresh()
        assertThat(registry.addons.value).isEmpty()
        assertThat(registry.offering(Resource.STREAM)).isEmpty()
        assertThat(registry.offering(Resource.LYRICS)).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `defaults are read from their manifests, in order`() = runBlocking<Unit> {
        serve("a", manifestJson("one"))
        serve("b", manifestJson("two"))
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
        serve("a", manifestJson("one"))
        registry.refresh()
        answers["a"] = { MockResponse().setResponseCode(503) }
        registry.refresh()
        assertThat(registry.addons.value.map { it.id }).containsExactly("one")
    }

    @Test fun `an offline cold start keeps the kept manifest, asking nothing`() = runBlocking<Unit> {
        val defaults = Defaults(listOf(DefaultAddonEntry(url("v1/tok"), firstParty = true)))
        serve("v1/tok", manifestJson("tidal", resources = "\"search\",\"stream\"", extra = ""","stream":{"cors":false}"""))
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
        serve("t", manifestJson("tidal", resources = streamy, extra = ""","stream":{"cors":false}"""))
        serve("o", manifestJson("other", resources = streamy, extra = ""","stream":{"cors":false}"""))
        val registry = registry(Defaults(listOf(DefaultAddonEntry(url("t"), firstParty = true), DefaultAddonEntry(url("o")))))
        registry.refresh()
        assertThat(registry.addons.value.map { it.id }).containsExactly("tidal", "other").inOrder()
        assertThat(registry.generic().map { it.id }).containsExactly("other")
        assertThat(registry.offering(Resource.STREAM).map { it.id }).containsExactly("other")
        assertThat(registry.firstParty("tidal")!!.id).isEqualTo("tidal")
    }

    @Test fun `a third-party add-on named tidal is generic, and a first-party default wins its id`() = runBlocking<Unit> {
        val body = manifestJson("tidal", resources = "\"search\",\"stream\"", extra = ""","stream":{"cors":false}""")
        serve("u", body) // the user's
        serve("t", body) // the default
        val both = registry(Defaults(listOf(DefaultAddonEntry(url("t"), firstParty = true))), Users(listOf(UserAddonEntry(url("u")))))
        both.refresh()
        assertThat(both.addons.value).hasSize(1)
        assertThat(both.addons.value.single().firstParty).isTrue()
        // The user's copy says why it is not used, instead of "not reachable".
        assertThat(both.entries.value.single { it.userAdded }.hold).isEqualTo(AddonHold.REPLACED)

        val userOnly = registry(users = Users(listOf(UserAddonEntry(url("u")))))
        userOnly.refresh()
        assertThat(userOnly.generic().map { it.id }).containsExactly("tidal")
        assertThat(userOnly.firstParty("tidal")).isNull()
    }

    @Test fun `user add-ons come first, with their token`() = runBlocking<Unit> {
        serve("m", manifestJson("mine", extra = ""","auth":{"token":"header"}"""))
        serve("d", manifestJson("default"))
        val registry = registry(Defaults(listOf(DefaultAddonEntry(url("d")))), Users(listOf(UserAddonEntry(url("m"), token = "s3cret"))))
        registry.refresh()
        assertThat(registry.addons.value.map { it.id }).containsExactly("mine", "default").inOrder()
        assertThat(registry.addons.value.first().userAdded).isTrue()
        assertThat(registry.addons.value.first().token).isEqualTo("s3cret")
        // Where each stands with Stash: the user's is not Stash's, a default is.
        assertThat(registry.addons.value.map { it.trust }).containsExactly(Trust.UNOFFICIAL, Trust.OFFICIAL).inOrder()
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
        serve("a", manifestJson("one"))
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
        serve("f", manifestJson("frame-one", kind = "frame", extra = ""","frame":"https://frame.example/""""))
        val registry = registry(Defaults(listOf(DefaultAddonEntry(url("f")))))
        registry.refresh()
        assertThat(registry.addons.value).isEmpty()
    }

    @Test fun `a v0 manifest is read as a v1 server add-on`() = runBlocking<Unit> {
        serve("j", """{"stash":0,"id":"jiosaavn","name":"JioSaavn","version":"0.3.0","runtime":"remote","resources":["search","resolve","stream"],"stream":{"cors":true}}""")
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

    // ------------------------------------------------------------------------------------------ each entry on its own (reviews J1, M2, M12)

    @Test fun `a failed read is not a read - the entry is asked again at the next tick`() = runBlocking<Unit> {
        var now = 1_000_000L
        val registry = registry(Defaults(listOf(DefaultAddonEntry(url("a")))))
        registry.nowMs = { now }
        answers["a"] = { MockResponse().setResponseCode(503) }
        registry.refresh(force = false)
        assertThat(registry.addons.value).isEmpty()
        // A minute later, well inside the 10 minutes: it never answered, so it is asked again.
        now += 60_000
        serve("a", manifestJson("one"))
        registry.refresh(force = false)
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(registry.addons.value.map { it.id }).containsExactly("one")
    }

    @Test fun `one poisoned entry never costs the others`() = runBlocking<Unit> {
        // A manifest that passes every check and then overflows the stack when it is written out
        // again (an unknown key nested 100,000 deep), or is refused as invalid: either way an
        // `Error` or a failure of one entry must leave the good one published.
        val deep = "{\"a\":".repeat(100_000) + "1" + "}".repeat(100_000)
        serve("bad", """{"stash":1,"id":"bad","name":"N","version":"1","kind":"server","resources":["lyrics"],"x":$deep}""")
        serve("good", manifestJson("good"))
        val registry = registry(users = Users(listOf(UserAddonEntry(url("bad")), UserAddonEntry(url("good")))))
        registry.refresh()
        assertThat(registry.addons.value.map { it.id }).contains("good")
        // And the next pass still runs: the registry is not frozen.
        serve("more", manifestJson("more"))
        val again = registry(users = Users(listOf(UserAddonEntry(url("bad")), UserAddonEntry(url("good")), UserAddonEntry(url("more")))))
        again.refresh()
        assertThat(again.addons.value.map { it.id }).containsAtLeast("good", "more")
    }

    @Test fun `whatever one entry throws, an Error too, costs that entry only`() = runBlocking<Unit> {
        // The client turns a hostile manifest into a failed read by itself now, so that case no longer
        // reaches the registry's own guard. This does: an `Error` thrown from one entry's own work (here
        // its kept manifest, at the read back and at the write) on every pass.
        serve("bad", manifestJson("bad"))
        serve("good", manifestJson("good"))
        val badKey = InTreeAddonRegistry.keyOf(url("bad"))
        val rows = ConcurrentHashMap<String, String>()
        val poisoned = object : AddonManifestStore {
            override suspend fun read(key: String): String? = if (key == badKey) throw StackOverflowError() else rows[key]
            override suspend fun write(key: String, json: String) {
                if (key == badKey) throw StackOverflowError()
                rows[key] = json
            }
            override suspend fun retain(keys: Set<String>) { rows.keys.retainAll(keys) }
        }
        val registry = InTreeAddonRegistry(
            NoDefaultAddons, Users(listOf(UserAddonEntry(url("bad")), UserAddonEntry(url("good")))), client, poisoned,
        )
        registry.refresh()
        assertThat(registry.addons.value.map { it.id }).containsExactly("good")
        assertThat(registry.entries.value.map { it.hold }).containsExactly(AddonHold.NOT_LOADED, null).inOrder()
        // The pass ran to its end: the good one's manifest was kept.
        assertThat(rows.keys).containsExactly(InTreeAddonRegistry.keyOf(url("good")))
        // And the next pass runs too, from what the last one knew.
        registry.refresh()
        assertThat(registry.addons.value.map { it.id }).containsExactly("good")
    }

    @Test fun `an entry that didn't answer is asked again by itself after a wait, and not for ever`() = runBlocking<Unit> {
        answers["a"] = { MockResponse().setResponseCode(503) }
        val registry = registry(Defaults(listOf(DefaultAddonEntry(url("a")))))
        // The app waits 15 s, 30 s, 1, 2, 5 and 10 minutes; the same code, on a test's clock.
        registry.retryDelaysMs = List(40) { 100L }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            // Nothing else starts a pass here: the lists don't change and no config is applied.
            registry.start(scope, emptyFlow())
            withTimeout(10_000) { while (asked.size < 3) delay(20) }
            assertThat(registry.addons.value).isEmpty()
            serve("a", manifestJson("one"))
            withTimeout(10_000) { registry.addons.first { it.isNotEmpty() } }
            assertThat(registry.addons.value.map { it.id }).containsExactly("one")
            // It answered: the retries end.
            delay(300)
            val settled = asked.size
            delay(500)
            assertThat(asked.size).isEqualTo(settled)
        } finally {
            scope.cancel()
        }

        // After the last wait it is left alone, until something else starts a pass.
        asked.clear()
        answers["b"] = { MockResponse().setResponseCode(503) }
        val gaveUp = registry(Defaults(listOf(DefaultAddonEntry(url("b")))))
        gaveUp.retryDelaysMs = listOf(50L, 50L)
        val second = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            gaveUp.start(second, emptyFlow())
            withTimeout(10_000) { while (asked.size < 3) delay(20) }
            delay(600)
            assertThat(asked).containsExactly("b", "b", "b")
        } finally {
            second.cancel()
        }
    }

    @Test fun `a cold start reads each add-on's manifest once, however the lists and the first config arrive`() = runBlocking<Unit> {
        // Seen on a phone: two GETs of each manifest within the same second. The user's list, the
        // signed config's defaults and the first applied config each start a pass, and a pass for a
        // changed list read everything again.
        serve("m", manifestJson("mine"))
        serve("d", manifestJson("builtin"))
        val users = Users(emptyList())
        val defaults = Defaults(emptyList())
        val registry = registry(defaults, users)
        // Like the config fetcher's `refreshedAt`: it has a value from the start, and a new one for each applied config.
        val applied = MutableStateFlow(0L)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            registry.start(scope, applied)
            users.flow.value = listOf(UserAddonEntry(url("m"), id = "mine"))
            withTimeout(5_000) { registry.addons.first { list -> list.any { it.id == "mine" } } }
            defaults.flow.value = listOf(DefaultAddonEntry(url("d")))
            withTimeout(5_000) { registry.addons.first { it.size == 2 } }
            applied.value = 1L
            // And a change that asks nothing of anyone: the user switches one off and on again.
            users.flow.value = listOf(UserAddonEntry(url("m"), id = "mine", enabled = false))
            withTimeout(5_000) { registry.addons.first { it.size == 1 } }
            users.flow.value = listOf(UserAddonEntry(url("m"), id = "mine"))
            withTimeout(5_000) { registry.addons.first { it.size == 2 } }
            delay(400)
            assertThat(asked.count { it == "m" }).isEqualTo(1)
            assertThat(asked.count { it == "d" }).isEqualTo(1)

            // A link replaced is an add-on that has never answered: it is read, and only it.
            serve("m2", manifestJson("mine"))
            users.flow.value = listOf(UserAddonEntry(url("m2"), id = "mine"))
            withTimeout(5_000) { while ("m2" !in asked) delay(20) }
            delay(300)
            assertThat(asked).containsExactly("m", "d", "m2")
        } finally {
            scope.cancel()
        }
    }

    @Test fun `a block that lands between a pass judging an add-on and publishing it is not published away`() = runBlocking<Unit> {
        // review-885 M7. A new index can land at any moment. Here it lands right after the pass has
        // judged the add-on (the first time the catalog is asked about it) and before the pass publishes.
        serve("m", manifestJson("mine"))
        val users = Users(listOf(UserAddonEntry(url("m"), id = "mine")))
        registry(users = users).refresh() // an earlier run kept its manifest
        asked.clear()
        val judged = AtomicInteger(0)
        val catalog = object : AddonCatalog {
            override fun trustOf(base: String, id: String?) = if (judged.getAndIncrement() == 0) Trust.UNOFFICIAL else Trust.BLOCKED
            override fun blockReason(base: String, id: String?) = BlockReason.HARMFUL
            override val official: StateFlow<List<OfficialEntry>> = MutableStateFlow(emptyList())
            override val changes: Flow<Any?> = emptyFlow()
        }
        val restarted = registry(users = users, catalog = catalog)
        restarted.refresh(force = false)
        // Never published from its kept manifest, and never asked.
        assertThat(restarted.addons.value).isEmpty()
        assertThat(asked).isEmpty()
        assertThat(restarted.entries.value.single().hold).isEqualTo(AddonHold.BLOCKED)
    }

    @Test fun `a user's switch and Remove take effect at once, while a pass is still reading`() = runBlocking<Unit> {
        // review-885 M5. A pass holds its lock for as long as its slowest manifest. The user's own
        // switch and Remove must not queue behind it: the add-on that hangs is the one they want gone.
        serve("hangs", manifestJson("hangs"))
        serve("fine", manifestJson("fine"))
        val users = Users(listOf(UserAddonEntry(url("hangs"), id = "hangs"), UserAddonEntry(url("fine"), id = "fine")))
        val registry = registry(users = users)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            registry.start(scope, emptyFlow())
            withTimeout(5_000) { registry.addons.first { it.size == 2 } }
            // Now it hangs, and a pass is reading it (Settings › Add-ons opened, a config was applied).
            answers["hangs"] = { MockResponse().setBody(manifestJson("hangs")).setHeadersDelay(4, TimeUnit.SECONDS) }
            val reading = scope.launch { registry.refresh(force = true) }
            withTimeout(5_000) { registry.reading.first { it } }
            // Switched off: the chains stop asking it now, not when the read gives up.
            users.flow.value = listOf(UserAddonEntry(url("hangs"), id = "hangs", enabled = false), UserAddonEntry(url("fine"), id = "fine"))
            withTimeout(1_500) { registry.addons.first { list -> list.map { it.id } == listOf("fine") } }
            assertThat(registry.entries.value.first().hold).isEqualTo(AddonHold.OFF)
            // Removed: its entry is gone at once too.
            users.flow.value = listOf(UserAddonEntry(url("fine"), id = "fine"))
            withTimeout(1_500) { registry.entries.first { it.size == 1 } }
            assertThat(registry.addons.value.map { it.id }).containsExactly("fine")
            reading.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test fun `a pass asked for from the screen does its reading on another thread`() {
        // Settings › Add-ons asks for a pass as it opens, from the main thread.
        serve("m", manifestJson("mine"))
        // The threads themselves, not their names: a test run renames a thread while a coroutine is on it.
        val threads = CopyOnWriteArrayList<Thread>()
        val watching = object : AddonManifestStore {
            override suspend fun read(key: String): String? {
                threads += Thread.currentThread()
                return null
            }
            override suspend fun write(key: String, json: String) {
                threads += Thread.currentThread()
            }
            override suspend fun retain(keys: Set<String>) = Unit
        }
        val registry = InTreeAddonRegistry(NoDefaultAddons, Users(listOf(UserAddonEntry(url("m")))), client, watching)
        var screen: Thread? = null
        val asking = Executors.newSingleThreadExecutor { r -> Thread(r, "the-screen").also { screen = it } }
        try {
            runBlocking(asking.asCoroutineDispatcher()) {
                assertThat(Thread.currentThread()).isSameInstanceAs(screen)
                registry.refresh(force = false)
            }
        } finally {
            asking.shutdownNow()
        }
        assertThat(screen).isNotNull()
        assertThat(registry.addons.value.map { it.id }).containsExactly("mine")
        // The kept copy was looked for and the fresh manifest kept: neither on the thread that asked.
        assertThat(threads).hasSize(2)
        assertThat(threads).doesNotContain(screen)
    }

    @Test fun `two add-ons whose manifests sit in one folder each keep their own`() = runBlocking<Unit> {
        // `…/x/a.json` and `…/x/b.json` (BitChord-style links) share a base: what a pass knew is kept by entry.
        answers["x/a.json"] = { MockResponse().setBody(manifestJson("one")) }
        answers["x/b.json"] = { MockResponse().setBody(manifestJson("two")) }
        fun link(file: String) = "http://127.0.0.1:${server.port}/x/$file"
        var now = 1_000_000L
        val registry = registry(users = Users(listOf(UserAddonEntry(link("a.json"), id = "one"), UserAddonEntry(link("b.json"), id = "two"))))
        registry.nowMs = { now }
        registry.refresh()
        assertThat(registry.addons.value.map { it.id }).containsExactly("one", "two").inOrder()
        // A pass that reads nothing (both answered a minute ago) starts from the last one, entry by entry.
        now += 60_000
        val before = server.requestCount
        registry.refresh(force = false)
        assertThat(server.requestCount).isEqualTo(before)
        assertThat(registry.addons.value.map { it.id }).containsExactly("one", "two").inOrder()
        assertThat(registry.entries.value.filter { it.hold != null }).isEmpty()
    }

    @Test fun `a slow add-on never delays another`() = runBlocking<Unit> {
        answers["slow"] = { MockResponse().setBody(manifestJson("slow")).setBodyDelay(3, TimeUnit.SECONDS) }
        serve("fast", manifestJson("fast"))
        val registry = registry(Defaults(listOf(DefaultAddonEntry(url("fast")))), Users(listOf(UserAddonEntry(url("slow")))))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            registry.start(scope, emptyFlow())
            // The fast one is on while the slow one is still being read.
            withTimeout(2_500) { registry.addons.first { list -> list.any { it.id == "fast" } } }
            assertThat(registry.addons.value.map { it.id }).containsExactly("fast")
            withTimeout(6_000) { registry.addons.first { it.size == 2 } }
        } finally {
            scope.cancel()
        }
    }

    @Test fun `start publishes the kept manifest of a default that arrives after it, before anything answers`() = runBlocking<Unit> {
        // The real start order: the registry starts, and the signed config's defaults come a moment later.
        serve("v1/tok", manifestJson("tidal", resources = "\"search\",\"stream\"", extra = ""","stream":{"cors":false}"""))
        registry(Defaults(listOf(DefaultAddonEntry(url("v1/tok"), firstParty = true)))).refresh()
        asked.clear()
        // The next run: the server takes its time over the manifest.
        answers["v1/tok"] = { MockResponse().setBody(manifestJson("tidal", resources = "\"search\",\"stream\"", extra = ""","stream":{"cors":false}""")).setBodyDelay(3, TimeUnit.SECONDS) }
        val defaults = Defaults(emptyList())
        val restarted = registry(defaults)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            restarted.start(scope, emptyFlow())
            delay(200)
            assertThat(restarted.addons.value).isEmpty()
            defaults.flow.value = listOf(DefaultAddonEntry(url("v1/tok"), firstParty = true))
            // On from the kept copy, long before the manifest read returns.
            withTimeout(2_000) { restarted.addons.first { it.isNotEmpty() } }
            assertThat(restarted.firstParty(NamedRungAddons.TIDAL)).isNotNull()
        } finally {
            scope.cancel()
        }
    }

    @Test fun `removing the last add-on leaves no kept manifest`() = runBlocking<Unit> {
        serve("m", manifestJson("mine"))
        val users = Users(listOf(UserAddonEntry(url("m"))))
        val registry = registry(users = users)
        registry.refresh()
        assertThat(store.rows).hasSize(1)
        users.flow.value = emptyList()
        registry.refresh()
        assertThat(registry.addons.value).isEmpty()
        assertThat(store.rows).isEmpty()
    }

    // ------------------------------------------------------------------------------------------ the blocklist (plan §1.9)

    @Test fun `a blocked user add-on is not published and not even asked, and lifting the block brings it back`() = runBlocking<Unit> {
        serve("m", manifestJson("mine"))
        serve("t", manifestJson("tidal", resources = "\"search\",\"stream\"", extra = ""","stream":{"cors":false}"""))
        val catalog = Catalog()
        val registry = registry(
            Defaults(listOf(DefaultAddonEntry(url("t"), firstParty = true))),
            Users(listOf(UserAddonEntry(url("m"), id = "mine"))),
            catalog,
        )
        // Blocked by origin and id, as the signed index writes one. The add-on and the default share
        // an origin here: a block never touches a default or anything first-party.
        catalog.blocked = { _, id -> BlockReason.HARMFUL.takeIf { id == "mine" } }
        registry.refresh()
        assertThat(registry.addons.value.map { it.id }).containsExactly("tidal")
        assertThat(asked).containsExactly("t")
        val held = registry.entries.value.single { it.userAdded }
        assertThat(held.hold).isEqualTo(AddonHold.BLOCKED)
        assertThat(held.trust).isEqualTo(Trust.BLOCKED)
        assertThat(held.blockReason).isEqualTo(BlockReason.HARMFUL)

        // The block is lifted: it is read and comes back.
        catalog.blocked = { _, _ -> null }
        registry.refresh()
        assertThat(registry.addons.value.map { it.id }).containsExactly("mine", "tidal").inOrder()
    }

    @Test fun `a new index blocks an add-on that is on, at once and with nothing asked`() = runBlocking<Unit> {
        serve("m", manifestJson("mine"))
        val catalog = Catalog()
        val registry = registry(users = Users(listOf(UserAddonEntry(url("m"), id = "mine"))), catalog = catalog)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            registry.start(scope, emptyFlow())
            withTimeout(5_000) { registry.addons.first { it.isNotEmpty() } }
            val before = server.requestCount
            catalog.blocked = { _, _ -> BlockReason.BROKEN }
            catalog.ticks.emit(Unit)
            withTimeout(5_000) { registry.addons.first { it.isEmpty() } }
            assertThat(server.requestCount).isEqualTo(before)
            assertThat(registry.entries.value.single().blockReason).isEqualTo(BlockReason.BROKEN)
        } finally {
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------------------------------ switches, consent, the approved id

    @Test fun `a default switched off is not on, keeps its entry, and comes back with nothing asked`() = runBlocking<Unit> {
        serve("a", manifestJson("one"))
        serve("b", manifestJson("two"))
        val switches = Switches(off = setOf("two"))
        val registry = registry(Defaults(listOf(DefaultAddonEntry(url("a")), DefaultAddonEntry(url("b")))), switches = switches)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            registry.start(scope, emptyFlow())
            withTimeout(5_000) { registry.entries.first { list -> list.size == 2 && list.all { it.manifest != null } } }
            assertThat(registry.addons.value.map { it.id }).containsExactly("one")
            assertThat(registry.entries.value.single { it.manifest?.manifest?.id == "two" }.hold).isEqualTo(AddonHold.OFF)
            val before = server.requestCount
            switches.flow.value = emptySet()
            withTimeout(5_000) { registry.addons.first { it.size == 2 } }
            assertThat(server.requestCount).isEqualTo(before)
        } finally {
            scope.cancel()
        }
    }

    @Test fun `a user add-on switched off is not asked and keeps its entry`() = runBlocking<Unit> {
        serve("m", manifestJson("mine"))
        val users = Users(listOf(UserAddonEntry(url("m"), id = "mine")))
        val registry = registry(users = users)
        registry.refresh()
        users.flow.value = listOf(UserAddonEntry(url("m"), id = "mine", enabled = false))
        val before = server.requestCount
        registry.refresh()
        assertThat(registry.addons.value).isEmpty()
        assertThat(server.requestCount).isEqualTo(before)
        val entry = registry.entries.value.single()
        assertThat(entry.hold).isEqualTo(AddonHold.OFF)
        // Its name is still known for its row.
        assertThat(entry.manifest!!.manifest.id).isEqualTo("mine")
    }

    @Test fun `an add-on that wants to see more than was agreed waits, and one that sees no more does not`() = runBlocking<Unit> {
        // Agreed to: lyrics, for the songs played and saved. Now it also streams: it is asked about the next song up too.
        serve("m", manifestJson("mine", resources = "\"lyrics\",\"search\",\"stream\"", extra = ""","stream":{"cors":false}"""))
        val users = Users(listOf(UserAddonEntry(url("m"), id = "mine", consent = setOf(AddonSees.PLAYS.name, AddonSees.SAVES.name))))
        val registry = registry(users = users)
        registry.refresh()
        assertThat(registry.addons.value).isEmpty()
        assertThat(registry.entries.value.single().hold).isEqualTo(AddonHold.WANTS_MORE)

        // The user agrees to what it lists now: it is on.
        users.flow.value = listOf(
            UserAddonEntry(url("m"), id = "mine", consent = setOf(AddonSees.PLAYS.name, AddonSees.NEXT_UP.name, AddonSees.SAVES.name)),
        )
        registry.refresh()
        assertThat(registry.addons.value.map { it.id }).containsExactly("mine")
        // A new version alone never asks again.
        serve("m", manifestJson("mine", resources = "\"search\",\"stream\"", extra = ""","stream":{"cors":false}""").replace("\"version\":\"1\"", "\"version\":\"2\""))
        registry.refresh()
        assertThat(registry.addons.value.single().manifest.version).isEqualTo("2")
    }

    @Test fun `a link that starts serving another add-on is not used, and its manifest is not kept`() = runBlocking<Unit> {
        serve("m", manifestJson("mine"))
        val registry = registry(users = Users(listOf(UserAddonEntry(url("m"), id = "mine"))))
        registry.refresh()
        val kept = store.rows.values.single()
        serve("m", manifestJson("someone-else"))
        registry.refresh()
        assertThat(registry.addons.value).isEmpty()
        val entry = registry.entries.value.single()
        assertThat(entry.hold).isEqualTo(AddonHold.CHANGED)
        // What is shown stays the add-on the user approved.
        assertThat(entry.manifest!!.manifest.id).isEqualTo("mine")
        assertThat(store.rows.values.single()).isEqualTo(kept)
    }

    @Test fun `an add-on that turns BitChord-only is switched off, and its kept manifest goes`() = runBlocking<Unit> {
        val eclipse = """{"id":"relay","name":"Relay","version":"1","resources":["search","stream"]}"""
        serve("m", eclipse)
        val registry = registry(users = Users(listOf(UserAddonEntry(url("m"), id = "relay"))))
        registry.refresh()
        assertThat(registry.addons.value.map { it.id }).containsExactly("relay")
        serve("m", eclipse.replace("\"version\"", "\"bitchordAuth\":true,\"version\""))
        registry.refresh()
        assertThat(registry.addons.value).isEmpty()
        assertThat(registry.entries.value.single().hold).isEqualTo(AddonHold.ONLY_BITCHORD)
        // A new process doesn't bring it back from the kept copy.
        val restarted = registry(users = Users(listOf(UserAddonEntry(url("m"), id = "relay"))))
        restarted.loadPersisted()
        assertThat(restarted.addons.value).isEmpty()
    }

    @Test fun `an add-on that answers a call with BitChord's own 401 stops being asked, and says why`() = runBlocking<Unit> {
        // Its manifest never says `bitchordAuth`, so every manifest read still passes: only a call tells.
        serve("m", """{"id":"relay","name":"Relay","version":"1","resources":["search","stream"]}""")
        answers["m/search?q=x"] = { MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "BitChord realm=\"app\"").setBody("{}") }
        val registry = registry(users = Users(listOf(UserAddonEntry(url("m"), id = "relay"))))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            registry.start(scope, emptyFlow())
            val addon = withTimeout(5_000) { registry.addons.first { it.isNotEmpty() } }.single()
            val refused = (client.search(addon, "x") as AddonResult.Failed).failure as AddonFailure.Refused
            assertThat(refused.onlyBitChord).isTrue()
            // Off at once, with nothing else starting a pass.
            withTimeout(5_000) { registry.addons.first { it.isEmpty() } }
            assertThat(registry.entries.value.single().hold).isEqualTo(AddonHold.ONLY_BITCHORD)
            // A later pass reads its manifest, which still reads, and it stays off.
            registry.refresh()
            assertThat(registry.addons.value).isEmpty()
            assertThat(registry.entries.value.single().hold).isEqualTo(AddonHold.ONLY_BITCHORD)
            // The user replaced its link (or added it again): it is asked again.
            client.forgive("relay")
            registry.refresh()
            assertThat(registry.addons.value.map { it.id }).containsExactly("relay")
        } finally {
            scope.cancel()
        }
    }

    @Test fun `no pass publishes or asks before the kept index has been read`() = runBlocking<Unit> {
        // A cold start: whichever pass runs first, an add-on Stash turned off must not be published
        // from its kept copy, or asked, while the kept index is still being read.
        serve("m", manifestJson("mine"))
        val users = Users(listOf(UserAddonEntry(url("m"), id = "mine")))
        registry(users = users).refresh() // an earlier run kept its manifest
        assertThat(store.rows).hasSize(1)
        asked.clear()

        val read = CompletableDeferred<Unit>()
        val catalog = object : AddonCatalog {
            @Volatile var loaded = false
            override fun trustOf(base: String, id: String?) = if (loaded) Trust.BLOCKED else Trust.UNOFFICIAL
            override fun blockReason(base: String, id: String?) = BlockReason.HARMFUL.takeIf { loaded }
            override val official: StateFlow<List<OfficialEntry>> = MutableStateFlow(emptyList())
            override val changes: Flow<Any?> = emptyFlow()
            override suspend fun awaitLoaded() {
                read.await()
                loaded = true
            }
        }
        val restarted = registry(users = users, catalog = catalog)
        val pass = launch(Dispatchers.IO) { restarted.refresh(force = false) }
        delay(400)
        assertThat(restarted.addons.value).isEmpty()
        assertThat(asked).isEmpty()
        read.complete(Unit)
        pass.join()
        assertThat(restarted.addons.value).isEmpty()
        assertThat(asked).isEmpty()
        assertThat(restarted.entries.value.single().hold).isEqualTo(AddonHold.BLOCKED)
    }
}
