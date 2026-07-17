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

    @Test fun keepsDailyMixes()        = assertTrue(isSpotifyMix("Daily Mix 3", "spotify"))
    @Test fun keepsNamedMixes()        = assertTrue(isSpotifyMix("Discover Weekly", "spotify"))
    @Test fun keepsYourTopSongs()      = assertTrue(isSpotifyMix("Your Top Songs 2025", "spotify"))
    @Test fun keepsBlend()             = assertTrue(isSpotifyMix("Rawn + Alex", "spotify")) // Blend, spotify-owned
    @Test fun keepsMadeForYouMood()    = assertTrue(isSpotifyMix("Chill Mix", "spotify"))
    @Test fun rejectsUserOwnedCustom() = assertFalse(isSpotifyMix("My Road Trip", "rawnaldclark"))
}
