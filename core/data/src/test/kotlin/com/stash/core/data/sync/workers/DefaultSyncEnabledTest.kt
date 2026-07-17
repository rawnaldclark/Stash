package com.stash.core.data.sync.workers

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.PlaylistType
import org.junit.Test

/**
 * Unit test for [defaultSyncEnabled] — the one-line decision that makes
 * newly-discovered algorithmic mixes surface immediately in Online mode
 * while everything else (and everything in Offline mode) stays opt-in.
 */
class DefaultSyncEnabledTest {

    @Test
    fun `daily mix enabled only in online mode`() {
        assertThat(defaultSyncEnabled(PlaylistType.DAILY_MIX, online = true)).isTrue()
        assertThat(defaultSyncEnabled(PlaylistType.DAILY_MIX, online = false)).isFalse()
    }

    @Test
    fun `custom playlist always opt-in`() {
        assertThat(defaultSyncEnabled(PlaylistType.CUSTOM, online = true)).isFalse()
        assertThat(defaultSyncEnabled(PlaylistType.CUSTOM, online = false)).isFalse()
    }

    @Test
    fun `liked songs always opt-in`() {
        assertThat(defaultSyncEnabled(PlaylistType.LIKED_SONGS, online = true)).isFalse()
    }

    // STASH_MIX is a "mix" by name and sits next to DAILY_MIX in findOrCreatePlaylist's
    // art-refresh logic — guard against a future refactor broadening the check to
    // "mix-like" types and accidentally auto-enabling locally-generated mixes.
    @Test
    fun `stash mix always opt-in`() {
        assertThat(defaultSyncEnabled(PlaylistType.STASH_MIX, online = true)).isFalse()
    }
}
