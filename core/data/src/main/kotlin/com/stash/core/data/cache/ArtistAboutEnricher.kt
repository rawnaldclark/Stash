package com.stash.core.data.cache

import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.musicbrainz.MusicBrainzClient
import com.stash.core.data.musicbrainz.mapSocials
import com.stash.data.ytmusic.model.ArtistAbout
import com.stash.data.ytmusic.model.SocialLink
import javax.inject.Inject

interface ArtistAboutEnricher { suspend fun enrich(artistName: String): ArtistAbout? }

/** No-op default so ArtistCache's existing test constructions keep compiling
 *  (mirrors NoopDiscographySupplement). */
class NoopArtistAboutEnricher : ArtistAboutEnricher {
    override suspend fun enrich(artistName: String): ArtistAbout? = null
}

class RealArtistAboutEnricher @Inject constructor(
    private val lastFm: LastFmApiClient,
    private val mb: MusicBrainzClient,
) : ArtistAboutEnricher {
    override suspend fun enrich(artistName: String): ArtistAbout? {
        val info = lastFm.getArtistInfo(artistName).getOrNull()
        val bio = info?.bio
        val relsPayload = (info?.mbid?.let { mb.lookupUrlRels(it) }) ?: mb.searchByName(artistName)
        val socials: List<SocialLink> = relsPayload?.let { mapSocials(it) } ?: emptyList()
        // photoUrl left null in v1 (Wikimedia upgrade deferred).
        return if (bio == null && socials.isEmpty()) null
        else ArtistAbout(bio = bio, socials = socials, photoUrl = null)
    }
}
