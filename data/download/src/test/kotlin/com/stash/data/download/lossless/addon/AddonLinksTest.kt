package com.stash.data.download.lossless.addon

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Manifest
import com.stash.core.addons.AddonHostOptions
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.InstalledAddon
import com.stash.core.addons.StrictAddonHost
import com.stash.core.addons.UserAddonEntry
import com.stash.data.download.lossless.tidal.FakeAddonRefDao
import com.stash.data.download.lossless.tidal.FixedRegistry
import com.stash.data.download.lossless.tidal.LocalHttp
import com.stash.data.download.lossless.tidal.TestAddonHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.security.GeneralSecurityException

/**
 * "Add from a link" (plan §5.5): what a pasted link is accepted as, what Add refuses and why, the
 * id clash, the header token, and what reaches the sealed list. review-881 M13 found only the
 * store tested, never [AddonLinks] itself.
 */
class AddonLinksTest {

    private lateinit var server: MockWebServer
    private val dir: File = Files.createTempDirectory("addon-links").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registry = FixedRegistry()
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

    /** A reversible scramble standing in for the phone's key; [failSeals] makes every save fail. */
    private class Key(@Volatile var failSeals: Boolean = false) : EncryptedUserAddons.Sealer {
        override fun seal(plain: ByteArray): ByteArray {
            if (failSeals && plain.size > 5) throw GeneralSecurityException("keystore busy")
            return plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
        override fun open(sealed: ByteArray) = sealed.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    private val key = Key()
    private val users by lazy {
        EncryptedUserAddons(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "u.preferences_pb") }, key)
    }

    private fun links(host: AddonHostOptions = LocalHttp) =
        HttpAddonClient(OkHttpClient(), TestAddonHost(), TestAddonHost(firstParty = false), TestAddonHost(), host).let { client ->
            AddonLinks(client, users, registry, host, AddonRefs(client, refDao))
        }

    private fun link(path: String) = "http://127.0.0.1:${server.port}/$path/manifest.json"

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

    private fun refusal(o: AddonLinks.Outcome) = (o as AddonLinks.Outcome.Refused).message

    private fun installed(id: String, userAdded: Boolean) = InstalledAddon(
        base = "https://elsewhere.example/$id/",
        manifest = Manifest(stash = 1, id = id, name = "Other", version = "1", kind = AddonKind.SERVER, resources = listOf("search", "stream")),
        userAdded = userAdded,
        firstParty = !userAdded,
    )

    // ------------------------------------------------------------------------------------------ the link

    @Test fun `a link is a manifest URL, or a base that gets manifest_json`() {
        val links = links()
        assertThat(links.normalise(" https://addon.example/tok/manifest.json ")).isEqualTo("https://addon.example/tok/manifest.json")
        assertThat(links.normalise("https://addon.example/tok/")).isEqualTo("https://addon.example/tok/manifest.json")
        for (bad in listOf(
            "https://addon.example/tok",
            "https://addon.example/manifest.json?x=1",
            "https://addon.example/manifest.json#top",
            "https://user:pw@addon.example/manifest.json",
            "ftp://addon.example/manifest.json",
            "addon.example/manifest.json",
            "",
        )) {
            assertThat(links.normalise(bad)).isNull()
        }
    }

    @Test fun `http is for a debug build's localhost only`() = runBlocking<Unit> {
        // A debug build: the development hosts over http, nothing else.
        assertThat(links(LocalHttp).normalise("http://127.0.0.1:8787/manifest.json")).isNotNull()
        assertThat(links(LocalHttp).normalise("http://addon.example/manifest.json")).isNull()
        // A release build: no http at all, and it says so. Nothing is fetched.
        val release = links(StrictAddonHost)
        assertThat(release.normalise("http://127.0.0.1:8787/manifest.json")).isNull()
        assertThat(refusal(release.preview(link("a")))).isEqualTo("The link must start with https://.")
        assertThat(refusal(release.preview("not a link"))).isEqualTo("Paste the add-on's manifest link. It ends in /manifest.json.")
        assertThat(server.requestCount).isEqualTo(0)
    }

    // ------------------------------------------------------------------------------------------ preview

    @Test fun `the preview shows the manifest's own words and what it offers, and asks nothing with a token`() = runBlocking<Unit> {
        serve("a", manifestJson(extra = ""","auth":{"token":"header"}"""))
        val ready = links().preview(link("a")) as AddonLinks.Outcome.Ready
        val p = ready.preview
        assertThat(p.manifestUrl).isEqualTo(link("a"))
        assertThat(p.id).isEqualTo("radio")
        assertThat(p.name).isEqualTo("Radio Add-on")
        assertThat(p.version).isEqualTo("1.2.0")
        assertThat(p.description).isEqualTo("Songs from the radio")
        assertThat(p.offers).containsExactly("Plays songs", "Lossless", "Downloads", "Lyrics").inOrder()
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

        assertThat(refusal(links.preview(link("pages")))).isEqualTo("This add-on offers nothing Stash can play yet.")
        assertThat(refusal(links.preview(link("newer")))).isEqualTo("This add-on needs a newer Stash.")
        assertThat(refusal(links.preview(link("junk")))).isEqualTo("That link isn't an add-on.")
        assertThat(refusal(links.preview(link("broken")))).isEqualTo("That add-on's manifest isn't valid.")
        assertThat(refusal(links.preview(link("html")))).isEqualTo("That add-on's manifest isn't valid.")
        assertThat(refusal(links.preview(link("missing")))).isEqualTo("That add-on didn't answer with a manifest.")
        // Nothing listens on port 9.
        assertThat(refusal(links.preview("http://127.0.0.1:9/manifest.json"))).isEqualTo("Couldn't reach that add-on. Check the link and your connection.")
        assertThat(users.loadedEntries()).isEmpty()
    }

