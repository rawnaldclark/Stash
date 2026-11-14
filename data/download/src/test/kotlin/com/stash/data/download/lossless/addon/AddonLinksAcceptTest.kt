package com.stash.data.download.lossless.addon

import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.StreamCapabilities
import org.junit.Test

/** What Add from a link accepts, and what its preview names (add-ons plan phase 4 §2.4). */
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
        assertThat(AddonLinks.uses(manifest(listOf("search", "stream", "album", "catalog"), tiers = listOf("high", "lossless"))))
            .containsExactly("Plays songs", "Lossless", "Downloads").inOrder()
        assertThat(AddonLinks.uses(manifest(listOf("search", "stream"), tiers = listOf("high"))))
            .containsExactly("Plays songs", "Downloads").inOrder()
        // BitChord's allowDownloads: 0.
        assertThat(AddonLinks.uses(manifest(listOf("search", "stream")), allowsDownloads = false)).containsExactly("Plays songs")
        assertThat(AddonLinks.uses(manifest(listOf("lyrics")))).containsExactly("Lyrics")
    }
}
