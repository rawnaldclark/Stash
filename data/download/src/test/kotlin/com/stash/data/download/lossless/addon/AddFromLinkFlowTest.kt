package com.stash.data.download.lossless.addon

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.stash.core.addons.AddonCatalog
import com.stash.core.addons.AddonFailure
import com.stash.core.addons.AddonHold
import com.stash.core.addons.AddonRateGate
import com.stash.core.addons.AddonResult
import com.stash.core.addons.AddonSees
import com.stash.core.addons.BlockReason
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.InTreeAddonRegistry
import com.stash.core.addons.NoDefaultAddons
import com.stash.core.addons.NoFirstPartyAddons
import com.stash.core.addons.NoManifestStore
import com.stash.core.addons.OfficialEntry
import com.stash.core.addons.Trust
import com.stash.core.addons.UnsignedAddons
import com.stash.core.data.db.entity.AddonRefEntity
import com.stash.data.download.lossless.PlayOrder
import com.stash.data.download.lossless.tidal.FakeAddonRefDao
import com.stash.data.download.lossless.tidal.LocalHttp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Add from a link, end to end below the screen (add-ons plan phase 4 §1.9, §2.1, §2.7, §2.9): a link in,
 * the add-on read from a real (local) server, what the preview says, and what Add stores. The rules
 * that protect the user are the ones checked: a blocked add-on is refused with its reason, nothing
 * Stash doesn't run is added without the user's agreement, what they agreed to is kept, and a
 * replaced link is the same add-on in the same place.
 */
class AddFromLinkFlowTest {

    private lateinit var server: MockWebServer
    private val manifests = ConcurrentHashMap<String, String>()

