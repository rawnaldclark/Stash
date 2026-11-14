package com.stash.core.addons

import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.Codec
import com.stash.addons.contract.Delivery
import com.stash.addons.contract.ErrorCode
import com.stash.addons.contract.Tier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

/**
 * Eclipse-family answers, built from Eclipse's and BitChord's docs and sanitised to `example.org`
 * (`src/test/resources/addon-fixtures-eclipse`), through [AddonEclipse] as an add-on the user
 * added (the base rules on). They belong in stash-addon-spec `fixtures/eclipse/` with the spec's
 * §10.2 corrections (plan phase 4 §2.3, step B2), shared with the checker.
 */
class AddonEclipseFixturesTest {

    private val root: File = File(
        requireNotNull(javaClass.classLoader?.getResource("addon-fixtures-eclipse/manifest")) { "fixtures not on the test classpath" }.toURI(),
    ).parentFile

    private val user = AddonValidator.Options(publicOnly = true)

    private fun load(name: String): JsonElement = Json.parseToJsonElement(File(root, name).readText())

    @Test fun `Eclipse's documented manifest reads as v1`() {
        val read = AddonValidator.readManifest(load("manifest/eclipse-docs.json"), user)
        assertThat(read.dialect).isEqualTo(AddonDialect.ECLIPSE)
        val m = read.manifest
        assertThat(m.id).isEqualTo("com.example.music")
        assertThat(m.name).isEqualTo("Example Music")
        // `catalog` means /album and /artist exist; `types` is display-only.
        assertThat(m.resources).containsExactly("search", "stream", "album", "artist").inOrder()
        assertThat(m.stream!!.cors).isFalse()
        assertThat(m.stream!!.knownManifests).containsExactly(Delivery.NONE, Delivery.DASH, Delivery.HLS)
        assertThat(m.icon).isEqualTo("https://example.org/icon.png")
        assertThat(read.eclipse).isEqualTo(EclipseTraits())
    }

    @Test fun `a BitChord manifest with only an id is filled in, and its switches read`() {
        val read = AddonValidator.readManifest(load("manifest/bitchord-minimal.json"), user)
        assertThat(read.manifest.id).isEqualTo("com.example.bit")
        assertThat(read.manifest.name).isEqualTo("com.example.bit")
        assertThat(read.eclipse!!.allowDownloads).isFalse()
        assertThat(read.eclipse!!.qualityOption).isEqualTo("LOSSLESS")
        // A setting that breaks v1's rules (a reserved key) is dropped, not the add-on.
        assertThat(read.manifest.settings!!.map { it.key }).containsExactly("quality")
    }

    @Test fun `isrc without resolve resolves through resolve-isrc`() {
        val read = AddonValidator.readManifest(load("manifest/isrc-resolver.json"), user)
        assertThat(read.manifest.resources).contains("resolve")
        assertThat(read.eclipse!!.resolveViaIsrc).isTrue()
    }

    @Test fun `bitchordAuth is refused in BitChord's own words`() {
        val e = assertThrows(AddonContractException::class.java) { AddonValidator.readManifest(load("manifest/bitchord-auth.json"), user) }
        assertThat(e.reason).isEqualTo(AddonEclipse.ONLY_BITCHORD)
    }

    @Test fun `an add-on of pages only reads, offering nothing that plays`() {
        val m = AddonValidator.readManifest(load("manifest/pages-only.json"), user).manifest
        assertThat(m.resources).containsExactly("search", "album", "artist", "catalog")
        assertThat(m.catalogs!!.map { it.id }).containsExactly("new")
        assertThat(m.stream).isNull()
    }

    @Test fun `search rows are read tolerantly`() {
        val r = AddonEclipse.searchResponse(load("search/rows.json"), user)
        assertThat(r.tracks.map { it.id }).containsExactly("12345", "b2", "d4").inOrder()
        val first = r.tracks[0]
        assertThat(first.durationMs).isEqualTo(320_357L)
        assertThat(first.isrc).isEqualTo("GBDUW0000059")
        assertThat(first.explicit).isFalse()
        assertThat(first.artwork!!.single().url).isEqualTo("https://img.example.org/a.jpg")
        assertThat(r.tracks[1].durationMs).isEqualTo(207_000L)
        assertThat(r.tracks[1].artwork!!.single().url).isEqualTo("https://img.example.org/b.jpg")
        // Artwork on a private address and a malformed ISRC are dropped; the row stays.
        assertThat(r.tracks[2].artwork).isNull()
        assertThat(r.tracks[2].isrc).isNull()
        // No `artists` list was sent; the album is read (a year written as text too). The client
        // keeps albums only for an add-on that has album pages.
        assertThat(r.artists).isNull()
        assertThat(r.albums).containsExactly(com.stash.addons.contract.AlbumSummary("al", "Discovery", "Daft Punk", 2001))
    }

    // ------------------------------------------------------------------------------------------ settled with the spec's reader (review-882 M10)

    @Test fun `a manifest that states no version is at 0_0_0`() {
        assertThat(AddonValidator.readManifest(load("manifest/bitchord-minimal.json"), user).manifest.version).isEqualTo("0.0.0")
    }

