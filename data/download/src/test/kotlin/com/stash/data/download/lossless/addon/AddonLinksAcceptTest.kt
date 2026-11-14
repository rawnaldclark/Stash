package com.stash.data.download.lossless.addon

import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.StreamCapabilities
import org.junit.Test

/** What Add from a link accepts, what its preview names (add-ons plan phase 4 §2.4), and how a link comes in (§2.2). */
class AddonLinksAcceptTest {

    private fun manifest(resources: List<String>, tiers: List<String>? = null) = Manifest(
        stash = 1, id = "x", name = "X", version = "1", kind = AddonKind.SERVER, resources = resources,
        stream = StreamCapabilities(cors = false, manifests = listOf("none"), tiers = tiers),
    )

    @Test fun `songs to play or keep, found by search or resolve, or lyrics, are accepted`() {
        assertThat(AddonLinks.worthAdding(manifest(listOf("search", "stream")))).isTrue()
        assertThat(AddonLinks.worthAdding(manifest(listOf("resolve", "stream")))).isTrue()
        assertThat(AddonLinks.worthAdding(manifest(listOf("search", "download")))).isTrue()
        assertThat(AddonLinks.worthAdding(manifest(listOf("lyrics")))).isTrue()
    }

    @Test fun `pages only, or a way to play with no way to find a song, is not`() {
        assertThat(AddonLinks.worthAdding(manifest(listOf("search", "album", "artist", "catalog")))).isFalse()
        assertThat(AddonLinks.worthAdding(manifest(listOf("stream")))).isFalse()
        assertThat(AddonLinks.worthAdding(manifest(listOf("resolve")))).isFalse()
    }

    @Test fun `the preview names only what Stash uses`() {
        val plays = AddonLinks.Use.PLAYS_SONGS
        val lossless = AddonLinks.Use.LOSSLESS
        val downloads = AddonLinks.Use.DOWNLOADS
        assertThat(AddonLinks.uses(manifest(listOf("search", "stream", "album", "catalog"), tiers = listOf("high", "lossless"))))
            .containsExactly(plays, lossless, downloads).inOrder()
        // Lossy only: it plays, but a download is a lossless file, which it can't hand over.
        assertThat(AddonLinks.uses(manifest(listOf("search", "stream"), tiers = listOf("high")))).containsExactly(plays)
        // It doesn't say which tiers it has: a download is asked for, and refused later if it isn't lossless.
        assertThat(AddonLinks.uses(manifest(listOf("search", "stream")))).containsExactly(plays, downloads).inOrder()
        // BitChord's allowDownloads: 0.
        assertThat(AddonLinks.uses(manifest(listOf("search", "stream")), allowsDownloads = false)).containsExactly(plays)
        assertThat(AddonLinks.uses(manifest(listOf("lyrics")))).containsExactly(AddonLinks.Use.LYRICS)
    }

    @Test fun `the server is said to see what you save exactly when Downloads or Lyrics is shown`() {
        // One condition for the "Downloads" use, the sheet's "and save" and the page's: seen apart on a phone
        // (a page without "save" for an add-on whose page showed Downloads). Lyrics say "save" too: they
        // are fetched for a song once it is downloaded.
        val shapes = listOf(
            manifest(listOf("search", "stream")),
            manifest(listOf("resolve", "stream"), tiers = listOf("lossless")),
            manifest(listOf("search", "resolve", "stream", "download"), tiers = listOf("high", "hires")),
            manifest(listOf("search", "download")),
            manifest(listOf("search", "stream"), tiers = listOf("high")),
            manifest(listOf("resolve", "download"), tiers = listOf("low", "high")),
            manifest(listOf("stream")),
            manifest(listOf("download")),
            manifest(listOf("lyrics")),
            manifest(listOf("lyrics", "search", "stream"), tiers = listOf("high")),
            manifest(listOf("search", "stream")).copy(stream = StreamCapabilities(cors = false, manifests = listOf("hls"))),
            manifest(listOf("search", "stream")).copy(stream = null),
        )
        for (m in shapes) for (allowed in listOf(true, false)) {
            val uses = AddonLinks.uses(m, allowsDownloads = allowed)
            val saves = com.stash.core.addons.AddonSees.SAVES in com.stash.core.addons.AddonSees.of(m, allowsDownloads = allowed)
            assertThat(saves).isEqualTo(AddonLinks.Use.DOWNLOADS in uses || AddonLinks.Use.LYRICS in uses)
        }
        // And both are on for the add-on seen on the phone: search, resolve and stream, no download route.
        val seen = manifest(listOf("search", "resolve", "stream"))
        assertThat(AddonLinks.uses(seen)).contains(AddonLinks.Use.DOWNLOADS)
        assertThat(com.stash.core.addons.AddonSees.of(seen)).contains(com.stash.core.addons.AddonSees.SAVES)
    }

