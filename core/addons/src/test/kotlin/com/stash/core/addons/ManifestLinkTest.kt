package com.stash.core.addons

import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.StreamCapabilities
import org.junit.Test

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

    @Test fun `what a server sees is read from what its manifest lists`() {
        assertThat(AddonSees.of(manifest("search", "stream", "download"))).containsExactly(AddonSees.SEARCHES, AddonSees.PLAYS, AddonSees.SAVES)
        assertThat(AddonSees.of(manifest("resolve", "stream"))).containsExactly(AddonSees.PLAYS)
        assertThat(AddonSees.of(manifest("search"))).containsExactly(AddonSees.SEARCHES)
        // Lyrics are asked for the song that is playing: its server sees that too.
        assertThat(AddonSees.of(manifest("lyrics"))).containsExactly(AddonSees.PLAYS)
        assertThat(AddonSees.of(manifest("album", "artist"))).isEmpty()
        assertThat(AddonSees.namesOf(manifest("search", "download"))).containsExactly("SEARCHES", "SAVES")
    }

    /** [manifest] with what it says about its streams: the tiers it has (null: it doesn't say) and the deliveries it gives. */
    private fun streaming(vararg resources: String, tiers: List<String>? = null, deliveries: List<String> = listOf("none")) =
        manifest(*resources).copy(stream = StreamCapabilities(cors = false, manifests = deliveries, tiers = tiers))

    @Test fun `a server Stash would take downloads from sees what you save, with or without a download route`() {
        // review-882 J3: a download from an add-on with no `/download` goes through its `/resolve` and
        // `/stream`, so it learns of songs you save and never played. The sheet must say "and save".
        val plays = AddonSees.PLAYS
        val saves = AddonSees.SAVES
        assertThat(AddonSees.of(streaming("resolve", "stream", tiers = listOf("lossless")))).containsExactly(plays, saves)
        assertThat(AddonSees.of(streaming("search", "stream"))).containsExactly(AddonSees.SEARCHES, plays, saves)
        assertThat(AddonSees.of(streaming("resolve", "stream", deliveries = listOf("none", "dash")))).containsExactly(plays, saves)
        // Never a download source, so nothing is saved from it: lossy only, a delivery the downloader
        // doesn't take, no way to find a song, or an Eclipse-family manifest that says `allowDownloads: 0`.
        assertThat(AddonSees.of(streaming("resolve", "stream", tiers = listOf("high")))).containsExactly(plays)
        assertThat(AddonSees.of(streaming("resolve", "stream", deliveries = listOf("hls")))).containsExactly(plays)
        assertThat(AddonSees.of(streaming("stream"))).containsExactly(plays)
        assertThat(AddonSees.of(streaming("search", "stream"), allowsDownloads = false)).containsExactly(AddonSees.SEARCHES, plays)
        assertThat(AddonSees.of(AddonManifest(streaming("search", "stream"), AddonDialect.ECLIPSE, EclipseTraits(allowDownloads = false))))
            .containsExactly(AddonSees.SEARCHES, plays)
        assertThat(AddonSees.namesOf(AddonManifest(streaming("search", "stream")))).containsExactly("SEARCHES", "PLAYS", "SAVES")
        // Listing `download` is seeing what you save, whatever else it says.
        assertThat(AddonSees.of(streaming("resolve", "download", tiers = listOf("high")))).containsExactly(plays, saves)
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