    @Test fun `the quality select is read as the add-on wrote it, even when v1 would not draw that setting`() {
        fun option(settings: String) = AddonEclipse.manifest(
            Json.parseToJsonElement("""{"id":"q.addon","resources":["search","stream"],"settings":$settings}"""), user,
        ).eclipse!!.qualityOption
        // No label on the setting or its options: v1 refuses to draw it, and the add-on still reads `quality`.
        assertThat(option("""[{"key":"quality","type":"select","options":[{"value":"FLAC"},{"value":"MP3"}]}]""")).isEqualTo("FLAC")
        // The first option that has a text value.
        assertThat(option("""[{"key":"quality","type":"select","options":[{"value":7},"x",{"value":"HI"}]}]""")).isEqualTo("HI")
        // Not a select, another key, or a value no option may have: as if none were declared.
        assertThat(option("""[{"key":"quality","type":"text"}]""")).isNull()
        assertThat(option("""[{"key":"Quality","type":"select","options":[{"value":"FLAC"}]}]""")).isNull()
        assertThat(option("""[{"key":"quality","type":"select","options":[{"value":"${"x".repeat(101)}"}]}]""")).isNull()
        assertThat(option("""[{"key":"quality","type":"select","options":[{"value":""}]}]""")).isNull()
    }

    @Test fun `anything but false on encrypted, drm, hybrid or video is not playable`() {
        fun stream(extra: String) = Json.parseToJsonElement("""{"url":"https://cdn.example.org/a.flac","codec":"flac",$extra}""")
        for (marker in listOf("encrypted", "drm", "hybrid", "video")) {
            for (value in listOf("true", "0", "1", "\"false\"", "\"0\"", "\"\"", "\"cenc\"", "{}", "[]")) {
                val refused = assertThrows("$marker: $value", AddonRefusalException::class.java) { AddonEclipse.streamInfo(stream("\"$marker\":$value"), user) }
                assertThat(refused.code).isEqualTo(ErrorCode.QUALITY_UNAVAILABLE)
            }
            for (value in listOf("false", "null")) {
                assertThat(AddonEclipse.streamInfo(stream("\"$marker\":$value"), user).tier).isEqualTo(Tier.LOSSLESS)
            }
        }
    }

    @Test fun `an empty manifest field says nothing - the delivery is read from format`() {
        fun delivery(fields: String) =
            AddonEclipse.streamInfo(Json.parseToJsonElement("""{"url":"https://cdn.example.org/a","codec":"flac",$fields}"""), user).manifest
        assertThat(delivery("\"manifest\":\"\"")).isEqualTo(Delivery.NONE)
        assertThat(delivery("\"manifest\":\"\",\"format\":\"dash\"")).isEqualTo(Delivery.DASH)
        assertThat(delivery("\"manifest\":\"  \",\"format\":\"hls\"")).isEqualTo(Delivery.HLS)
        // A delivery this app does not know is still a broken answer.
        assertThrows(AddonContractException::class.java) { delivery("\"manifest\":\"smooth\"") }
    }


    @Test fun `streams read codec first, format as a codec word or a delivery, and derive the tier`() {
        val hires = AddonEclipse.streamInfo(load("stream/flac-hires.json"), user)
        assertThat(hires.codec).isEqualTo(Codec.FLAC)
        assertThat(hires.tier).isEqualTo(Tier.HIRES)
        assertThat(hires.manifest).isEqualTo(Delivery.NONE)
        assertThat(hires.cors).isFalse()
        assertThat(hires.expiresAt).isEqualTo(1_794_355_200L)

        val mp3 = AddonEclipse.streamInfo(load("stream/mp3-320.json"), user)
        assertThat(mp3.codec).isEqualTo(Codec.MP3)
        assertThat(mp3.tier).isEqualTo(Tier.HIGH)

        val aac = AddonEclipse.streamInfo(load("stream/aac-kbps-bitrate.json"), user)
        assertThat(aac.bitrate).isEqualTo(320_000)
        assertThat(aac.tier).isEqualTo(Tier.HIGH)

        val dash = AddonEclipse.streamInfo(load("stream/dash-as-format.json"), user)
        assertThat(dash.manifest).isEqualTo(Delivery.DASH)
        assertThat(dash.codec).isEqualTo(Codec.FLAC)
        assertThat(dash.tier).isEqualTo(Tier.LOSSLESS)

        assertThat(AddonEclipse.streamInfo(load("stream/bitchord-high.json"), user).tier).isEqualTo(Tier.HIGH)
        assertThat(AddonEclipse.streamInfo(load("stream/expires-in-ms.json"), user).expiresAt).isEqualTo(1_794_355_200L)
    }

    @Test fun `a 200 error is a miss, and encrypted, hybrid and DRM answers are not playable`() {
        assertThat(assertThrows(AddonRefusalException::class.java) { AddonEclipse.streamInfo(load("stream/error-200.json"), user) }.code)
            .isEqualTo(ErrorCode.NOT_FOUND)
        for (f in listOf("stream/encrypted.json", "stream/hybrid.json", "stream/drm.json")) {
            assertThat(assertThrows(AddonRefusalException::class.java) { AddonEclipse.streamInfo(load(f), user) }.code)
                .isEqualTo(ErrorCode.QUALITY_UNAVAILABLE)
        }
    }

    @Test fun `stream URLs keep the URL rule and the base rules`() {
        assertThat(assertThrows(AddonContractException::class.java) { AddonEclipse.streamInfo(load("stream/http-url.json"), user) }.path)
            .isEqualTo("/url")
        val private = assertThrows(AddonContractException::class.java) { AddonEclipse.streamInfo(load("stream/private-url.json"), user) }
        assertThat(private.reason).isEqualTo(AddonEclipse.PRIVATE_ADDRESS)
    }
}
