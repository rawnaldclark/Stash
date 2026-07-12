package com.stash.core.data.cache

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmArtistInfo
import com.stash.core.data.musicbrainz.MusicBrainzClient
import com.stash.data.ytmusic.model.SocialLink
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class ArtistAboutEnricherTest {
    private fun relsPayload(vararg typeToUrl: Pair<String, String>): JsonObject {
        val rels = typeToUrl.joinToString(",") { (type, url) ->
            """{"type":"$type","url":{"resource":"$url"}}"""
        }
        return Json.parseToJsonElement("""{"relations":[$rels]}""") as JsonObject
    }

    private fun enricher(
        info: LastFmArtistInfo?,
        mbLookup: JsonObject? = null,
        mbSearch: JsonObject? = null,
    ): RealArtistAboutEnricher {
        val lastFm = mock<LastFmApiClient> {
            onBlocking { getArtistInfo(any()) } doReturn Result.success(info)
        }
        val mb = object : MusicBrainzClient {
            override suspend fun lookupUrlRels(mbid: String) = mbLookup
            override suspend fun searchByName(name: String) = mbSearch
        }
        return RealArtistAboutEnricher(lastFm, mb)
    }

    @Test fun `bio and socials both present`() = runTest {
        val about = enricher(
            info = LastFmArtistInfo(bio = "b", mbid = "m1"),
            mbLookup = relsPayload("social network" to "https://instagram.com/a"),
        ).enrich("A")
        assertThat(about!!.bio).isEqualTo("b")
        assertThat(about.socials).containsExactly(SocialLink("instagram", "https://instagram.com/a"))
    }

    @Test fun `bio null but socials present is partial`() = runTest {
        val about = enricher(
            info = LastFmArtistInfo(bio = null, mbid = "m1"),
            mbLookup = relsPayload("official homepage" to "https://a.com"),
        ).enrich("A")
        assertThat(about!!.bio).isNull()
        assertThat(about.socials).isNotEmpty()
    }

    @Test fun `nothing found returns null`() = runTest {
        val about = enricher(info = LastFmArtistInfo(bio = null, mbid = null)).enrich("A")
        assertThat(about).isNull()
    }

    @Test fun `stale mbid lookup null falls back to name search`() = runTest {
        val about = enricher(
            info = LastFmArtistInfo(bio = "b", mbid = "stale"),
            mbLookup = null,
            mbSearch = relsPayload("youtube" to "https://youtube.com/@a"),
        ).enrich("A")
        assertThat(about!!.socials).containsExactly(SocialLink("youtube", "https://youtube.com/@a"))
    }
}