    // ------------------------------------------------------------------------------------------ a link in (phase 4 §2.2)

    private fun url(link: String, local: Boolean = false) = (AddonLinks.normalise(link, allowLocalHttp = local) as? AddonLinks.Normalised.Url)?.url

    private fun refused(link: String, local: Boolean = false) = (AddonLinks.normalise(link, allowLocalHttp = local) as? AddonLinks.Normalised.No)?.reason

    @Test fun `the root, a manifest json, or any other json all name a manifest`() {
        assertThat(url("https://relay.example.org")).isEqualTo("https://relay.example.org/manifest.json")
        assertThat(url("https://relay.example.org/")).isEqualTo("https://relay.example.org/manifest.json")
        assertThat(url("https://relay.example.org/manifest.json")).isEqualTo("https://relay.example.org/manifest.json")
        // BitChord-style links name the file anything.
        assertThat(url("https://relay.example.org/addons/hifi.json")).isEqualTo("https://relay.example.org/addons/hifi.json")
        // LastWave writes its bases with a final /manifest.
        assertThat(url("https://relay.example.org/a/tok123/manifest")).isEqualTo("https://relay.example.org/a/tok123/manifest.json")
        // The path is kept, so a token in it survives; so is its case.
        assertThat(url("https://relay.example.org/v1/AbC-123_x/")).isEqualTo("https://relay.example.org/v1/AbC-123_x/manifest.json")
        assertThat(url("  https://relay.example.org/v1/tok  ")).isEqualTo("https://relay.example.org/v1/tok/manifest.json")
    }

    @Test fun `https is added when no scheme was typed, and only the path's last part is ever dropped`() {
        assertThat(url("relay.example.org/v1/tok")).isEqualTo("https://relay.example.org/v1/tok/manifest.json")
        assertThat(url("HTTPS://relay.example.org/x/")).isEqualTo("https://relay.example.org/x/manifest.json")
        // A host that happens to be called "manifest" keeps its name.
        assertThat(url("https://manifest.example.org")).isEqualTo("https://manifest.example.org/manifest.json")
    }

    @Test fun `what is not an add-on link says why`() {
        // A token belongs in the path: a query string or a fragment is refused.
        assertThat(refused("https://relay.example.org/manifest.json?token=abc")).isEqualTo(AddonLinks.Refusal.NOT_AN_ADDON)
        assertThat(refused("https://relay.example.org/#frag")).isEqualTo(AddonLinks.Refusal.NOT_AN_ADDON)
        assertThat(refused("")).isEqualTo(AddonLinks.Refusal.NOT_AN_ADDON)
        assertThat(refused("two words")).isEqualTo(AddonLinks.Refusal.NOT_AN_ADDON)
        assertThat(refused("http://relay.example.org/manifest.json")).isEqualTo(AddonLinks.Refusal.NOT_HTTPS)
        // The base rules: no address on the user's own network.
        for (local in listOf(
            "https://192.168.1.10/manifest.json", "https://10.0.0.1/", "https://localhost/manifest.json", "https://router.lan/",
            "https://nas.local/manifest.json", "https://intranet/manifest.json",
        )) {
            assertThat(refused(local)).isEqualTo(AddonLinks.Refusal.LOCAL_ADDRESS)
        }
        // An address in brackets is no host an add-on can have at all.
        assertThat(refused("https://[::1]/manifest.json")).isNotNull()
    }