    /** Paths (with their query) the server refuses, and the status it refuses them with. */
    private val refusals = ConcurrentHashMap<String, Int>()
    private val dir: File = Files.createTempDirectory("add-from-link").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty().removePrefix("/")
                    refusals[path]?.let { return MockResponse().setResponseCode(it).setBody("{}") }
                    return manifests[path]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
    }

    @After fun tearDown() {
        runCatching { server.shutdown() }
        scope.cancel()
        dir.deleteRecursively()
    }

    /** Not encryption: a reversible scramble standing in for the Keystore. [failing] makes every seal throw, as a Keystore's bad moment does. */
    private class Scramble : EncryptedUserAddons.Sealer {
        @Volatile var failing = false

        /** The threads a save sealed the list on. The threads themselves: a test run renames them while a coroutine is on them. */
        val threads = CopyOnWriteArrayList<Thread>()
        override fun seal(plain: ByteArray): ByteArray {
            threads += Thread.currentThread()
            if (failing) throw java.security.GeneralSecurityException("keystore")
            return plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
        override fun open(sealed: ByteArray) = sealed.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    private class Catalog : AddonCatalog {
        @Volatile var blocked: (base: String, id: String?) -> BlockReason? = { _, _ -> null }
        @Volatile var officialBases: Set<String> = emptySet()

        /** Set: the copy kept from the last run is still being read, until this completes. */
        @Volatile var loading: CompletableDeferred<Unit>? = null

        /** The threads a link was checked on. */
        val threads = CopyOnWriteArrayList<Thread>()
        override fun trustOf(base: String, id: String?) = when {
            blocked(base, id) != null -> Trust.BLOCKED
            base in officialBases -> Trust.OFFICIAL
            else -> Trust.UNOFFICIAL
        }
        override fun blockReason(base: String, id: String?): BlockReason? {
            threads += Thread.currentThread()
            return blocked(base, id)
        }
        override val official: StateFlow<List<OfficialEntry>> = MutableStateFlow(emptyList())
        override val changes: Flow<Any?> = emptyFlow()
        override suspend fun awaitLoaded() {
            loading?.await()
        }
    }

    /** Never pauses anything; remembers which pauses were ended. */
    private class Gate : AddonRateGate {
        val resets = CopyOnWriteArrayList<String>()
        override suspend fun admit(key: String, paced: Boolean) = true
        override suspend fun onAnswer(key: String) = Unit
        override suspend fun onRateLimited(key: String, retryAfterMs: Long?) = Unit
        override suspend fun onFailure(key: String) = Unit
        override suspend fun reset(key: String) { resets += key }
    }

    private val sealer = Scramble()
    private val catalog = Catalog()
    private val gate = Gate()
    private val savedOrder = MutableStateFlow<List<String>?>(null)
    private val refDao = FakeAddonRefDao()

    private val users by lazy { EncryptedUserAddons(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "u.preferences_pb") }, sealer) }
    private val client by lazy { HttpAddonClient(OkHttpClient(), gate, NoFirstPartyAddons, UnsignedAddons, LocalHttp) }
    private val registry by lazy { InTreeAddonRegistry(NoDefaultAddons, users, client, NoManifestStore, catalog) }
    private val links by lazy {
        AddonLinks(client, users, registry, LocalHttp, AddonRefs(client, refDao), catalog, PlayOrder(savedOrder, { savedOrder.value = it }, registry))
    }

    private fun url(path: String) = "http://127.0.0.1:${server.port}/$path/manifest.json"
    private fun base(path: String) = "http://127.0.0.1:${server.port}/$path/"

    /** An Eclipse-shaped add-on at [path]: what most links people paste are. */
    private fun serve(path: String, id: String, resources: String = "\"search\",\"stream\"", name: String = "Hi-Fi Relay") {
        manifests["$path/manifest.json"] = """{"id":"$id","name":"$name","version":"1.0.0","resources":[$resources]}"""
    }

    private suspend fun ready(link: String, replacing: String? = null) = (links.preview(link, replacing) as AddonLinks.Outcome.Ready).preview
    private suspend fun refused(link: String, replacing: String? = null) = (links.preview(link, replacing) as AddonLinks.Outcome.Refused).reason

    // ------------------------------------------------------------------------------------------ the preview

    @Test fun `the preview says who it is, where it runs, what Stash will use and what its server will see`() = runBlocking<Unit> {
        serve("v1/s3cret", "hifi")
        val p = ready(url("v1/s3cret"))
        assertThat(p.id).isEqualTo("hifi")
        assertThat(p.name).isEqualTo("Hi-Fi Relay")
        assertThat(p.version).isEqualTo("1.0.0")
        // The address only: never the path, which can hold a token.
        assertThat(p.host).isEqualTo("127.0.0.1:${server.port}")
        assertThat(p.host).doesNotContain("s3cret")
        assertThat(p.trust).isEqualTo(Trust.UNOFFICIAL)
        assertThat(p.needsConsent).isTrue()
        // It has no download route, and Stash would still take downloads from its stream: it sees what is saved too.
        assertThat(p.sees).containsExactly(AddonSees.PLAYS, AddonSees.NEXT_UP, AddonSees.SAVES)
        assertThat(p.uses).containsExactly(AddonLinks.Use.PLAYS_SONGS, AddonLinks.Use.DOWNLOADS).inOrder()
        assertThat(p.playsSongs).isTrue()
        // Reading it added nothing.
        assertThat(users.loadedEntries()).isEmpty()
    }

    @Test fun `what isn't an add-on, or gives Stash nothing to play, is refused with its reason`() = runBlocking<Unit> {
        assertThat(refused(url("nothing-here"))).isEqualTo(AddonLinks.Refusal.NOT_AN_ADDON)
        manifests["page/manifest.json"] = """{"hello":"world"}"""
        assertThat(refused(url("page"))).isEqualTo(AddonLinks.Refusal.NOT_AN_ADDON)
        serve("pages", "pages", resources = "\"search\",\"catalog\"")
        assertThat(refused(url("pages"))).isEqualTo(AddonLinks.Refusal.NOTHING_TO_PLAY)
        manifests["bc/manifest.json"] = """{"id":"bc","name":"BC","version":"1","resources":["search","stream"],"bitchordAuth":true}"""
        assertThat(refused(url("bc"))).isEqualTo(AddonLinks.Refusal.ONLY_BITCHORD)
        manifests["new/manifest.json"] = """{"stash":2,"id":"new","name":"N","version":"1","kind":"server","resources":["lyrics"]}"""
        assertThat(refused(url("new"))).isEqualTo(AddonLinks.Refusal.NEEDS_NEWER_STASH)
        assertThat(refused("https://192.168.1.10/manifest.json")).isEqualTo(AddonLinks.Refusal.LOCAL_ADDRESS)
        assertThat(refused("https://relay.example.org/manifest.json?token=1")).isEqualTo(AddonLinks.Refusal.NOT_AN_ADDON)
    }

    // ------------------------------------------------------------------------------------------ the blocklist

    @Test fun `a link on a blocked origin is refused before anything is asked of it`() = runBlocking<Unit> {
        serve("x", "hifi")
        catalog.blocked = { _, _ -> BlockReason.HARMFUL }
        assertThat(refused(url("x"))).isEqualTo(AddonLinks.Refusal.BLOCKED_HARMFUL)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `an add-on blocked by its id is refused once it has said who it is, with that block's reason`() = runBlocking<Unit> {
        serve("x", "hifi")
        catalog.blocked = { _, id -> BlockReason.IMPERSONATION.takeIf { id == "hifi" } }
        assertThat(refused(url("x"))).isEqualTo(AddonLinks.Refusal.BLOCKED_IMPERSONATION)
        // A reason from a later list than this app knows refuses it all the same.
        catalog.blocked = { _, id -> BlockReason.OTHER.takeIf { id == "hifi" } }
        assertThat(refused(url("x"))).isEqualTo(AddonLinks.Refusal.BLOCKED)
    }

    @Test fun `a block that lands between the preview and the tap still refuses the add`() = runBlocking<Unit> {
        serve("x", "hifi")
        val p = ready(url("x"))
        catalog.blocked = { _, _ -> BlockReason.BROKEN }
        assertThat(links.add(p, token = null, agreed = true)).isEqualTo(AddonLinks.Outcome.Refused(AddonLinks.Refusal.BLOCKED_BROKEN))
        assertThat(users.loadedEntries()).isEmpty()
    }

    // ------------------------------------------------------------------------------------------ agreement

    @Test fun `an add-on Stash doesn't run is never added without the user's agreement, and what they agreed to is kept`() = runBlocking<Unit> {
        serve("x", "hifi")
        val p = ready(url("x"))
        assertThat(links.add(p, token = null)).isEqualTo(AddonLinks.Outcome.Refused(AddonLinks.Refusal.NOT_AGREED))
        assertThat(users.loadedEntries()).isEmpty()

        val added = links.add(p, token = null, agreed = true)
        assertThat(added).isInstanceOf(AddonLinks.Outcome.Added::class.java)
        val entry = users.loadedEntries().single()
        assertThat(entry.id).isEqualTo("hifi")
        assertThat(entry.name).isEqualTo("Hi-Fi Relay")
        assertThat(entry.consent).containsExactly("PLAYS", "NEXT_UP", "SAVES")
        assertThat(entry.addedAt).isNotNull()
        assertThat(links.lastAdded.value).isEqualTo((added as AddonLinks.Outcome.Added).key)
    }

    @Test fun `an official add-on has no sheet, and nothing to agree to is recorded`() = runBlocking<Unit> {
        serve("official", "radio", name = "Example Radio")
        catalog.officialBases = setOf(base("official"))
        val p = ready(url("official"))
        assertThat(p.trust).isEqualTo(Trust.OFFICIAL)
        assertThat(p.needsConsent).isFalse()
        assertThat(links.add(p, token = null)).isInstanceOf(AddonLinks.Outcome.Added::class.java)
        assertThat(users.loadedEntries().single().consent).isNull()
    }

    @Test fun `an add-on that stopped being Official between the preview and the tap needs the agreement after all`() = runBlocking<Unit> {
        // review-885 M3: Add trusted where the add-on stood at the preview.
        serve("official", "radio", name = "Example Radio")
        catalog.officialBases = setOf(base("official"))
        val p = ready(url("official"))
        assertThat(p.needsConsent).isFalse()
        catalog.officialBases = emptySet()
        assertThat(links.add(p, token = null)).isEqualTo(AddonLinks.Outcome.Refused(AddonLinks.Refusal.NOT_AGREED))
        assertThat(users.loadedEntries()).isEmpty()
        assertThat(links.add(p, token = null, agreed = true)).isInstanceOf(AddonLinks.Outcome.Added::class.java)
        assertThat(users.loadedEntries().single().consent).containsExactly("PLAYS", "NEXT_UP", "SAVES")
    }

    @Test fun `a new add-on starts last, whatever place its id had in a saved order`() = runBlocking<Unit> {
        // review-885 M11: a restored settings backup carries the play order and never the add-ons.
        savedOrder.value = listOf("addon:hifi", "qobuz", "tidal")
        serve("x", "hifi")
        links.add(ready(url("x")), token = null, agreed = true)
        assertThat(savedOrder.value).containsExactly("qobuz", "tidal").inOrder()
    }

    // ------------------------------------------------------------------------------------------ the same add-on twice

    @Test fun `the same link typed another way, or another link to the same id, is already added`() = runBlocking<Unit> {
        serve("x", "hifi")
        links.add(ready(url("x")), token = null, agreed = true)
        // The base, without its file: the same manifest.
        assertThat(refused(base("x"))).isEqualTo(AddonLinks.Refusal.ALREADY_ADDED)
        // Another server claiming the same id.
        serve("y", "hifi", name = "Someone else")
        assertThat(refused(url("y"))).isEqualTo(AddonLinks.Refusal.ALREADY_ADDED)
        // Switched off still counts: its id is taken.
        links.setEnabled(InTreeAddonRegistry.keyOf(url("x")), false)
        assertThat(refused(url("y"))).isEqualTo(AddonLinks.Refusal.ALREADY_ADDED)
        assertThat(users.loadedEntries()).hasSize(1)
    }

    // ------------------------------------------------------------------------------------------ Replace link, Remove

    @Test fun `a replaced link is the same add-on in the same place, with what was agreed`() = runBlocking<Unit> {
        serve("old-tok", "hifi")
        serve("other", "other", name = "Other")
        links.add(ready(url("old-tok")), token = null, agreed = true)
        links.add(ready(url("other")), token = null, agreed = true)
        val key = InTreeAddonRegistry.keyOf(url("old-tok"))
        val before = users.loadedEntries().first()

        // The same add-on under a new token, on the same origin.
        serve("new-tok", "hifi")
        val p = ready(url("new-tok"), replacing = key)
        assertThat(p.replacesKey).isEqualTo(key)
        assertThat(p.needsConsent).isFalse() // it sees no more than was agreed
        assertThat(links.add(p, token = null)).isInstanceOf(AddonLinks.Outcome.Added::class.java)
        val now = users.loadedEntries()
        assertThat(now.map { it.id }).containsExactly("hifi", "other").inOrder()
        assertThat(now.first().manifestUrl).isEqualTo(url("new-tok"))
        assertThat(now.first().consent).isEqualTo(before.consent)
        assertThat(now.first().addedAt).isEqualTo(before.addedAt)

        // Another add-on's link is not a replacement.
        serve("stranger", "stranger")
        assertThat(refused(url("stranger"), replacing = InTreeAddonRegistry.keyOf(url("new-tok")))).isEqualTo(AddonLinks.Refusal.DIFFERENT_ADDON)
        assertThat(refused("https://elsewhere.example.org/manifest.json", replacing = "no-such-add-on")).isEqualTo(AddonLinks.Refusal.DIFFERENT_ADDON)
    }

    @Test fun `a replacement that sees more asks again`() = runBlocking<Unit> {
        // A v1 add-on with lossy streams only: Stash takes no download from those, so at first it sees
        // nothing that is saved. Then it says it has lossless ones too, and Stash would keep songs from it.
        fun v1(tiers: String) =
            """{"stash":1,"id":"hifi","name":"Hi-Fi Relay","version":"1.0.0","kind":"server","resources":["search","stream"],"stream":{"cors":false,"tiers":[$tiers]}}"""
        manifests["a/manifest.json"] = v1("\"high\"")
        links.add(ready(url("a")), token = null, agreed = true)
        assertThat(users.loadedEntries().single().consent).containsExactly("PLAYS", "NEXT_UP")
        manifests["b/manifest.json"] = v1("\"high\",\"lossless\"")
        val p = ready(url("b"), replacing = InTreeAddonRegistry.keyOf(url("a")))
        assertThat(p.needsConsent).isTrue()
        assertThat(links.add(p, token = null)).isEqualTo(AddonLinks.Outcome.Refused(AddonLinks.Refusal.NOT_AGREED))
        assertThat(links.add(p, token = null, agreed = true)).isInstanceOf(AddonLinks.Outcome.Added::class.java)
        assertThat(users.loadedEntries().single().consent).containsExactly("PLAYS", "NEXT_UP", "SAVES")
    }

    @Test fun `Remove takes its place in the play order with it`() = runBlocking<Unit> {
        serve("x", "hifi")
        links.add(ready(url("x")), token = null, agreed = true)
        savedOrder.value = listOf("addon:hifi", "qobuz", "tidal")
        assertThat(links.remove(InTreeAddonRegistry.keyOf(url("x")))).isTrue()
        assertThat(users.loadedEntries()).isEmpty()
        assertThat(savedOrder.value).containsExactly("qobuz", "tidal").inOrder()
        // And it can be added again, asking again.
        assertThat(ready(url("x")).needsConsent).isTrue()
    }

    // ------------------------------------------------------------------------------------------ a save that fails

    @Test fun `a save that fails is said, changes nothing, and never throws at the screen`() = runBlocking<Unit> {
        serve("x", "hifi")
        serve("y", "other", name = "Other")
        links.add(ready(url("x")), token = null, agreed = true)
        val p = ready(url("y"))
        sealer.failing = true
        assertThat(links.add(p, token = null, agreed = true)).isEqualTo(AddonLinks.Outcome.Refused(AddonLinks.Refusal.COULD_NOT_SAVE))
        val key = InTreeAddonRegistry.keyOf(url("x"))
        assertThat(links.setEnabled(key, false)).isFalse()
        assertThat(links.remove(key)).isFalse()
        sealer.failing = false
        // Nothing changed: the first add-on is still there, on.
        assertThat(users.loadedEntries().map { it.id to it.enabled }).containsExactly("hifi" to true)
        assertThat(links.setEnabled(key, false)).isTrue()
    }

    @Test fun `a password the add-on asks for is needed, and one a request can't carry is refused`() = runBlocking<Unit> {
        manifests["t/manifest.json"] = """{"stash":1,"id":"locked","name":"Locked","version":"1","kind":"server","resources":["lyrics"],"auth":{"token":"header"}}"""
        val p = ready(url("t"))
        assertThat(p.needsToken).isTrue()
        assertThat(links.add(p, token = " ", agreed = true)).isEqualTo(AddonLinks.Outcome.Refused(AddonLinks.Refusal.NEEDS_PASSWORD))
        assertThat(links.add(p, token = "pässword", agreed = true)).isEqualTo(AddonLinks.Outcome.Refused(AddonLinks.Refusal.BAD_PASSWORD))
        assertThat(links.add(p, token = "s3cret", agreed = true)).isInstanceOf(AddonLinks.Outcome.Added::class.java)
        assertThat(users.loadedEntries().single().token).isEqualTo("s3cret")
    }

    // ------------------------------------------------------------------------------------------ what the reviews found only said (881 N10; 882 M5, N3), and §2.7, §2.9

    @Test fun `Remove forgets what the add-on said about songs, switched off and never read this run`() = runBlocking<Unit> {
        serve("x", "hifi")
        serve("y", "other", name = "Other")
        links.add(ready(url("x")), token = null, agreed = true)
        links.add(ready(url("y")), token = null, agreed = true)
        refDao.upsert(AddonRefEntity(addonId = "hifi", songKey = "isrc:AA0000000001", remoteId = "1", resolvedAt = 1L))
        refDao.upsert(AddonRefEntity(addonId = "other", songKey = "isrc:AA0000000002", remoteId = "2", resolvedAt = 1L))
        val key = InTreeAddonRegistry.keyOf(url("x"))
        assertThat(links.setEnabled(key, false)).isTrue()
        // The registry has read nothing this run: only the id kept at Add can name its refs.
        assertThat(registry.addons.value).isEmpty()
        assertThat(registry.entries.value).isEmpty()
        assertThat(links.remove(key)).isTrue()
        assertThat(refDao.rows.values.map { it.addonId }).containsExactly("other")
    }

    @Test fun `agreeing to what an add-on now wants to see is recorded, and it is used again`() = runBlocking<Unit> {
        // Lossy streams only, so nothing is saved from it until it says it has lossless ones.
        fun v1(tiers: String) =
            """{"stash":1,"id":"hifi","name":"Hi-Fi Relay","version":"1.0.0","kind":"server","resources":["search","stream"],"stream":{"cors":false,"tiers":[$tiers]}}"""
        manifests["a/manifest.json"] = v1("\"high\"")
        links.add(ready(url("a")), token = null, agreed = true)
        registry.refresh()
        assertThat(registry.addons.value.map { it.id }).containsExactly("hifi")

        // Its server now has lossless files, so Stash would keep songs from it: more than was agreed. It waits.
        manifests["a/manifest.json"] = v1("\"high\",\"lossless\"")
        registry.refresh()
        assertThat(registry.addons.value).isEmpty()
        assertThat(registry.entries.value.single().hold).isEqualTo(AddonHold.WANTS_MORE)

        val key = InTreeAddonRegistry.keyOf(url("a"))
        assertThat(links.agree(key)).isTrue()
        assertThat(users.loadedEntries().single().consent).containsExactly("PLAYS", "NEXT_UP", "SAVES")
        registry.refresh()
        assertThat(registry.addons.value.map { it.id }).containsExactly("hifi")
        assertThat(registry.entries.value.single().hold).isNull()
    }

    @Test fun `the sheet and the add-on's page say the same about what its server sees, and both match the Downloads use`() = runBlocking<Unit> {
        // The add-on seen on the phone: search, resolve and stream, no download route. Its sheet said "and
        // save"; its page, showing Downloads, did not.
        manifests["p/manifest.json"] =
            """{"stash":1,"id":"echo","name":"Echo","version":"1.0.0","kind":"server","resources":["search","resolve","stream"],"stream":{"cors":false}}"""
        val p = ready(url("p"))
        assertThat(p.uses).contains(AddonLinks.Use.DOWNLOADS)
        assertThat(p.sees).containsExactly(AddonSees.PLAYS, AddonSees.NEXT_UP, AddonSees.SAVES)
        links.add(p, token = null, agreed = true)
        registry.refresh()
        val row = AddonOverview.build(
            userEntries = users.entries.value,
            entries = registry.entries.value,
            reading = false,
            named = AddonOverview.Named(hasQobuz = false, tidalKnown = false, ownHost = null, off = emptySet()),
            saved = null,
            official = emptyList(),
            unauthorized = emptySet(),
        ).row("addon:echo")!!
        assertThat(row.status).isEqualTo(AddonOverview.Status.WORKING)
        assertThat(row.sees).isEqualTo(p.sees)
        assertThat(row.uses).isEqualTo(p.uses)
    }

    @Test fun `a link that stopped working says Needs a new link, and replacing it ends the pause`() = runBlocking<Unit> {
        serve("old-tok", "hifi")
        links.add(ready(url("old-tok")), token = null, agreed = true)
        registry.refresh()
        val addon = registry.addons.value.single()

        /** The add-on's row as Settings › Add-ons builds it, from the same registry and client. */
        fun status() = AddonOverview.build(
            userEntries = users.entries.value,
            entries = registry.entries.value,
            reading = false,
            named = AddonOverview.Named(hasQobuz = false, tidalKnown = true, ownHost = null, off = emptySet()),
            saved = null,
            official = emptyList(),
            unauthorized = client.unauthorized.value,
        ).row("addon:hifi")!!.status
        assertThat(status()).isEqualTo(AddonOverview.Status.WORKING)

        // The token in its link stopped working: a plain 401 on a call.
        refusals["old-tok/search?q=x"] = 401
        val refused = (client.search(addon, "x") as AddonResult.Failed).failure as AddonFailure.Refused
        assertThat(refused.status).isEqualTo(401)
        assertThat(status()).isEqualTo(AddonOverview.Status.NEEDS_NEW_LINK)

        // Replace link, from its page: the same add-on under a new token.
        gate.resets.clear()
        serve("new-tok", "hifi")
        val key = InTreeAddonRegistry.keyOf(url("old-tok"))
        assertThat(links.add(ready(url("new-tok"), replacing = key), token = null)).isInstanceOf(AddonLinks.Outcome.Added::class.java)
        registry.refresh()
        assertThat(status()).isEqualTo(AddonOverview.Status.WORKING)
        // And the hour's pause the 401 started is over.
        assertThat(gate.resets).contains(HttpAddonClient.rateKey("hifi"))
    }

    @Test fun `reading a link and saving the list never run on the thread that asked`() {
        // The screen asks from the main thread. A manifest's JSON and the Keystore's seal are work for another one.
        serve("x", "hifi")
        // The asking thread itself, not its name: a test run renames a thread while a coroutine is on it.
        var screen: Thread? = null
        val asking = Executors.newSingleThreadExecutor { r -> Thread(r, "the-screen").also { screen = it } }
        try {
            runBlocking(asking.asCoroutineDispatcher()) {
                assertThat(Thread.currentThread()).isSameInstanceAs(screen)
                val p = ready(url("x"))
                val key = InTreeAddonRegistry.keyOf(url("x"))
                assertThat(links.add(p, token = null, agreed = true)).isInstanceOf(AddonLinks.Outcome.Added::class.java)
                assertThat(links.setEnabled(key, false)).isTrue()
                assertThat(links.remove(key)).isTrue()
                assertThat(Thread.currentThread()).isSameInstanceAs(screen)
            }
        } finally {
            asking.shutdownNow()
        }
        assertThat(screen).isNotNull()
        // The link was checked before and after its manifest was read and again at Add; the list was sealed three times.
        assertThat(catalog.threads.size).isAtLeast(3)
        assertThat(sealer.threads).hasSize(3)
        assertThat(catalog.threads + sealer.threads).doesNotContain(screen)
    }

    @Test fun `a link read in the app's first second waits for the kept blocklist, and a blocked add-on is asked nothing`() = runBlocking<Unit> {
        serve("x", "hifi")
        val loading = CompletableDeferred<Unit>()
        catalog.loading = loading
        val reading = async(Dispatchers.IO) { links.preview(url("x")) }
        delay(400)
        assertThat(server.requestCount).isEqualTo(0)
        // The copy kept from the last run is read: it blocks this add-on.
        catalog.blocked = { _, _ -> BlockReason.HARMFUL }
        loading.complete(Unit)
        assertThat(reading.await()).isEqualTo(AddonLinks.Outcome.Refused(AddonLinks.Refusal.BLOCKED_HARMFUL))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `an add-on that runs on the phone, or in a frame, is never added from a link`() = runBlocking<Unit> {
        // Owner decision: an add-on Stash doesn't run is a server, never code on the phone. Both of
        // these are valid manifests, and the second lists everything a server would need to be added.
        manifests["phone/manifest.json"] = """{"stash":1,"id":"onphone","name":"On the phone","version":"1.0.0","kind":"phone","resources":["search"],
            "phone":{"bundle":{"path":"main.js","sha256":"a3f1c2d4e5b6978877665544332211ffeeddccbbaa99887766554433221100aa"},"domains":["api.example.org"]}}"""
        manifests["frame/manifest.json"] = """{"stash":1,"id":"inframe","name":"In a frame","version":"1.0.0","kind":"frame",
            "frame":"https://frames.example.org/frame.html","resources":["search","resolve","stream"],"stream":{"cors":true}}"""
        assertThat(refused(url("phone"))).isEqualTo(AddonLinks.Refusal.RUNS_ON_PHONE)
        assertThat(refused(url("frame"))).isEqualTo(AddonLinks.Refusal.RUNS_ON_PHONE)
        assertThat(users.loadedEntries()).isEmpty()
    }

    @Test fun `whatever reading a link throws is an answer on the screen, never a crash`() = runBlocking<Unit> {
        serve("x", "hifi")
        catalog.blocked = { _, _ -> throw IllegalStateException("https://relay.example.org/s3cret/") }
        assertThat(refused(url("x"))).isEqualTo(AddonLinks.Refusal.UNREACHABLE)
        assertThat(users.loadedEntries()).isEmpty()
    }
}
