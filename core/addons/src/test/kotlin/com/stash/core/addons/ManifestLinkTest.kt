package com.stash.core.addons

import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Descriptor
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.ResolveQuery
import com.stash.addons.contract.StreamCapabilities
import com.stash.addons.contract.Tier
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import java.net.InetAddress

/**
 * A manifest link's two halves (add-ons plan phase 4 §2.2): the base its routes live under, and the
 * manifest's file name, which is `manifest.json` or, for BitChord-style links, any other `*.json`.
 * And the small host-side facts that hang off a manifest: what its server sees, and the refusal codes.
 */
class ManifestLinkTest {

    @Test fun `the base is the link without its file, and the file is manifest json unless the link names another`() {
        assertThat(HttpAddonClient.baseOf("https://relay.example/tok/manifest.json")).isEqualTo("https://relay.example/tok/")
        assertThat(HttpAddonClient.manifestFileOf("https://relay.example/tok/manifest.json")).isEqualTo("manifest.json")
        assertThat(HttpAddonClient.baseOf("https://relay.example/tok/")).isEqualTo("https://relay.example/tok/")
        assertThat(HttpAddonClient.manifestFileOf("https://relay.example/tok/")).isEqualTo("manifest.json")
        // BitChord-style: the manifest under another name.
        assertThat(HttpAddonClient.baseOf("https://relay.example/addons/hifi.json")).isEqualTo("https://relay.example/addons/")
        assertThat(HttpAddonClient.manifestFileOf("https://relay.example/addons/hifi.json")).isEqualTo("hifi.json")
        // Not a manifest link: no file and no final slash, a bare ".json", or not a URL.
        assertThat(HttpAddonClient.baseOf("https://relay.example/tok")).isNull()
        assertThat(HttpAddonClient.baseOf("https://relay.example/.json")).isNull()
        assertThat(HttpAddonClient.baseOf("not a url/")).isNull()
        assertThat(HttpAddonClient.baseOf("https://host.json")).isNull()
    }

    private fun manifest(vararg resources: String) =
        Manifest(stash = 1, id = "x", name = "X", version = "1", kind = AddonKind.SERVER, resources = resources.toList())

    /** [manifest] with what it says about its streams: the tiers it has (null: it doesn't say) and the deliveries it gives. */
    private fun streaming(vararg resources: String, tiers: List<String>? = null, deliveries: List<String> = listOf("none")) =
        manifest(*resources).copy(stream = StreamCapabilities(cors = false, manifests = deliveries, tiers = tiers))

