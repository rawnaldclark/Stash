package com.stash.core.data.sync

import com.stash.core.data.sync.MixStacking.isStackable
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mixes that can stack (owner, 2026-10-29): the taste-built ones, not radios or recaps. */
class MixStackingTest {
    private fun spotify(name: String) = isStackable(MusicSource.SPOTIFY, PlaylistType.DAILY_MIX, name)
    private fun youtube(name: String) = isStackable(MusicSource.YOUTUBE, PlaylistType.DAILY_MIX, name)

    @Test fun spotifyDailyMixesAndDiscoverWeekly() {
        assertTrue(spotify("Daily Mix 1"))
        assertTrue(spotify("Daily Mix 6"))
        assertTrue(spotify("Discover Weekly"))
    }

    @Test fun youtubeMyMixesAndDiscoverMix() {
        assertTrue(youtube("My Mix 3"))
        assertTrue(youtube("Discover Mix"))
    }

    @Test fun everythingElseRefreshes() {
        assertFalse(spotify("Release Radar"))
        assertFalse(spotify("Beck Radio"))
        assertFalse(spotify("Rock Mix"))
        assertFalse(spotify("Your Top Songs 2025"))
        assertFalse(youtube("Replay Mix"))
        assertFalse(youtube("My Supermix"))
        assertFalse(youtube("Daily Mix 1")) // a Spotify name on YouTube
    }

    @Test fun onlyMixes() {
        assertFalse(isStackable(MusicSource.SPOTIFY, PlaylistType.CUSTOM, "Daily Mix 1"))
    }
}
