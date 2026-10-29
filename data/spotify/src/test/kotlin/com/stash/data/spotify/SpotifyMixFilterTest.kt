package com.stash.data.spotify

import com.stash.data.spotify.SpotifyApiClient.Companion.isSearchedMixName
import com.stash.data.spotify.SpotifyApiClient.Companion.isSpotifyMix
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [isSpotifyMix], the pure keep-check deciding which home-feed
 * playlists the sync widens to.
 *
 * The rule: keep "Daily Mix N", keep the known named mixes, keep spotify-owned
 * personalized items (Your Top Songs / Blend / Made-For-You) — but DROP editorial
 * playlists, which are spotify-owned too and so used to ride in on the owner
 * catch-all.
 *
 * Ids below are real prefixes observed on a live account (2026-08-01), where the
 * catch-all had classified 66 editorial playlists as "mixes" out of 130 total.
 */
class SpotifyMixFilterTest {

    // Name branches use a NON-spotify owner so only the name rule can carry
    // them — otherwise the owner catch-all would mask the regex/name-set checks.
    @Test fun keepsDailyMixes()        = assertTrue(isSpotifyMix("Daily Mix 3", "someuser"))
    @Test fun keepsNamedMixes()        = assertTrue(isSpotifyMix("Discover Weekly", "someuser"))

    // Owner catch-all: personalized items whose names match no rule.
    @Test fun keepsYourTopSongs()      = assertTrue(isSpotifyMix("Your Top Songs 2025", "spotify"))
    @Test fun keepsBlend()             = assertTrue(isSpotifyMix("Rawn + Alex", "spotify"))
    @Test fun keepsMadeForYouMood()    = assertTrue(isSpotifyMix("Chill Mix", "spotify"))

    @Test fun rejectsUserOwnedCustom() = assertFalse(isSpotifyMix("My Road Trip", "rawnaldclark"))

    // ── Editorial deny-rule (the ceiling the previous version pinned) ────────
    // Every one of these is spotify-owned, so ONLY the id can reject them.

    @Test fun rejectsEditorialDX() =
        assertFalse(isSpotifyMix("Disco Fever", "spotify", "37i9dQZF1DX2sUQwD7tbmL"))

    @Test fun rejectsEditorialDW() =
        assertFalse(isSpotifyMix("70s Rock Anthems", "spotify", "37i9dQZF1DWWwzidNQX6jx"))

    /** "This Is <artist>" is curated for everyone, not generated for this listener. */
    @Test fun rejectsThisIsArtist() =
        assertFalse(isSpotifyMix("This Is Phantogram", "spotify", "37i9dQZF1DZ06evO2iBPiw"))

    // ── Personalized surfaces must survive the deny-rule ─────────────────────

    @Test fun keepsDailyMixById() =
        assertTrue(isSpotifyMix("Daily Mix 1", "spotify", "37i9dQZF1E38ZgHCGJDJmC"))

    @Test fun keepsArtistRadio() =
        assertTrue(isSpotifyMix("Curtis Mayfield Radio", "spotify", "37i9dQZF1E4z5B1TjSNLWc"))

    @Test fun keepsDecadeMix() =
        assertTrue(isSpotifyMix("70s Mix", "spotify", "37i9dQZF1EQqkOPvHGajmW"))

    /** Discover Weekly / Release Radar use a different prefix again — not editorial. */
    @Test fun keepsDiscoverWeeklyById() =
        assertTrue(isSpotifyMix("Discover Weekly", "spotify", "37i9dQZEVXcQ9COmYvdajy"))

    /** Absent id (older call sites / missing data) must not silently drop a mix. */
    @Test fun keepsWhenIdUnknown() =
        assertTrue(isSpotifyMix("Some Personalized Thing", "spotify"))

    // Search finds the mixes the home feed leaves out (2026-10-29); a name search
    // also returns lookalikes, so only the mixes' own names get through.
    @Test fun searchKeepsDiscoverWeekly() = assertTrue(isSearchedMixName("Discover Weekly"))
    @Test fun searchKeepsReleaseRadar()   = assertTrue(isSearchedMixName("Release Radar"))
    @Test fun searchKeepsDailyMix()       = assertTrue(isSearchedMixName("Daily Mix 4"))
    @Test fun searchKeepsYourTopSongs()   = assertTrue(isSearchedMixName("Your Top Songs 2025"))
    @Test fun searchDropsLookalikes() {
        assertFalse(isSearchedMixName("Discover Weekly Archive"))
        assertFalse(isSearchedMixName("Daily Mix Vibes"))
    }
}
