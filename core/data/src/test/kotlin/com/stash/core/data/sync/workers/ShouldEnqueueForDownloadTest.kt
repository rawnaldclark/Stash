package com.stash.core.data.sync.workers

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.PlaylistType
import org.junit.Test

/**
 * Unit test for [shouldEnqueueForDownload] — the enqueue-side guard that keeps
 * algorithmic mixes (DAILY_MIX) surface-only (stream-on-tap) even in Offline
 * mode, so an auto-enabled mix never pulls bytes after the user switches to
 * Offline and re-syncs. Non-mix playlists still download when Offline.
 */
class ShouldEnqueueForDownloadTest {
    @Test fun `offline non-mix playlists download`() {
        assertThat(shouldEnqueueForDownload(PlaylistType.CUSTOM, streamingMode = false)).isTrue()
        assertThat(shouldEnqueueForDownload(PlaylistType.LIKED_SONGS, streamingMode = false)).isTrue()
    }
    @Test fun `offline daily mix is surface-only`() {
        assertThat(shouldEnqueueForDownload(PlaylistType.DAILY_MIX, streamingMode = false)).isFalse()
    }
    // Only DAILY_MIX is surface-only. STASH_MIX (locally generated, no auto-enable
    // gap) still downloads offline — pin it so a future "isMixLike()" refactor
    // can't silently stop it. Parity with DefaultSyncEnabledTest's STASH_MIX guard.
    @Test fun `offline stash mix still downloads`() {
        assertThat(shouldEnqueueForDownload(PlaylistType.STASH_MIX, streamingMode = false)).isTrue()
    }
    @Test fun `online never enqueues`() {
        assertThat(shouldEnqueueForDownload(PlaylistType.CUSTOM, streamingMode = true)).isFalse()
        assertThat(shouldEnqueueForDownload(PlaylistType.DAILY_MIX, streamingMode = true)).isFalse()
    }
}
