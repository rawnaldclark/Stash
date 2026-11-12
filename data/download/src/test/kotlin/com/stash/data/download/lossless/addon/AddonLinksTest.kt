package com.stash.data.download.lossless.addon

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.stash.core.addons.AddonHostOptions
import com.stash.core.addons.DefaultAddonEntry
import com.stash.core.addons.DefaultAddons
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.InTreeAddonRegistry
import com.stash.core.addons.NoAddonCatalog
import com.stash.core.addons.NoDefaultAddons
import com.stash.core.addons.NoManifestStore
import com.stash.core.addons.StrictAddonHost
import com.stash.data.download.lossless.PlayOrder
import com.stash.data.download.lossless.addon.AddonLinks.Refusal
import com.stash.data.download.lossless.tidal.FakeAddonRefDao
import com.stash.data.download.lossless.tidal.LocalHttp
import com.stash.data.download.lossless.tidal.TestAddonHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.security.GeneralSecurityException

/**
 * "Add from a link" (plan §5.5): what a pasted link is accepted as, what Add refuses and why, the
 * id clash, the header token, and what reaches the sealed list. review-881 M13 found only the
 * store tested, never [AddonLinks] itself.
 *
 * Written for #881's `AddonLinks`, whose refusals were English sentences; phase 4's are reasons
 * ([Refusal]) the screen puts into words, so each case below checks the reason. Two cases changed
 * with the plan (phase 4 §2.2) rather than with the wording: a root with no final `/` and a link
 * typed without `https://` are both accepted now. What the list shows for each add-on (its name,
 * else its host, never its link) moved to `AddonOverview`: `AddonOverviewRowsTest`. The agreement
 * an add-on Stash doesn't run needs, Replace link and the blocklist are in `AddFromLinkFlowTest`.
 */
class AddonLinksTest {

    private lateinit var server: MockWebServer
    private val dir: File = Files.createTempDirectory("addon-links").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refDao = FakeAddonRefDao()

    /** What `/<path>/manifest.json` answers; anything else is a 404. */
    private val manifests = HashMap<String, MockResponse>()