    @Test fun `what a lookup tells a server about a song is what the sheet names - no other fact`() = runBlocking<Unit> {
        // review-885 M8. The six "Its server will see" sentences (`:feature:settings`) say "the name,
        // artist, album, length and recording code". The recording code (ISRC) was sent and not said.
        // If a lookup ever carries another fact about a song, this fails: the sentences come first.
        // The requests as HttpAddonClient sends them, to a server here (round 2, N7: the contract's
        // own helper is not what the client builds its query from).
        val song = Descriptor(title = "Title", artist = "Artist", album = "Album", durationMs = 200_000L, isrc = "GBAAA0000001", explicit = true)
        val saidOfTheSong = listOf("title", "artist", "album", "durationMs", "isrc")
        val server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            val host = object : AddonHostOptions {
                override val allowLocalHttp = true
                override val userAgent = "Stash/9.9.9-test"
            }
            val client = HttpAddonClient(OkHttpClient(), OpenAddonRateGate, NoFirstPartyAddons, UnsignedAddons, host)
            val base = "http://127.0.0.1:${server.port}/tok/"
            fun nothingFound() = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"item":null}""")

            // A Stash add-on: sent besides, and about the request, not the listener, which version to
            // match (clean or explicit) and the lowest quality wanted.
            val v1 = InstalledAddon(base = base, manifest = manifest("resolve", "stream"))
            server.enqueue(nothingFound())
            client.resolve(v1, ResolveQuery(song, minTier = Tier.LOSSLESS))
            assertThat(server.takeRequest().requestUrl!!.queryParameterNames).containsExactlyElementsIn(saidOfTheSong + listOf("explicit", "minTier"))

            // An Eclipse-shaped add-on is sent no more of the song than that.
            val read = AddonValidator.readManifest(
                Json.parseToJsonElement("""{"id":"com.example.e","name":"E","version":"1","resources":["search","stream","resolve"]}"""),
                client.optionsFor(publicOnly = true),
            )
            val eclipse = InstalledAddon(base = base, manifest = read.manifest, userAdded = true, dialect = read.dialect, eclipse = read.eclipse)
            server.enqueue(nothingFound())
            client.resolve(eclipse, ResolveQuery(song, minTier = Tier.LOSSLESS))
            val eclipseSent = server.takeRequest().requestUrl!!.queryParameterNames
            assertThat(eclipseSent).isNotEmpty()
            assertThat(saidOfTheSong).containsAtLeastElementsIn(eclipseSent)
        } finally {
            server.shutdown()
        }
        // Nothing else is known about a song to send.
        val known = Descriptor::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") || it == "Companion" }
        assertThat(known).containsExactlyElementsIn(saidOfTheSong + "explicit")
    }

    @Test fun `what a server is said to see is what Stash will ask it`() {
        val plays = AddonSees.PLAYS
        val next = AddonSees.NEXT_UP
        val saves = AddonSees.SAVES
        // A stream source: asked about each song played and the one up next. And about each song saved
        // when Stash would also take downloads from it, download route or not (review-882 J3).
        assertThat(AddonSees.of(streaming("search", "stream"))).containsExactly(plays, next, saves)
        assertThat(AddonSees.of(streaming("resolve", "stream", tiers = listOf("lossless")))).containsExactly(plays, next, saves)
        assertThat(AddonSees.of(streaming("search", "stream", "download"))).containsExactly(plays, next, saves)
        assertThat(AddonSees.of(streaming("resolve", "stream", deliveries = listOf("none", "dash")))).containsExactly(plays, next, saves)
        // No download is taken from it: lossy files only, or an Eclipse-family manifest that says `allowDownloads: 0`.
        assertThat(AddonSees.of(streaming("resolve", "stream", tiers = listOf("high")))).containsExactly(plays, next)
        assertThat(AddonSees.of(streaming("search", "stream"), allowsDownloads = false)).containsExactly(plays, next)
        assertThat(AddonSees.of(AddonManifest(streaming("search", "stream"), AddonDialect.ECLIPSE, EclipseTraits(allowDownloads = false))))
            .containsExactly(plays, next)
        // Downloads only: asked about what is saved, never on a play.
        assertThat(AddonSees.of(streaming("resolve", "download"))).containsExactly(saves)
        // Lyrics: asked for the song that is playing, and for a song once it is downloaded; never for the one up next.
        assertThat(AddonSees.of(manifest("lyrics"))).containsExactly(plays, saves)
        assertThat(AddonSees.of(streaming("lyrics", "search", "stream"), allowsDownloads = false)).containsExactly(plays, next, saves)
        // Never asked about a song: pages only, a stream with no way to find a song, a delivery the
        // player doesn't take, nothing said about its streams.
        assertThat(AddonSees.of(manifest("search", "album", "artist"))).isEmpty()
        assertThat(AddonSees.of(streaming("stream"))).isEmpty()
        assertThat(AddonSees.of(streaming("resolve", "stream", deliveries = listOf("hls")))).isEmpty()
        assertThat(AddonSees.of(manifest("resolve", "stream"))).isEmpty()
        // Listing `download` is not what decides: with lossy files only, Stash asks it for none to keep.
        assertThat(AddonSees.of(streaming("resolve", "download", tiers = listOf("high")))).isEmpty()
        assertThat(AddonSees.of(streaming("resolve", "download", tiers = listOf("high", "lossless")))).containsExactly(saves)
        // The names kept with the user's agreement.
        assertThat(AddonSees.namesOf(AddonManifest(streaming("search", "stream")))).containsExactly("PLAYS", "NEXT_UP", "SAVES")
        assertThat(AddonSees.namesOf(streaming("resolve", "stream", tiers = listOf("high")))).containsExactly("PLAYS", "NEXT_UP")
    }

    @Test fun `the refusals a host acts on are codes, and only an exact reason is one`() {
        fun code(reason: String) = AddonFailure.InvalidResponse("/x", reason).code
        assertThat(code(AddonValidator.NEEDS_NEWER_STASH)).isEqualTo(AddonFailure.InvalidResponse.Code.NEEDS_NEWER_STASH)
        assertThat(code(AddonValidator.NOT_AN_ADDON)).isEqualTo(AddonFailure.InvalidResponse.Code.NOT_AN_ADDON)
        assertThat(code(AddonEclipse.ONLY_BITCHORD)).isEqualTo(AddonFailure.InvalidResponse.Code.ONLY_BITCHORD)
        assertThat(code(AddonEclipse.PRIVATE_ADDRESS)).isEqualTo(AddonFailure.InvalidResponse.Code.PRIVATE_ADDRESS)
        // Text an add-on could put in its own answer never passes for one.
        assertThat(code("This add-on needs a newer Stash, honest")).isNull()
        assertThat(code("expected a lowercase id")).isNull()
    }

    @Test fun `trust follows where an add-on came from unless the signed lists say otherwise`() {
        val m = manifest("lyrics")
        assertThat(InstalledAddon("https://a.example/", m, userAdded = true).trust).isEqualTo(Trust.UNOFFICIAL)
        assertThat(InstalledAddon("https://a.example/", m).trust).isEqualTo(Trust.OFFICIAL)
        assertThat(InstalledAddon("https://a.example/", m, firstParty = true).trust).isEqualTo(Trust.FIRST_PARTY)
        // One the user added from the official list.
        assertThat(InstalledAddon("https://a.example/", m, userAdded = true, trust = Trust.OFFICIAL).trust).isEqualTo(Trust.OFFICIAL)
    }
}
