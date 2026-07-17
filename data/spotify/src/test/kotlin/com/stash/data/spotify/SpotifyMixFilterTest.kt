package com.stash.data.spotify

import com.stash.data.spotify.SpotifyApiClient.Companion.isSpotifyMix
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [isSpotifyMix], the pure keep-check that decides which
 * home-feed playlists the sync widens to.
 *
 * The rule: keep "Daily Mix N", keep the known named mixes, and keep anything
 * owned by "spotify" (the locale-proof catch-all for personalized home items
 * like Your Top Songs / Blend / Made-For-You / This Is). Drop user-owned
 * custom playlists.
 */
class SpotifyMixFilterTest {

    // Name branches use a NON-spotify owner so only the name rule can carry
    // them — otherwise the owner catch-all would mask the regex/name-set checks.
    @Test fun keepsDailyMixes()        = assertTrue(isSpotifyMix("Daily Mix 3", "someuser"))
    @Test fun keepsNamedMixes()        = assertTrue(isSpotifyMix("Discover Weekly", "someuser"))

    // Owner catch-all: personalized items whose names match no rule.
    @Test fun keepsYourTopSongs()      = assertTrue(isSpotifyMix("Your Top Songs 2025", "spotify"))
    @Test fun keepsBlend()             = assertTrue(isSpotifyMix("Rawn + Alex", "spotify")) // Blend, spotify-owned
    @Test fun keepsMadeForYouMood()    = assertTrue(isSpotifyMix("Chill Mix", "spotify"))

    @Test fun rejectsUserOwnedCustom() = assertFalse(isSpotifyMix("My Road Trip", "rawnaldclark"))

    // Pins the known ceiling as intentional: a spotify-owned editorial title
    // currently leaks through the owner catch-all. If B4 shows this on-device,
    // a deny-set flips this assertion (and this test guards that change).
    @Test fun keepsSpotifyOwnedEditorial() = assertTrue(isSpotifyMix("Today's Top Hits", "spotify"))
}