    @Before fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    synchronized(manifests) { manifests[request.requestUrl!!.encodedPath] } ?: MockResponse().setResponseCode(404)
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
    }

    @After fun tearDown() {
        server.shutdown()
        scope.cancel()
        dir.deleteRecursively()
    }

    /**
     * A reversible scramble standing in for the phone's key; [failSeals] makes every save fail,
     * whatever its size: removing the only add-on seals an empty list, two bytes of text.
     */
    private class Key(@Volatile var failSeals: Boolean = false) : EncryptedUserAddons.Sealer {
        override fun seal(plain: ByteArray): ByteArray {
            if (failSeals) throw GeneralSecurityException("keystore busy")
            return plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
        override fun open(sealed: ByteArray) = sealed.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    private val key = Key()
    private val file by lazy { PreferenceDataStoreFactory.create(scope = scope) { File(dir, "u.preferences_pb") } }
    private val users by lazy { EncryptedUserAddons(file, key) }

    /** The signed config's add-ons, for the id clash with one of Stash's own. */
    private val defaults = MutableStateFlow<List<DefaultAddonEntry>>(emptyList())
    private val savedOrder = MutableStateFlow<List<String>?>(null)

    private class Kit(val links: AddonLinks, val registry: InTreeAddonRegistry)

    /** [AddonLinks] over a real registry. No index: every add-on is one Stash doesn't run, so Add needs the user's agreement. */
    private fun kit(host: AddonHostOptions = LocalHttp): Kit {
        val client = HttpAddonClient(OkHttpClient(), TestAddonHost(), TestAddonHost(firstParty = false), TestAddonHost(), host)
        val stashOwn = object : DefaultAddons {
            override val entries: StateFlow<List<DefaultAddonEntry>> = defaults
        }
        val registry = InTreeAddonRegistry(stashOwn, users, client, NoManifestStore)
        val order = PlayOrder(savedOrder, { savedOrder.value = it }, registry)
        return Kit(AddonLinks(client, users, registry, host, AddonRefs(client, refDao), NoAddonCatalog, order), registry)
    }

    private fun links(host: AddonHostOptions = LocalHttp) = kit(host).links

    private fun link(path: String) = "http://127.0.0.1:${server.port}/$path/manifest.json"
    private fun keyOf(path: String) = InTreeAddonRegistry.keyOf(link(path))

    private fun serve(path: String, body: String) {
        synchronized(manifests) { manifests["/$path/manifest.json"] = MockResponse().setHeader("Content-Type", "application/json").setBody(body) }
    }

    private fun manifestJson(
        id: String = "radio",
        resources: String = """"search","resolve","stream","lyrics"""",
        kind: String = "server",
        stash: Int = 1,
        extra: String = "",
    ) = """{"stash":$stash,"id":"$id","name":"Radio Add-on","version":"1.2.0","kind":"$kind","description":"Songs from the radio",
        "resources":[$resources],"stream":{"cors":false,"manifests":["none","dash"],"tiers":["lossless","hires"]}$extra}"""

    private fun refusal(o: AddonLinks.Outcome) = (o as AddonLinks.Outcome.Refused).reason
    private suspend fun ready(links: AddonLinks, path: String) = (links.preview(link(path)) as AddonLinks.Outcome.Ready).preview

    private fun url(link: String, local: Boolean = false) = (AddonLinks.normalise(link, allowLocalHttp = local) as? AddonLinks.Normalised.Url)?.url

    // ------------------------------------------------------------------------------------------ the link

    @Test fun `a link is a manifest URL, or a base or a root that gets manifest_json`() {
        assertThat(url(" https://addon.example/tok/manifest.json ")).isEqualTo("https://addon.example/tok/manifest.json")
        assertThat(url("https://addon.example/tok/")).isEqualTo("https://addon.example/tok/manifest.json")
        // Phase 4 §2.2: the root with no final `/`, and a link typed without its scheme.
        assertThat(url("https://addon.example/tok")).isEqualTo("https://addon.example/tok/manifest.json")
        assertThat(url("addon.example/manifest.json")).isEqualTo("https://addon.example/manifest.json")
        for (bad in listOf(
            "https://addon.example/manifest.json?x=1",
            "https://addon.example/manifest.json#top",
            "https://user:pw@addon.example/manifest.json",
            "ftp://addon.example/manifest.json",
            "",
        )) {
            assertThat(url(bad)).isNull()
        }
    }

    @Test fun `http is for a debug build's localhost only`() = runBlocking<Unit> {
        // A debug build: the development hosts over http, nothing else.
        assertThat(url("http://127.0.0.1:8787/manifest.json", local = true)).isNotNull()
        assertThat(url("http://addon.example/manifest.json", local = true)).isNull()
        // A release build: no http at all, and it says so. Nothing is fetched.
        val release = links(StrictAddonHost)
        assertThat(url("http://127.0.0.1:8787/manifest.json", local = false)).isNull()
        assertThat(refusal(release.preview(link("a")))).isEqualTo(Refusal.NOT_HTTPS)
        assertThat(refusal(release.preview("not a link"))).isEqualTo(Refusal.NOT_AN_ADDON)
        assertThat(server.requestCount).isEqualTo(0)
    }

    // ------------------------------------------------------------------------------------------ preview

    @Test fun `the preview shows the manifest's own words and what Stash will use, and asks nothing with a token`() = runBlocking<Unit> {
        serve("a", manifestJson(extra = ""","auth":{"token":"header"}"""))
        val p = ready(links(), "a")
        assertThat(p.manifestUrl).isEqualTo(link("a"))
        assertThat(p.id).isEqualTo("radio")
        assertThat(p.name).isEqualTo("Radio Add-on")
        assertThat(p.version).isEqualTo("1.2.0")
        assertThat(p.description).isEqualTo("Songs from the radio")
        assertThat(p.uses).containsExactly(
            AddonLinks.Use.PLAYS_SONGS, AddonLinks.Use.LOSSLESS, AddonLinks.Use.DOWNLOADS, AddonLinks.Use.LYRICS,
        ).inOrder()
        assertThat(p.needsToken).isTrue()
        // The link can carry a token in its path: the preview never prints it.
        assertThat(p.toString()).isEqualTo("Preview(radio 1.2.0)")
        assertThat(server.takeRequest().getHeader("X-Access-Token")).isNull()
        // Nothing is kept by looking.
        assertThat(users.loadedEntries()).isEmpty()
    }

    @Test fun `what a preview refuses, and why`() = runBlocking<Unit> {
        val links = links()
        serve("pages", manifestJson(resources = """"album","artist","catalog""""))
        serve("newer", manifestJson(stash = 2))
        serve("junk", """{"hello":"world"}""")
        serve("broken", manifestJson(id = "Not An Id!"))
        serve("html", "<html>not an add-on</html>")

        assertThat(refusal(links.preview(link("pages")))).isEqualTo(Refusal.NOTHING_TO_PLAY)
        assertThat(refusal(links.preview(link("newer")))).isEqualTo(Refusal.NEEDS_NEWER_STASH)
        // Not a manifest, a manifest that isn't valid, a page, nothing at that address: none is an add-on.
        assertThat(refusal(links.preview(link("junk")))).isEqualTo(Refusal.NOT_AN_ADDON)
        assertThat(refusal(links.preview(link("broken")))).isEqualTo(Refusal.NOT_AN_ADDON)
        assertThat(refusal(links.preview(link("html")))).isEqualTo(Refusal.NOT_AN_ADDON)
        assertThat(refusal(links.preview(link("missing")))).isEqualTo(Refusal.NOT_AN_ADDON)
        // Nothing listens on port 9.
        assertThat(refusal(links.preview("http://127.0.0.1:9/manifest.json"))).isEqualTo(Refusal.UNREACHABLE)
        assertThat(users.loadedEntries()).isEmpty()
    }

    @Test fun `an id one of Stash's own add-ons has is refused, at the preview and again at Add`() = runBlocking<Unit> {
        val kit = kit()
        serve("a", manifestJson(id = "radio"))
        val preview = ready(kit.links, "a")

        // The id turned up between the preview and Add: the signed config now names an add-on by it.
        serve("stash-radio", manifestJson(id = "radio"))
        defaults.value = listOf(DefaultAddonEntry(link("stash-radio")))
        kit.registry.refresh(force = true)
        assertThat(kit.registry.addons.value.map { it.id }).containsExactly("radio")

        assertThat(refusal(kit.links.preview(link("a")))).isEqualTo(Refusal.ALREADY_ADDED)
        assertThat(refusal(kit.links.add(preview, null, agreed = true))).isEqualTo(Refusal.ALREADY_ADDED)
        assertThat(users.loadedEntries()).isEmpty()
    }

    // ------------------------------------------------------------------------------------------ add, remove, the switch

    @Test fun `Add keeps the link sealed, with the token only when the manifest asks for one`() = runBlocking<Unit> {
        val links = links()
        serve("open", manifestJson(id = "open"))
        serve("locked", manifestJson(id = "locked", extra = ""","auth":{"token":"header"}"""))
        val open = ready(links, "open")
        val locked = ready(links, "locked")

        // A token typed for an add-on that asked for none is not kept.
        assertThat(links.add(open, "typed-anyway", agreed = true)).isEqualTo(AddonLinks.Outcome.Added(keyOf("open")))
        // One that asks for a token is not added without it, nor with one a header can't carry.
        assertThat(refusal(links.add(locked, "  ", agreed = true))).isEqualTo(Refusal.NEEDS_PASSWORD)
        assertThat(refusal(links.add(locked, "badétoken", agreed = true))).isEqualTo(Refusal.BAD_PASSWORD)
        assertThat(links.add(locked, " s3cret ", agreed = true)).isEqualTo(AddonLinks.Outcome.Added(keyOf("locked")))

        val kept = users.loadedEntries()
        assertThat(kept.map { Triple(it.manifestUrl, it.token, it.enabled) }).containsExactly(
            Triple(link("open"), null, true),
            Triple(link("locked"), "s3cret", true),
        ).inOrder()
        // Who each one said it was when it was added, so its row needs no answer from it.
        assertThat(kept.map { it.id to it.name }).containsExactly("open" to "Radio Add-on", "locked" to "Radio Add-on").inOrder()
        // The same link again: refused before anything is fetched.
        val asked = server.requestCount
        assertThat(refusal(links.preview(link("open")))).isEqualTo(Refusal.ALREADY_ADDED)
        assertThat(server.requestCount).isEqualTo(asked)
    }

    @Test fun `Remove and the switch change the sealed list and say so`() = runBlocking<Unit> {
        val links = links()
        serve("a", manifestJson(id = "a"))
        serve("b", manifestJson(id = "b"))
        links.add(ready(links, "a"), null, agreed = true)
        links.add(ready(links, "b"), null, agreed = true)

        assertThat(links.setEnabled(keyOf("a"), false)).isTrue()
        assertThat(users.loadedEntries().map { it.manifestUrl to it.enabled }).containsExactly(link("a") to false, link("b") to true).inOrder()
        assertThat(links.remove(keyOf("a"))).isTrue()
        assertThat(users.loadedEntries().map { it.manifestUrl }).containsExactly(link("b"))
    }

    /** review-882 J2: a save that fails must come back as an answer, never as a throw into `viewModelScope.launch`. */
    @Test fun `a save that fails is reported, throws nothing, and changes nothing`() = runBlocking<Unit> {
        val links = links()
        serve("a", manifestJson(id = "a"))
        serve("b", manifestJson(id = "b"))
        links.add(ready(links, "a"), null, agreed = true)
        val b = ready(links, "b")

        key.failSeals = true
        assertThat(refusal(links.add(b, null, agreed = true))).isEqualTo(Refusal.COULD_NOT_SAVE)
        assertThat(links.remove(keyOf("a"))).isFalse()
        assertThat(links.setEnabled(keyOf("a"), false)).isFalse()
        assertThat(users.loadedEntries().map { it.manifestUrl to it.enabled }).containsExactly(link("a") to true)

        key.failSeals = false
        assertThat(links.add(b, null, agreed = true)).isEqualTo(AddonLinks.Outcome.Added(keyOf("b")))
        assertThat(users.loadedEntries()).hasSize(2)
    }

    /**
     * review-881 M11 and review-882 J2 together: while the stored list has not opened, Add, Remove and
     * the switch are refused (never written over it), and the list says it is unreadable rather than empty.
     */
    @Test fun `while the stored list will not open, nothing is written over it and the list says so`() = runBlocking<Unit> {
        val links = links()
        serve("a", manifestJson(id = "a"))
        serve("b", manifestJson(id = "b"))
        links.add(ready(links, "a"), null, agreed = true)
        assertThat(users.unreadable.value).isFalse()

        // The next run: the same file, and a key that is not answering.
        val busy = object : EncryptedUserAddons.Sealer {
            @Volatile var answering = false
            override fun seal(plain: ByteArray) = if (answering) key.seal(plain) else throw GeneralSecurityException("keystore busy")
            override fun open(sealed: ByteArray) = if (answering) key.open(sealed) else throw GeneralSecurityException("keystore busy")
        }
        var now = 0L
        val nextRun = EncryptedUserAddons(file, busy) { now }
        val client = HttpAddonClient(OkHttpClient(), TestAddonHost(), TestAddonHost(firstParty = false), TestAddonHost(), LocalHttp)
        val registry = InTreeAddonRegistry(NoDefaultAddons, nextRun, client, NoManifestStore)
        val next = AddonLinks(client, nextRun, registry, LocalHttp, AddonRefs(client, refDao), NoAddonCatalog, PlayOrder(savedOrder, { savedOrder.value = it }, registry))

        assertThat(nextRun.loadedEntries()).isEmpty()
        assertThat(nextRun.unreadable.value).isTrue()
        assertThat(refusal(next.add(ready(next, "b"), null, agreed = true))).isEqualTo(Refusal.COULD_NOT_SAVE)

        // The key answers again: the list is back, with what was in it.
        busy.answering = true
        now += EncryptedUserAddons.RETRY_EVERY_MS
        assertThat(nextRun.loadedEntries().map { it.manifestUrl }).containsExactly(link("a"))
        assertThat(nextRun.unreadable.value).isFalse()
    }
}