    @Test fun `a host written with a final dot is refused - it would walk past a block and past already added`() {
        // review-885 M4: the network takes "bad.example." for "bad.example"; a block's origin and the
        // links the user already has are compared as written.
        for (dotted in listOf(
            "https://bad.example./manifest.json",
            "https://bad.example.:8443/v1/tok/manifest.json",
            "bad.example./",
            "https://bad.example.",
        )) {
            assertThat(refused(dotted)).isEqualTo(AddonLinks.Refusal.NOT_AN_ADDON)
        }
        // A dot inside the path, or before the file's ending, is nobody's host.
        assertThat(url("https://relay.example/v1.2/manifest.json")).isEqualTo("https://relay.example/v1.2/manifest.json")
    }

    @Test fun `a debug build also takes its own local test servers, and nothing else local`() {
        assertThat(url("http://127.0.0.1:8798/manifest.json", local = true)).isEqualTo("http://127.0.0.1:8798/manifest.json")
        assertThat(url("http://127.0.0.1:8798", local = true)).isEqualTo("http://127.0.0.1:8798/manifest.json")
        assertThat(refused("https://192.168.1.10/manifest.json", local = true)).isEqualTo(AddonLinks.Refusal.LOCAL_ADDRESS)
        assertThat(refused("http://127.0.0.1:8798/manifest.json", local = false)).isNotNull()
    }

    @Test fun `the same link typed two ways is one add-on`() {
        assertThat(AddonLinks.sameLink("https://Relay.Example.org/v1/tok/manifest.json", "https://relay.example.org:443/v1/tok/manifest.json")).isTrue()
        assertThat(AddonLinks.sameLink("https://relay.example.org/v1/tok/", "https://relay.example.org/v1/tok/manifest.json")).isTrue()
        // The path is the add-on: its case matters, and another file is another manifest.
        assertThat(AddonLinks.sameLink("https://relay.example.org/v1/TOK/manifest.json", "https://relay.example.org/v1/tok/manifest.json")).isFalse()
        assertThat(AddonLinks.sameLink("https://relay.example.org/a.json", "https://relay.example.org/b.json")).isFalse()
        assertThat(AddonLinks.sameLink("https://relay.example.org:8443/manifest.json", "https://relay.example.org/manifest.json")).isFalse()
    }

    @Test fun `a share is an add-on's only when it holds a manifest link`() {
        assertThat(AddonLinkDetector.find("Add this: https://relay.example.org/v1/tok/manifest.json.")).isEqualTo("https://relay.example.org/v1/tok/manifest.json")
        assertThat(AddonLinkDetector.find("https://relay.example.org/addons/hifi.json")).isEqualTo("https://relay.example.org/addons/hifi.json")
        // Anything else stays what it was: a page, a song link, a link with a query, plain http.
        assertThat(AddonLinkDetector.find("https://relay.example.org/setup")).isNull()
        assertThat(AddonLinkDetector.find("https://example.org/")).isNull()
        assertThat(AddonLinkDetector.find("https://relay.example.org/manifest.json?token=1")).isNull()
        assertThat(AddonLinkDetector.find("http://relay.example.org/manifest.json")).isNull()
        assertThat(AddonLinkDetector.find("no link here")).isNull()
        assertThat(AddonLinkDetector.find(null)).isNull()
    }

    @Test fun `every block reason has its own refusal`() {
        assertThat(com.stash.core.addons.BlockReason.entries.map(AddonLinks::blocked)).containsExactly(
            AddonLinks.Refusal.BLOCKED_HARMFUL, AddonLinks.Refusal.BLOCKED_BROKEN, AddonLinks.Refusal.BLOCKED_IMPERSONATION,
            // A reason from a later list: refused all the same, said without one.
            AddonLinks.Refusal.BLOCKED,
        ).inOrder()
    }
}
