package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.StreamCapabilities
import com.stash.core.addons.AddonRegistry
import com.stash.core.addons.AddonSees
import com.stash.core.addons.InstalledAddon
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Test

/**
 * "…of each song you play and of the next song in your queue" on the sheet and the page is decided
 * by [AddonSees.streams]. Whether a play really asks the add-on is decided by the stream chain's
 * own rule (`AddonStreamRungs.canStream`, behind the rung lists of its two passes). They are two
 * hand-written copies of one rule (review-885, Tests): this holds them to each other over every
 * shape of manifest, so the sentence can never drift from what Stash asks.
 */
class AddonSeesStreamsTest {

    private class Registry(list: List<InstalledAddon>) : AddonRegistry {
        override val addons: StateFlow<List<InstalledAddon>> = MutableStateFlow(list)
    }

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

    @Test fun `what the user is told an add-on is asked to play is what the stream chain asks it`() {
        var yes = 0
        var no = 0
        for (m in manifests()) {
            val what = "resources=${m.resources} stream=${m.stream}"
            val addon = InstalledAddon(base = "https://mine.example/", manifest = m, userAdded = true)
            val rungs = AddonStreamRungs(Registry(listOf(addon)), mockk(relaxed = true))
            // Asked in the lossless pass or in the lossy one: either way a play reaches its server.
            val asked = rungs.rungs().isNotEmpty() || rungs.lossyRungs().isNotEmpty()
            val told = AddonSees.streams(m)
            assertWithMessage(what).that(told).isEqualTo(asked)
            // And what is said: a play, and the look-ahead for the next song (lyrics are fetched for
            // a played song too).
            val sees = AddonSees.of(m)
            assertWithMessage("NEXT_UP for $what").that(AddonSees.NEXT_UP in sees).isEqualTo(told)
            assertWithMessage("PLAYS for $what").that(AddonSees.PLAYS in sees).isEqualTo(told || "lyrics" in m.resources)
            if (told) yes++ else no++
        }
        // The table really has both kinds.
        assertThat(yes).isGreaterThan(20)
        assertThat(no).isGreaterThan(20)
    }
}
