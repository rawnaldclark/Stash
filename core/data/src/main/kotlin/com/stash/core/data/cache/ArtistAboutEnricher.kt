package com.stash.core.data.cache

import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.musicbrainz.MusicBrainzClient
import com.stash.core.data.musicbrainz.mapSocials
import com.stash.data.ytmusic.model.ArtistAbout
import com.stash.data.spotify.SpotifyApiClient
import com.stash.data.ytmusic.model.SocialLink
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

interface ArtistAboutEnricher { suspend fun enrich(artistName: String): ArtistAbout? }

/** No-op default so ArtistCache's existing test constructions keep compiling
 *  (mirrors NoopDiscographySupplement). */
class NoopArtistAboutEnricher : ArtistAboutEnricher {
    override suspend fun enrich(artistName: String): ArtistAbout? = null
}

class RealArtistAboutEnricher @Inject constructor(
    private val lastFm: LastFmApiClient,
    private val mb: MusicBrainzClient,
    private val spotify: SpotifyApiClient,
) : ArtistAboutEnricher {
    override suspend fun enrich(artistName: String): ArtistAbout? = coroutineScope {
        // Spotify's photos alongside the bio, on a shorter leash of their own: a slow answer
        // costs the photos, never the bio (ArtistCache bounds the whole enrich).
        val visuals = async { withTimeoutOrNull(VISUALS_TIMEOUT_MS) { spotify.getArtistVisuals(artistName) } }
        val info = lastFm.getArtistInfo(artistName).getOrNull()
        val bio = info?.bio
        val relsPayload = (info?.mbid?.let { mb.lookupUrlRels(it) }) ?: mb.searchByName(artistName)
        val socials: List<SocialLink> = relsPayload?.let { mapSocials(it) } ?: emptyList()
        val photos = visuals.await()
        val gallery = photos?.gallery.orEmpty()
        val header = photos?.headerUrl
        // Spotify's monthly listeners ride on the photos' answer; the hero shows them over YouTube's.
        val monthlyListeners = photos?.monthlyListeners
        // photoUrl left null in v1 (Wikimedia upgrade deferred).
        if (bio == null && socials.isEmpty() && gallery.isEmpty() && header == null && monthlyListeners == null) null
        else ArtistAbout(
            bio = bio,
            socials = socials,
            photoUrl = null,
            gallery = gallery,
            headerUrl = header,
            monthlyListeners = monthlyListeners,
        )
    }

    private companion object {
        /** Under ArtistCache's 4 s for the whole enrich: a search and an overview, each ~0.5 s. */
        const val VISUALS_TIMEOUT_MS = 3_500L
    }
}
