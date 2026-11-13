package com.stash.core.addons

import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Codec
import com.stash.addons.contract.Tier
import kotlinx.serialization.json.Json
import org.junit.Assert.assertThrows
import org.junit.Test

/** Stash Sources v0 read as v1 (SPEC.md 10.1), and the manifest versions a host reads. */
class AddonV0Test {

    private fun parse(s: String) = Json.parseToJsonElement(s)

    private val v0 = """{"stash":0,"id":"tidal","name":"Tidal","version":"0.4.1","runtime":"remote","resources":["search","resolve","stream"],"stream":{"cors":false}}"""

    @Test fun `a v0 remote manifest is a v1 server with progressive streams`() {
        val m = AddonValidator.anyManifest(parse(v0))
        assertThat(m.stash).isEqualTo(0)
        assertThat(m.kind).isEqualTo(AddonKind.SERVER)
        assertThat(m.resources).containsExactly("search", "resolve", "stream").inOrder()
        assertThat(m.stream!!.cors).isFalse()
        assertThat(m.stream!!.manifests).containsExactly("none")
    }

    @Test fun `v0 rules still apply`() {
        assertThrows(AddonContractException::class.java) { AddonValidator.anyManifest(parse(v0.replace("\"remote\"", "\"cloud\""))) }
        assertThrows(AddonContractException::class.java) { AddonValidator.anyManifest(parse(v0.replace("\"stream\"]", "\"stream\",\"quota\"]"))) }
        assertThrows(AddonContractException::class.java) { AddonValidator.anyManifest(parse(v0.replace("\"tidal\"", "\"Tidal Plugin\""))) }
    }

    @Test fun `v0, v1 and Eclipse-family manifests are read, and nothing else`() {
        val newer = assertThrows(AddonContractException::class.java) { AddonValidator.anyManifest(parse(v0.replace("\"stash\":0", "\"stash\":2"))) }
        assertThat(newer.reason).contains("newer Stash")
        // Phase 4 §2.3: no `stash`, an id and search or stream is an Eclipse-family add-on.
        val eclipse = AddonValidator.readManifest(parse("""{"id":"x","name":"X","version":"1","resources":["search"]}"""))
        assertThat(eclipse.dialect).isEqualTo(AddonDialect.ECLIPSE)
        val none = assertThrows(AddonContractException::class.java) { AddonValidator.anyManifest(parse("""{"id":"x","name":"X","version":"1","resources":["lyrics"]}""")) }
        assertThat(none.reason).contains("not a Stash add-on")
    }

    @Test fun `stream tiers are derived, never over-claimed`() {
        fun tier(body: String) = AddonV0.streamInfo(parse(body)).tier
        assertThat(tier("""{"url":"https://a.example/1","cors":true,"mime":"audio/flac","bitDepth":16,"sampleRate":44100}""")).isEqualTo(Tier.LOSSLESS)
        assertThat(tier("""{"url":"https://a.example/1","cors":true,"mime":"audio/flac","sampleRate":96000}""")).isEqualTo(Tier.HIRES)
        assertThat(tier("""{"url":"https://a.example/1","cors":true,"mime":"audio/mpeg","bitrate":320000}""")).isEqualTo(Tier.HIGH)
        assertThat(tier("""{"url":"https://a.example/1","cors":true,"mime":"audio/webm; codecs=\"opus\"","bitrate":160000}""")).isEqualTo(Tier.LOW)
        // No mime: nothing says it is lossless.
        assertThat(tier("""{"url":"https://a.example/1","cors":true,"bitDepth":24}""")).isEqualTo(Tier.LOW)
        assertThat(AddonV0.codecOf("audio/mp4; codecs=\"mp4a.40.2\"")).isEqualTo(Codec.AAC)
        assertThat(AddonV0.codecOf("audio/wav")).isEqualTo(Codec.PCM)
    }

    @Test fun `fractional numbers are rounded, and the URL rule holds`() {
        val s = AddonV0.streamInfo(parse("""{"url":"https://a.example/1","cors":true,"bitrate":319999.6,"expiresAt":1893456000.4}"""))
        assertThat(s.bitrate).isEqualTo(320_000)
        assertThat(s.expiresAt).isEqualTo(1_893_456_000L)
        assertThrows(AddonContractException::class.java) { AddonV0.streamInfo(parse("""{"url":"http://a.example/1","cors":true}""")) }
    }
}
