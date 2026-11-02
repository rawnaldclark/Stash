package com.stash.core.data.cache

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmArtistInfo
import com.stash.core.data.musicbrainz.MusicBrainzClient
import com.stash.data.spotify.SpotifyApiClient
import com.stash.data.spotify.SpotifyArtistVisuals
import com.stash.data.ytmusic.model.SocialLink
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
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
        visuals: SpotifyArtistVisuals? = null,
        visualsStall: Boolean = false,
        /** How long MusicBrainz takes to answer (it can take 1–7 s). */
        mbDelayMs: Long = 0L,
        /** MusicBrainz's call blocks: it can't be cancelled, as OkHttp's execute() can't. */
        mbBlocks: Boolean = false,
    ): RealArtistAboutEnricher {
        val lastFm = mock<LastFmApiClient> {
            onBlocking { getArtistInfo(any()) } doReturn Result.success(info)
        }
        suspend fun slowMb() {
            if (mbDelayMs <= 0L) return
            if (mbBlocks) withContext(NonCancellable) { delay(mbDelayMs) } else delay(mbDelayMs)
        }
        val mb = object : MusicBrainzClient {
            override suspend fun lookupUrlRels(mbid: String) = mbLookup.also { slowMb() }
            override suspend fun searchByName(name: String) = mbSearch.also { slowMb() }
            override suspend fun searchRecordingsByIsrc(isrc: String): JsonObject? = null
            override suspend fun searchReleaseGroups(artist: String, album: String): JsonObject? = null
            override suspend fun browseReleases(releaseGroupId: String): JsonObject? = null
            override suspend fun releaseGroupUrlRels(releaseGroupId: String): JsonObject? = null
            override suspend fun releaseUrlRels(releaseId: String): JsonObject? = null
        }
        val spotify = mock<SpotifyApiClient> {
            if (visualsStall) {
                onBlocking { getArtistVisuals(any()) } doSuspendableAnswer { awaitCancellation() }
            } else {
                onBlocking { getArtistVisuals(any()) } doReturn visuals
            }
        }
        return RealArtistAboutEnricher(lastFm, mb, spotify)
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

    @Test fun `Spotify's monthly listeners reach About, even when nothing else was found`() = runTest {
        val about = enricher(
            info = LastFmArtistInfo(bio = null, mbid = null),
            visuals = SpotifyArtistVisuals(gallery = emptyList(), headerUrl = null, monthlyListeners = 1_734_567L),
        ).enrich("Sonic Youth")
        assertThat(about!!.monthlyListeners).isEqualTo(1_734_567L)
        // The hero shows the count; About itself has nothing, so the section stays hidden.
        assertThat(about.hasSection).isFalse()
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

    @Test fun `Spotify photos fill the gallery and the header`() = runTest {
        val about = enricher(
            info = LastFmArtistInfo(bio = "b", mbid = null),
            visuals = SpotifyArtistVisuals(gallery = listOf("https://i/1", "https://i/2"), headerUrl = "https://i/h"),
        ).enrich("A")
        assertThat(about!!.gallery).containsExactly("https://i/1", "https://i/2").inOrder()
        assertThat(about.headerUrl).isEqualTo("https://i/h")
    }

    @Test fun `photos alone still make an About`() = runTest {
        val about = enricher(
            info = null,
            visuals = SpotifyArtistVisuals(gallery = listOf("https://i/1"), headerUrl = null),
        ).enrich("A")
        assertThat(about!!.gallery).hasSize(1)
        assertThat(about.bio).isNull()
    }

    @Test fun `Spotify photos that never answer cost the photos, not the bio`() = runTest {
        val about = enricher(info = LastFmArtistInfo(bio = "b", mbid = null), visualsStall = true).enrich("A")
        assertThat(about!!.bio).isEqualTo("b")
        assertThat(about.gallery).isEmpty()
        // Within the 4 s ArtistCache gives the whole enrich.
        assertThat(currentTime).isLessThan(4_000)
    }

    // Review on #770: a slow MusicBrainz overran ArtistCache's 4 s and the whole About went,
    // Spotify's listener count with it.

    private val spotifyCount = SpotifyArtistVisuals(
        gallery = emptyList(), headerUrl = null, monthlyListeners = 1_734_567L, topTrackTitles = listOf("Teen Age Riot"),
    )

    @Test fun `a slow MusicBrainz costs the links, never Spotify's count or the bio`() = runTest {
        val about = enricher(
            info = LastFmArtistInfo(bio = "b", mbid = "m1"),
            mbLookup = relsPayload("social network" to "https://instagram.com/a"),
            visuals = spotifyCount,
            mbDelayMs = 7_000L,
        ).enrich("Sonic Youth")
        assertThat(about!!.monthlyListeners).isEqualTo(1_734_567L)
        assertThat(about.topTrackTitles).containsExactly("Teen Age Riot")
        // Last.fm had answered before the bound: its bio is kept.
        assertThat(about.bio).isEqualTo("b")
        assertThat(about.socials).isEmpty()
        assertThat(currentTime).isEqualTo(RealArtistAboutEnricher.TEXT_TIMEOUT_MS)
    }

    @Test fun `a MusicBrainz call that can't be cancelled is not waited for`() = runTest {
        // OkHttp's execute() ignores cancellation: a timeout around it would wait the 7 s out.
        val about = enricher(
            info = LastFmArtistInfo(bio = "b", mbid = "m1"),
            mbLookup = relsPayload("social network" to "https://instagram.com/a"),
            visuals = spotifyCount,
            mbDelayMs = 7_000L,
            mbBlocks = true,
        ).enrich("Sonic Youth")
        assertThat(about!!.monthlyListeners).isEqualTo(1_734_567L)
        assertThat(currentTime).isLessThan(4_000)
    }

    @Test fun `a MusicBrainz that answers in time keeps its links`() = runTest {
        val about = enricher(
            info = LastFmArtistInfo(bio = "b", mbid = "m1"),
            mbLookup = relsPayload("social network" to "https://instagram.com/a"),
            visuals = spotifyCount,
            mbDelayMs = 2_000L,
        ).enrich("Sonic Youth")
        assertThat(about!!.socials).containsExactly(SocialLink("instagram", "https://instagram.com/a"))
        assertThat(about.monthlyListeners).isEqualTo(1_734_567L)
    }
}