    @Test fun `an id that is already on is refused, at the preview and again at Add`() = runBlocking<Unit> {
        val links = links()
        serve("a", manifestJson(id = "radio"))
        val preview = (links.preview(link("a")) as AddonLinks.Outcome.Ready).preview

        registry.state.value = listOf(installed("radio", userAdded = true))
        assertThat(refusal(links.preview(link("a")))).isEqualTo("You already added an add-on with this id (radio).")
        // The id turned up between Check link and Add.
        assertThat(refusal(links.add(preview, null))).isEqualTo("Stash already has an add-on with this id (radio).")

        registry.state.value = listOf(installed("radio", userAdded = false))
        assertThat(refusal(links.preview(link("a")))).isEqualTo("Stash already has an add-on with this id (radio).")
        assertThat(users.loadedEntries()).isEmpty()
    }

    // ------------------------------------------------------------------------------------------ add, remove, the switch

    @Test fun `Add keeps the link sealed, with the token only when the manifest asks for one`() = runBlocking<Unit> {
        val links = links()
        serve("open", manifestJson(id = "open"))
        serve("locked", manifestJson(id = "locked", extra = ""","auth":{"token":"header"}"""))
        val open = (links.preview(link("open")) as AddonLinks.Outcome.Ready).preview
        val locked = (links.preview(link("locked")) as AddonLinks.Outcome.Ready).preview

        // A token typed for an add-on that asked for none is not kept.
        assertThat(links.add(open, "typed-anyway")).isEqualTo(AddonLinks.Outcome.Added)
        // One that asks for a token is not added without it, nor with one a header can't carry.
        assertThat(refusal(links.add(locked, "  "))).isEqualTo("This add-on needs its access token.")
        assertThat(refusal(links.add(locked, "badétoken"))).isEqualTo("That token has characters a request can't carry.")
        assertThat(links.add(locked, " s3cret ")).isEqualTo(AddonLinks.Outcome.Added)

        assertThat(users.loadedEntries()).containsExactly(
            UserAddonEntry(link("open"), token = null, enabled = true),
            UserAddonEntry(link("locked"), token = "s3cret", enabled = true),
        ).inOrder()
        // The same link again: refused before anything is fetched.
        val asked = server.requestCount
        assertThat(refusal(links.preview(link("open")))).isEqualTo("This add-on is already added.")
        assertThat(server.requestCount).isEqualTo(asked)
    }

    @Test fun `the list names each add-on by its loaded manifest, else by its host, never by its link`() = runBlocking<Unit> {
        val links = links()
        serve("tok-123", manifestJson(id = "radio"))
        links.add((links.preview(link("tok-123")) as AddonLinks.Outcome.Ready).preview, null)

        val notLoaded = withTimeout(5_000) { links.added.first { it.size == 1 } }.single()
        assertThat(notLoaded.loaded).isFalse()
        assertThat(notLoaded.name).isEqualTo("127.0.0.1:${server.port}")
        assertThat(notLoaded.toString()).doesNotContain("tok-123")

        registry.state.value = listOf(
            InstalledAddon(
                base = HttpAddonClient.baseOf(link("tok-123"))!!,
                manifest = Manifest(stash = 1, id = "radio", name = "Radio Add-on", version = "1", kind = AddonKind.SERVER, resources = listOf("search")),
                userAdded = true,
            ),
        )
        val loaded = withTimeout(5_000) { links.added.first { it.single().loaded } }.single()
        assertThat(loaded.name).isEqualTo("Radio Add-on")
        assertThat(loaded.id).isEqualTo("radio")
    }

    @Test fun `Remove and the switch change the sealed list and say so`() = runBlocking<Unit> {
        val links = links()
        serve("a", manifestJson(id = "a"))
        serve("b", manifestJson(id = "b"))
        links.add((links.preview(link("a")) as AddonLinks.Outcome.Ready).preview, null)
        links.add((links.preview(link("b")) as AddonLinks.Outcome.Ready).preview, null)

        assertThat(links.setEnabled(link("a"), false)).isTrue()
        assertThat(users.loadedEntries().map { it.manifestUrl to it.enabled }).containsExactly(link("a") to false, link("b") to true).inOrder()
        assertThat(links.remove(link("a"))).isTrue()
        assertThat(users.loadedEntries().map { it.manifestUrl }).containsExactly(link("b"))
    }

    /** review-882 J2: a save that fails must come back as an answer, never as a throw into `viewModelScope.launch`. */
    @Test fun `a save that fails is reported, throws nothing, and changes nothing`() = runBlocking<Unit> {
        val links = links()
        serve("a", manifestJson(id = "a"))
        serve("b", manifestJson(id = "b"))
        links.add((links.preview(link("a")) as AddonLinks.Outcome.Ready).preview, null)
        val b = (links.preview(link("b")) as AddonLinks.Outcome.Ready).preview

        key.failSeals = true
        assertThat(refusal(links.add(b, null))).isEqualTo(AddonLinks.NOT_SAVED)
        assertThat(links.remove(link("a"))).isFalse()
        assertThat(links.setEnabled(link("a"), false)).isFalse()
        assertThat(users.loadedEntries()).containsExactly(UserAddonEntry(link("a")))

        key.failSeals = false
        assertThat(links.add(b, null)).isEqualTo(AddonLinks.Outcome.Added)
        assertThat(users.loadedEntries()).hasSize(2)
    }
}
