package com.stash.core.data.sync

import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType

/**
 * The mixes that can stack (keep every song they bring, [com.stash.core.data.db.entity.PlaylistEntity.stackSongs]):
 * the ones a service regenerates around the listener's taste, where piling weeks up builds a
 * library. Spotify's Daily Mix N and Discover Weekly, YouTube Music's My Mix N and Discover Mix
 * (owner, 2026-10-29). Radios, genre and decade mixes, Release Radar, Replay and the yearly
 * recaps refresh: stacking those collects noise, not taste.
 *
 * By name: these are the services' fixed English names (the same names the Spotify mix filter
 * keys on). Another locale's names fall outside, so those mixes simply refresh.
 */
object MixStacking {
    private val SPOTIFY_DAILY_MIX = Regex("""Daily Mix \d+""")
    private val YOUTUBE_MY_MIX = Regex("""My Mix \d+""")

    fun isStackable(source: MusicSource, type: PlaylistType, name: String): Boolean {
        if (type != PlaylistType.DAILY_MIX) return false
        val n = name.trim()
        return when (source) {
            MusicSource.SPOTIFY -> SPOTIFY_DAILY_MIX.matches(n) || n.equals("Discover Weekly", ignoreCase = true)
            MusicSource.YOUTUBE -> YOUTUBE_MY_MIX.matches(n) || n.equals("Discover Mix", ignoreCase = true)
            else -> false
        }
    }

    /**
     * Whether a mix shows the Stack switch: one that can stack, or one stacking already. A mix an accumulating account had
     * before the switch existed (Release Radar, My Supermix, a Daily Mix under another locale's name) kept stacking on the
     * upgrade (review on #737), so it keeps the switch to turn that off.
     */
    fun showsSwitch(source: MusicSource, type: PlaylistType, name: String, stackSongs: Boolean): Boolean =
        type == PlaylistType.DAILY_MIX && (stackSongs || isStackable(source, type, name))
}
