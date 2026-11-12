package com.stash.data.download.lossless.addon

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.stash.core.addons.AddonCatalog
import com.stash.core.addons.AddonSees
import com.stash.core.addons.BlockReason
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.InTreeAddonRegistry
import com.stash.core.addons.NoDefaultAddons
import com.stash.core.addons.NoFirstPartyAddons
import com.stash.core.addons.NoManifestStore
import com.stash.core.addons.OfficialEntry
import com.stash.core.addons.OpenAddonRateGate
import com.stash.core.addons.Trust
import com.stash.core.addons.UnsignedAddons
import com.stash.data.download.lossless.PlayOrder
import com.stash.data.download.lossless.tidal.FakeAddonRefDao
import com.stash.data.download.lossless.tidal.LocalHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private val dir: File = Files.createTempDirectory("add-from-link").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty().removePrefix("/")
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
        override fun seal(plain: ByteArray): ByteArray {
            if (failing) throw java.security.GeneralSecurityException("keystore")
            return plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
        override fun open(sealed: ByteArray) = sealed.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    private class Catalog : AddonCatalog {
        @Volatile var blocked: (base: String, id: String?) -> BlockReason? = { _, _ -> null }
        @Volatile var officialBases: Set<String> = emptySet()
        override fun trustOf(base: String, id: String?) = when {
            blocked(base, id) != null -> Trust.BLOCKED
            base in officialBases -> Trust.OFFICIAL
            else -> Trust.UNOFFICIAL
        }
        override fun blockReason(base: String, id: String?) = blocked(base, id)
        override val official: StateFlow<List<OfficialEntry>> = MutableStateFlow(emptyList())
        override val changes: Flow<Any?> = emptyFlow()
    }

    private val sealer = Scramble()
    private val catalog = Catalog()
    private val savedOrder = MutableStateFlow<List<String>?>(null)
    private val refDao = FakeAddonRefDao()

    private val users by lazy { EncryptedUserAddons(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "u.preferences_pb") }, sealer) }
    private val client by lazy { HttpAddonClient(OkHttpClient(), OpenAddonRateGate, NoFirstPartyAddons, UnsignedAddons, LocalHttp) }
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
        assertThat(p.sees).containsExactly(AddonSees.SEARCHES, AddonSees.PLAYS)
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
        assertThat(entry.consent).containsExactly("SEARCHES", "PLAYS")
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
        serve("a", "hifi")
        links.add(ready(url("a")), token = null, agreed = true)
        serve("b", "hifi", resources = "\"search\",\"stream\",\"download\"")
        val p = ready(url("b"), replacing = InTreeAddonRegistry.keyOf(url("a")))
        assertThat(p.needsConsent).isTrue()
        assertThat(links.add(p, token = null)).isEqualTo(AddonLinks.Outcome.Refused(AddonLinks.Refusal.NOT_AGREED))
        assertThat(links.add(p, token = null, agreed = true)).isInstanceOf(AddonLinks.Outcome.Added::class.java)
        assertThat(users.loadedEntries().single().consent).containsExactly("SEARCHES", "PLAYS", "SAVES")
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
}
