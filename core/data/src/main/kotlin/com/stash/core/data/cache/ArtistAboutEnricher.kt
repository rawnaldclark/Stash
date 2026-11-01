package com.stash.core.data.cache

import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.musicbrainz.MusicBrainzClient
import com.stash.core.data.musicbrainz.mapSocials
import com.stash.data.ytmusic.model.ArtistAbout
import com.stash.data.spotify.SpotifyApiClient
import com.stash.data.ytmusic.model.SocialLink
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
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
        // Spotify's photos and monthly listeners alongside the bio, on a leash of their own: a slow
        // answer costs them, never the bio. Its calls end with the coroutine (cancellable = true).
        val visuals = async { withTimeoutOrNull(VISUALS_TIMEOUT_MS) { spotify.getArtistVisuals(artistName) } }
        // The bio and links within their own bound, so a slow MusicBrainz never costs Spotify's
        // part: ArtistCache drops the whole enrich past its 4 s (review on #770: a cold visit then
        // cached YouTube's 574K for 6 h, and a refresh swapped 1.7M for it).
        val text = findTextWithin(artistName, TEXT_TIMEOUT_MS)
        val bio = text.bio
        val socials: List<SocialLink> = text.socials
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
            topTrackTitles = photos?.topTrackTitles.orEmpty(),
        )
    }

    /** What Last.fm and MusicBrainz found; written by the lookup in [findTextWithin], read when its wait ends. */
    private class FoundText {
        @Volatile var bio: String? = null
        @Volatile var socials: List<SocialLink> = emptyList()
    }

    /**
     * The bio (Last.fm) and the links (MusicBrainz, by the id Last.fm gives, else by name): what
     * of them is in within [timeoutMs]. MusicBrainz alone can take 1–7 s (one call a second, the
     * gate shared with the back covers).
     *
     * Both clients make blocking HTTP calls (OkHttp `execute()`) that a timeout can't interrupt:
     * a `withTimeoutOrNull` around them would still wait for the answer, and so does ArtistCache's
     * `withTimeout`. So the lookup runs outside this coroutine's scope (the caller's dispatcher,
     * not its Job; the calls move to IO themselves). Past the bound this returns without it,
     * keeping what it had found (the bio, when only MusicBrainz is slow), and the lookup is
     * cancelled: it ends at its next suspension, at worst at OkHttp's own timeouts.
     */
    private suspend fun findTextWithin(artistName: String, timeoutMs: Long): FoundText {
        val found = FoundText()
        val lookup = CoroutineScope(currentCoroutineContext().minusKey(Job) + Job()).launch {
            try {
                val info = lastFm.getArtistInfo(artistName).getOrNull()
                found.bio = info?.bio
                val rels = info?.mbid?.let { mb.lookupUrlRels(it) } ?: mb.searchByName(artistName)
                found.socials = rels?.let { mapSocials(it) } ?: emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best effort, like the rest of About: a root coroutine must not throw (it would crash).
            }
        }
        if (withTimeoutOrNull(timeoutMs) { lookup.join() } == null) lookup.cancel()
        return found
    }

    internal companion object {
        /** Under ArtistCache's 4 s for the whole enrich: a search and an overview, each ~0.5 s. */
        const val VISUALS_TIMEOUT_MS = 3_500L

        /** The bio and links: under ArtistCache's 4 s, so the enrich always answers in time. */
        const val TEXT_TIMEOUT_MS = 3_300L
    }
}
