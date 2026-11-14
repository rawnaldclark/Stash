package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Codec
import com.stash.addons.contract.Delivery
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.StreamCapabilities
import com.stash.addons.contract.StreamInfo
import com.stash.addons.contract.Tier
import com.stash.core.addons.AddonRegistry
import com.stash.core.addons.InstalledAddon
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.addon.AddonRefs
import io.mockk.coEvery
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

    @Test fun `an add-on set aside for a wrong file is no rung in either pass until its cooldown ends`() {
        // The player refused its stream (AddonStreamCheck), or a download its file: the next resolve
        // must not ask it again, or the song it was refused for would be handed the same file.
        var now = 0L
        val gate = com.stash.data.download.lossless.LosslessSourceHealthGate { now }
        val rungs = AddonStreamRungs(Registry(listOf(addon("liar", firstParty = false), addon("honest", firstParty = false))), mockk(), gate)
        gate.recordWrongFile("addon:liar")
        assertThat(rungs.rungs().map { it.addon.id }).containsExactly("honest")
        assertThat(rungs.lossyRungs().map { it.addon.id }).containsExactly("honest")
        now += com.stash.data.download.lossless.LosslessSourceHealthGate.COOLDOWN_MS
        assertThat(rungs.rungs().map { it.addon.id }).containsExactly("liar", "honest").inOrder()
    }

    @Test fun `a stream carries the song's length for the player's check - the resolve's own, else the add-on's matched item's - #888 3b`() =
        kotlinx.coroutines.test.runTest {
            val info = StreamInfo(url = "https://cdn.example/a.flac", manifest = Delivery.NONE, tier = Tier.LOSSLESS, cors = false, codec = Codec.FLAC)
            var matched: Long? = 222_640L
            val refs = mockk<AddonRefs> {
                coEvery { stream(any(), any(), any(), any(), any(), any(), any()) } answers {
                    AddonRefs.Streamed.Ok(info, AddonRefs.Ref("r1", null, null, 0.9f, durationMs = matched))
                }
            }
            val rung = AddonStreamRungs(Registry(listOf(addon("liar", firstParty = false))), refs).rungs().single()
            val tapped = TrackEntity(id = 1L, title = "Feel Good Inc.", artist = "Gorillaz") // a Search result with no length

            // The add-on's own matched item's length, when the song the resolve asked for had none.
            assertThat(rung.resolve(tapped, null)?.songMs).isEqualTo(222_640L)
            // A placeholder resolved from its library row asks with the row's: that one.
            assertThat(rung.resolve(tapped.copy(durationMs = 222_000L), null)?.songMs).isEqualTo(222_000L)
            // A ref read back from the table states none: unknown.
            matched = null
            assertThat(rung.resolve(tapped, null)?.songMs).isNull()
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
