package com.stash.feature.home

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.MusicSource
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
import org.junit.Test

class MixRailClassifierTest {
    private fun pl(name: String, type: PlaylistType, src: MusicSource = MusicSource.SPOTIFY) =
        Playlist(id = 1, name = name, source = src, type = type)

    @Test fun `stash mix goes to Your mixes`() {
        assertThat(mixRail(pl("My Guitar Mix", PlaylistType.STASH_MIX))).isEqualTo(MixRail.YOUR_MIXES)
    }
    @Test fun `radio names go to Radios`() {
        assertThat(mixRail(pl("David Bowie Radio", PlaylistType.DAILY_MIX))).isEqualTo(MixRail.RADIOS)
    }
    @Test fun `known dailies go to Made for you`() {
        assertThat(mixRail(pl("Discover Weekly", PlaylistType.DAILY_MIX))).isEqualTo(MixRail.MADE_FOR_YOU)
        assertThat(mixRail(pl("Daily Mix 3", PlaylistType.DAILY_MIX))).isEqualTo(MixRail.MADE_FOR_YOU)
        assertThat(mixRail(pl("My Mix 2", PlaylistType.DAILY_MIX, MusicSource.YOUTUBE))).isEqualTo(MixRail.MADE_FOR_YOU)
        assertThat(mixRail(pl("My Supermix", PlaylistType.DAILY_MIX, MusicSource.YOUTUBE))).isEqualTo(MixRail.MADE_FOR_YOU)
    }
    @Test fun `other daily mixes go to Mood and decades`() {
        assertThat(mixRail(pl("Focus Mix", PlaylistType.DAILY_MIX))).isEqualTo(MixRail.MOOD_DECADES)
        assertThat(mixRail(pl("Motown", PlaylistType.DAILY_MIX))).isEqualTo(MixRail.MOOD_DECADES)
        assertThat(mixRail(pl("This Is Rohne", PlaylistType.DAILY_MIX))).isEqualTo(MixRail.MOOD_DECADES)
    }
    @Test fun `non-mix playlists are not classified`() {
        assertThat(mixRail(pl("Road Trip 2024", PlaylistType.CUSTOM))).isNull()
        assertThat(mixRail(pl("Liked Songs", PlaylistType.LIKED_SONGS))).isNull()
    }
    @Test fun `radio precedence beats the mood fallback`() {
        assertThat(mixRail(pl("Are You Alright? Radio", PlaylistType.DAILY_MIX))).isEqualTo(MixRail.RADIOS)
    }
}
