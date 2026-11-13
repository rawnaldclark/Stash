package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.StreamCapabilities
import com.stash.core.addons.InstalledAddon
import com.stash.data.download.lossless.addon.AddonLosslessSource
import com.stash.data.download.lossless.tidal.FixedRegistry
import org.junit.Test

/**
 * A match the retry sweep found is kept ten minutes for the song's download. One that an add-on
 * found is handed over only while that add-on is still on (review-885 M6): the download would
 * otherwise fetch from a host the add-on chose after it was removed, switched off or blocked.
 */
class ResolvedSourceHandoffTest {

    private fun addon(id: String) = InstalledAddon(
        base = "https://$id.example/",
        manifest = Manifest(
            stash = 1, id = id, name = id, version = "1", kind = AddonKind.SERVER, resources = listOf("resolve", "download"),
            stream = StreamCapabilities(cors = false, manifests = listOf("none"), tiers = listOf("lossless")),
        ),
        userAdded = true,
    )

    private fun match(sourceId: String) = SourceResult(
        sourceId = sourceId,
        downloadUrl = "https://cdn.example/a.flac",
        format = AudioFormat(codec = "flac", bitrateKbps = 1000),
        confidence = 0.95f,
    )

    private val lossless = ResolvedSourceHandoff.Kind.LOSSLESS

    @Test fun `an add-on's match is handed over while the add-on is on`() {
        val registry = FixedRegistry(listOf(addon("mine")))
        val handoff = ResolvedSourceHandoff(registry)
        val found = match(AddonLosslessSource.sourceId("mine"))
        handoff.put(1L, found, lossless)
        assertThat(handoff.take(1L, lossless)).isEqualTo(found)
        // Once.
        assertThat(handoff.take(1L, lossless)).isNull()
    }

    @Test fun `an add-on's match is dropped once the add-on is removed, switched off or blocked`() {
        // All three leave the list of add-ons that are on.
        val registry = FixedRegistry(listOf(addon("mine"), addon("other")))
        val handoff = ResolvedSourceHandoff(registry)
        handoff.put(1L, match(AddonLosslessSource.sourceId("mine")), lossless)
        handoff.put(2L, match(AddonLosslessSource.sourceId("other")), lossless)
        registry.state.value = listOf(addon("other"))
        assertThat(handoff.take(1L, lossless)).isNull()
        // Dropped for good: back on a moment later, the download still asks afresh.
        registry.state.value = listOf(addon("mine"), addon("other"))
        assertThat(handoff.take(1L, lossless)).isNull()
        // The add-on that stayed on keeps its match.
        assertThat(handoff.take(2L, lossless)).isNotNull()
    }

    @Test fun `a match from Stash's own sources never depends on the add-ons`() {
        val handoff = ResolvedSourceHandoff(FixedRegistry())
        val found = match("squid_qobuz")
        handoff.put(1L, found, lossless)
        assertThat(handoff.take(1L, lossless)).isEqualTo(found)
        val aac = match("tidal")
        handoff.put(2L, aac, ResolvedSourceHandoff.Kind.TIDAL_AAC)
        assertThat(handoff.take(2L, ResolvedSourceHandoff.Kind.TIDAL_AAC)).isEqualTo(aac)
    }
}
