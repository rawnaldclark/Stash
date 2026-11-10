package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Codec
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.StreamCapabilities
import com.stash.core.addons.AddonRegistry
import com.stash.core.addons.InstalledAddon
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Test

/** Add-ons phase 2 §5.6: the generic rungs leave the Tidal add-on to the Tidal rung, and name lossy codecs as such. */
class AddonStreamRungsPhase2Test {

    private class Registry(list: List<InstalledAddon>) : AddonRegistry {
        override val addons: StateFlow<List<InstalledAddon>> = MutableStateFlow(list)
    }

    private fun addon(id: String, firstParty: Boolean) = InstalledAddon(
        base = "https://$id.example/",
        manifest = Manifest(
            stash = 1, id = id, name = id, version = "1", kind = AddonKind.SERVER,
            resources = listOf("search", "resolve", "stream"),
            stream = StreamCapabilities(cors = false, manifests = listOf("none", "dash"), tiers = listOf("high", "lossless", "hires")),
        ),
        firstParty = firstParty,
    )

    @Test fun `the first-party Tidal add-on is never a generic rung`() {
        val rungs = AddonStreamRungs(Registry(listOf(addon("tidal", firstParty = true), addon("other", firstParty = false))), mockk()).rungs()
        assertThat(rungs.map { it.addon.id }).containsExactly("other")
    }

    @Test fun `a third-party add-on that calls itself tidal is still a generic rung`() {
        val rungs = AddonStreamRungs(Registry(listOf(addon("tidal", firstParty = false))), mockk()).rungs()
        assertThat(rungs.map { it.addon.id }).containsExactly("tidal")
    }

    @Test fun `lossy codecs keep their names`() {
        assertThat(AddonStreamRung.codecName(Codec.AAC)).isEqualTo("aac")
        assertThat(AddonStreamRung.codecName(Codec.HE_AAC)).isEqualTo("aac")
        assertThat(AddonStreamRung.codecName(Codec.MP3)).isEqualTo("mp3")
        assertThat(AddonStreamRung.codecName(Codec.OPUS)).isEqualTo("opus")
        assertThat(AddonStreamRung.codecName(Codec.ALAC)).isEqualTo("alac")
        assertThat(AddonStreamRung.codecName(Codec.FLAC)).isEqualTo("flac")
        assertThat(AddonStreamRung.codecName(null)).isEqualTo("flac")
    }
}
