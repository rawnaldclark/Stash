package com.stash.core.data.backcover

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.io.File

/** Fixtures trimmed from live Discogs and MusicBrainz replies (2026-10-24). */
class DiscogsMatchingTest {

    private fun json(name: String) =
        Json.parseToJsonElement(File("src/test/resources/backcover/$name").readText()).jsonObject

    private fun release(name: String) = parseDiscogsRelease(json(name))!!

    /**
     * A 150px Discogs thumbnail, decoded: the JPEGs saved as binary PPM (P6), since the unit tests
     * have no image decoder.
     */
    private fun thumb(name: String): Pixels {
        val bytes = File("src/test/resources/backcover/$name.ppm").readBytes()
        var pos = 0
        fun token(): String {
            while (bytes[pos].toInt().toChar().isWhitespace()) pos++
            val start = pos
            while (!bytes[pos].toInt().toChar().isWhitespace()) pos++
            return String(bytes, start, pos - start)
        }
        check(token() == "P6")
        val w = token().toInt()
        val h = token().toInt()
        check(token() == "255")
        pos++ // the one whitespace byte before the pixels
        val argb = IntArray(w * h) { i ->
            val o = pos + i * 3
            (0xFF shl 24) or ((bytes[o].toInt() and 0xFF) shl 16) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or (bytes[o + 2].toInt() and 0xFF)
        }
        return Pixels(w, h, argb)
    }

    private fun DiscogsRelease.back(halvesJoin: Boolean = false) = discogsBackIndex(this, halvesJoin)

    @Test fun `the Discogs master comes from the release group's links`() {
        assertThat(discogsMasterId(json("mb_rg_urlrels_rumours.json"))).isEqualTo(38722)
        assertThat(discogsMasterId(Json.parseToJsonElement("""{"relations":[]}""").jsonObject)).isNull()
        val releaseLink = """{"relations":[{"type":"discogs","url":{"resource":"https://www.discogs.com/release/1"}}]}"""
        assertThat(discogsMasterId(Json.parseToJsonElement(releaseLink).jsonObject)).isNull()
    }

    @Test fun `the most-collected accepted version is read`() {
        val versions = parseDiscogsVersions(json("discogs_versions_wwafawdwg.json"))
        assertThat(mostCollected(versions)).isEqualTo(13414611) // 34,131 own it
        val draft = DiscogsVersion(1, listOf("Vinyl"), accepted = false, inCollection = 1_000_000)
        assertThat(mostCollected(versions + draft)).isEqualTo(13414611)
        assertThat(mostCollected(emptyList())).isNull()
    }

    @Test fun `a sleeve's back is its first image after the front`() {
        // Rumours, Jacksonville pressing: front, back, two labels, the inner sleeve.
        val rumours = release("discogs_release_rumours_vinyl.json")
        assertThat(needsSeamCheck(rumours)).isTrue()
        assertThat(rumours.back()).isEqualTo(1)
    }

    @Test fun `after a gatefold spread photographed whole, the back is the next image`() {
        // After Hours 2LP: front, the spread (600x295), back.
        val afterHours = release("discogs_release_after_hours_vinyl.json")
        assertThat(needsSeamCheck(afterHours)).isFalse()
        assertThat(afterHours.back()).isEqualTo(2)
    }

    @Test fun `a gatefold the format names has its back after the two halves`() {
        // To Pimp a Butterfly 2LP, "Gatefold": front, the inside's two halves, back.
        val tpab = release("discogs_release_tpab_vinyl.json")
        assertThat(needsSeamCheck(tpab)).isFalse()
        assertThat(tpab.back()).isEqualTo(3)
    }

    @Test fun `halves whose edges meet put the back third, else the back is second`() {
        // Future Nostalgia (Moonlight Edition): the format doesn't say gatefold, the picture does.
        val nostalgia = release("discogs_release_future_nostalgia_vinyl.json")
        assertThat(needsSeamCheck(nostalgia)).isTrue()
        assertThat(nostalgia.back(halvesJoin = true)).isEqualTo(3)
        assertThat(nostalgia.back(halvesJoin = false)).isEqualTo(1)
    }

    @Test fun `a digital release has no back`() {
        // SOS on Discogs as files: its second image is an alternate cover.
        val sos = release("discogs_release_sos_file.json")
        assertThat(needsSeamCheck(sos)).isFalse()
        assertThat(sos.back()).isNull()
    }

    @Test fun `a release with only its front has no back`() {
        val front = DiscogsImage("https://i/f.jpeg", null, 600, 600)
        assertThat(discogsBackIndex(DiscogsRelease(1, listOf(front), listOf("Vinyl"), "", null), false)).isNull()
    }

    @Test fun `the inside is every image but the front, the back and slivers`() {
        val afterHours = release("discogs_release_after_hours_vinyl.json")
        val inside = discogsInside(afterHours, backIndex = 2)
        val all = afterHours.images
        assertThat(inside).doesNotContain(all[0])
        assertThat(inside).doesNotContain(all[2])
        assertThat(inside).doesNotContain(all[3]) // the spine, 600x24
        assertThat(inside.first()).isEqualTo(all[1]) // the gatefold spread
        assertThat(inside).hasSize(all.size - 3)
    }

    @Test fun `the halves of a gatefold meet, a back beside a label doesn't`() {
        // Measured on these thumbnails with the probe's own code: 4.9 and 22.5; 34.5 and 172.4.
        val nostalgia = seamGap(thumb("discogs_thumb_future_nostalgia_1"), thumb("discogs_thumb_future_nostalgia_2"))
        val cowboy = seamGap(thumb("discogs_thumb_be_the_cowboy_1"), thumb("discogs_thumb_be_the_cowboy_2"))
        val emma = seamGap(thumb("discogs_thumb_for_emma_1"), thumb("discogs_thumb_for_emma_2"))
        val rumours = seamGap(thumb("discogs_thumb_rumours_1"), thumb("discogs_thumb_rumours_2"))
        assertThat(nostalgia).isLessThan(HALVES_JOIN_BELOW)
        assertThat(cowboy).isLessThan(HALVES_JOIN_BELOW)
        assertThat(emma).isAtLeast(HALVES_JOIN_BELOW)
        assertThat(rumours).isAtLeast(HALVES_JOIN_BELOW)
    }

    @Test fun `the seam gap reads the edges, whatever the heights`() {
        fun flat(w: Int, h: Int, rgb: Int) = Pixels(w, h, IntArray(w * h) { rgb or (0xFF shl 24) })
        assertThat(seamGap(flat(150, 140, 0x336699), flat(150, 150, 0x336699))).isWithin(1e-9).of(0.0)
        assertThat(seamGap(flat(150, 150, 0x000000), flat(150, 75, 0xFFFFFF))).isWithin(1e-9).of(255.0)
    }
}
