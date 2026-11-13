package com.stash.data.download.lossless.addon

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.StreamCapabilities
import com.stash.core.addons.AddonSees
import com.stash.core.addons.EclipseTraits
import com.stash.core.addons.InstalledAddon
import com.stash.data.download.lossless.tidal.FixedRegistry
import io.mockk.mockk
import org.junit.Test

/**
 * "…and of each song you save" on the sheet and the page, and the "Downloads" use, are decided by
 * [AddonSees.keeps]. Whether a download really asks the add-on is decided by the download chain's
 * own rule, [AddonDownloadSources.canDownloadLossless]. They are two hand-written copies of one
 * rule (review-885, Tests): this holds them to each other over every shape of manifest, so the
 * sentence can never drift from what Stash asks.
 */
class AddonSeesKeepsTest {

    private val resources = listOf(
        emptyList(), listOf("resolve"), listOf("search"), listOf("stream"), listOf("download"), listOf("lyrics"),
        listOf("resolve", "stream"), listOf("search", "stream"), listOf("resolve", "download"), listOf("search", "download"),
        listOf("resolve", "search", "stream", "download", "lyrics", "quota"), listOf("stream", "download"), listOf("resolve", "lyrics"),
    )
    private val deliveries = listOf(
        null, listOf("none"), listOf("dash"), listOf("hls"), listOf("none", "hls"), listOf("hls", "dash"), listOf("later-word"), emptyList(),
    )
    private val tiers = listOf(
        null, emptyList(), listOf("lossless"), listOf("hires"), listOf("high"), listOf("low", "high"), listOf("high", "lossless"), listOf("later-word"),
    )

    private fun manifest(resources: List<String>, stream: StreamCapabilities?) =
        Manifest(stash = 1, id = "mine", name = "Mine", version = "1", kind = AddonKind.SERVER, resources = resources, stream = stream)

    private fun manifests(): List<Manifest> = buildList {
        for (r in resources) {
            add(manifest(r, null))
            for (d in deliveries) for (t in tiers) add(manifest(r, StreamCapabilities(cors = false, manifests = d, tiers = t)))
        }
    }

    @Test fun `what the user is told an add-on is asked to save is what the download chain asks it`() {
        var yes = 0
        var no = 0
        for (m in manifests()) for (allows in listOf(true, false)) {
            val what = "resources=${m.resources} stream=${m.stream} allowsDownloads=$allows"
            val addon = InstalledAddon(base = "https://mine.example/", manifest = m, userAdded = true, eclipse = EclipseTraits(allowDownloads = allows))
            val told = AddonSees.keeps(m, allowsDownloads = allows)
            assertWithMessage(what).that(told).isEqualTo(AddonDownloadSources.canDownloadLossless(addon))
            // The chain itself: it has a source for the add-on exactly then.
            val sources = AddonDownloadSources(FixedRegistry(listOf(addon)), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)).sources()
            assertWithMessage("a source for $what").that(sources.isNotEmpty()).isEqualTo(told)
            // And the two places that say it: "save" among what its server sees (lyrics are fetched
            // for a saved song too), and the Downloads use.
            assertWithMessage("SAVES for $what").that(AddonSees.SAVES in AddonSees.of(m, allows)).isEqualTo(told || "lyrics" in m.resources)
            assertWithMessage("Downloads for $what").that(AddonLinks.Use.DOWNLOADS in AddonLinks.uses(m, allowsDownloads = allows)).isEqualTo(told)
            if (told) yes++ else no++
        }
        // The table really has both kinds.
        assertThat(yes).isGreaterThan(20)
        assertThat(no).isGreaterThan(20)
    }
}
