package com.stash.data.download.lossless.qbdlx

import org.junit.Assert.assertEquals
import org.junit.Test

/** Parse coverage for the Qobuz featured/playlist envelopes (spec §14 shapes). */
class QbdlxFeaturedParseTest {
    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true
    }

    @Test fun `album getFeatured envelope reuses the album list shape`() {
        val body = """{"albums":{"total":482,"items":[
            {"id":"a8gc9ch2rp7kl","title":"POWER HOUSE",
             "image":{"small":"s","thumbnail":"t","large":"L"},
             "artist":{"id":5771332,"name":"Magi Merlin"},
             "release_date_original":"2026-07-10","tracks_count":10}]}}"""
        val a = json.decodeFromString<QbdlxArtistAlbumsResponse>(body).albums.items.single()
        assertEquals("a8gc9ch2rp7kl", a.id)
        assertEquals("L", a.image?.large)
        assertEquals("2026-07-10", a.release_date_original)
    }

    @Test fun `playlist getFeatured envelope parses`() {
        val body = """{"playlists":{"total":6360,"items":[
            {"id":67048110,"name":"Brazilcore II","owner":{"name":"Qobuz France"},
             "tracks_count":58,"images300":["https://img/300.jpg"]}]}}"""
        val p = json.decodeFromString<QbdlxFeaturedPlaylistsResponse>(body).playlists.items.single()
        assertEquals(67048110L, p.id)
        assertEquals("Brazilcore II", p.name)
        assertEquals("Qobuz France", p.owner?.name)
        assertEquals("https://img/300.jpg", p.images300.firstOrNull())
    }

    @Test fun `playlist get detail parses tracks with performer and album art`() {
        val body = """{"id":67048110,"name":"Brazilcore II","owner":{"name":"Qobuz France"},
            "tracks_count":58,"images300":["https://img/300.jpg"],
            "tracks":{"total":58,"items":[
              {"id":108364435,"title":"Funky Tamborim","performer":{"name":"Tania Maria"},
               "duration":195,"album":{"title":"Love Explosion","image":{"large":"AL"}}}]}}"""
        val d = json.decodeFromString<QbdlxPlaylistDetailResponse>(body)
        assertEquals("Brazilcore II", d.name)
        val t = d.tracks.items.single()
        assertEquals("Tania Maria", t.performer?.name)
        assertEquals(195, t.duration)
        assertEquals("AL", t.album?.image?.large)
    }
}
