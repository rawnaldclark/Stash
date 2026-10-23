package com.stash.feature.home

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.PlaylistPlayedShare
import com.stash.core.data.mix.MixBuildState
import com.stash.core.data.prefs.MixRotation
import com.stash.core.model.MusicSource
import org.junit.Test

class MixRotationTest {

    private fun mix(id: Long, pinnedAt: Long? = null) = HomeMix(
        id = id, title = "Mix $id", artUrl = null, source = MusicSource.SPOTIFY,
        buildState = MixBuildState.READY, trackCount = 10, pinnedToHomeAt = pinnedAt,
    )

    private val mixes = (1L..12L).map { mix(it) }
    private val recency = mixes.associate { it.id to it.id * 1000 } // higher id = fresher

    @Test fun `off keeps freshest first`() {
        val order = mixes.rotated(recency, MixRotation.OFF, epochDay = 20_000, salt = 0)
        assertThat(order.map { it.id }).isEqualTo((12L downTo 1L).toList())
    }

    @Test fun `the order holds for the whole period whatever order the mixes arrive in`() {
        val a = mixes.rotated(recency, MixRotation.EVERY_3_DAYS, epochDay = 20_001, salt = 0)
        val b = mixes.reversed().rotated(recency, MixRotation.EVERY_3_DAYS, epochDay = 20_002, salt = 0)
        assertThat(a).isEqualTo(b) // 20_001 and 20_002 are both period 6667
    }

    @Test fun `the next period reshuffles`() {
        val day1 = mixes.rotated(recency, MixRotation.DAILY, epochDay = 20_000, salt = 0)
        val day2 = mixes.rotated(recency, MixRotation.DAILY, epochDay = 20_001, salt = 0)
        assertThat(day1).isNotEqualTo(day2)
        assertThat(day1.toSet()).isEqualTo(day2.toSet())
    }

    @Test fun `rails rotate independently`() {
        val railA = mixes.rotated(recency, MixRotation.DAILY, epochDay = 20_000, salt = 0)
        val railB = mixes.rotated(recency, MixRotation.DAILY, epochDay = 20_000, salt = 2)
        assertThat(railA).isNotEqualTo(railB)
    }

    @Test fun `favorites lead, first favorited first, and never rotate`() {
        val withFavs = mixes.map {
            when (it.id) {
                5L -> mix(5, pinnedAt = 200)
                9L -> mix(9, pinnedAt = 100)
                else -> it
            }
        }
        for (day in 20_000L..20_010L) {
            val order = withFavs.rotated(recency, MixRotation.DAILY, epochDay = day, salt = 0)
            assertThat(order.take(2).map { it.id }).containsExactly(9L, 5L).inOrder()
        }
    }

    @Test fun `barely played mixes come before the rest, after favorites`() {
        val withFav = mixes.map { if (it.id == 1L) mix(1, pinnedAt = 1) else it }
        val unplayed = setOf(3L, 7L, 11L)
        val order = withFav.rotated(recency, MixRotation.DAILY, epochDay = 20_000, salt = 0, unplayed = unplayed)
        assertThat(order.first().id).isEqualTo(1L)
        assertThat(order.drop(1).take(3).map { it.id }.toSet()).isEqualTo(unplayed)
    }

    @Test fun `barely played means under a quarter of its songs played`() {
        val ids = barelyPlayedMixIds(
            listOf(
                PlaylistPlayedShare(playlistId = 1, liveTracks = 20, playedTracks = 0),
                PlaylistPlayedShare(playlistId = 2, liveTracks = 20, playedTracks = 4), // 20 %
                PlaylistPlayedShare(playlistId = 3, liveTracks = 20, playedTracks = 5), // 25 %: played
                PlaylistPlayedShare(playlistId = 4, liveTracks = 0, playedTracks = 0), // nothing live
            ),
        )
        assertThat(ids).containsExactly(1L, 2L)
    }
}
