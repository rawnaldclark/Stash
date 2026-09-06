package com.stash.core.media.service

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_IS_STREAMABLE
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Android Auto browse tree can't be manually tested here (no AA
 * hardware, dated DHU), so these tests pin the exact logic the car sees:
 * which tracks appear/play, and what URI they carry.
 *
 * The original bug: children were gated on the BARE `is_streamable` flag,
 * which defaults to 0 meaning "not checked yet" — so every synced,
 * not-yet-downloaded row vanished ("playlist opens empty in the car") and
 * the survivors carried `filePath ?: ""` empty URIs that errored at play.
 */
@RunWith(RobolectricTestRunner::class)
class AutoBrowseTest {

    private fun track(
        id: Long = 1L,
        downloaded: Boolean = false,
        streamable: Boolean = false,
        checkedAt: Long? = null,
        filePath: String? = null,
    ) = TrackEntity(
        id = id,
        title = "Song",
        artist = "Artist",
        isDownloaded = downloaded,
        isStreamable = streamable,
        isStreamableCheckedAt = checkedAt,
        filePath = filePath,
        youtubeId = "vid$id",
        durationMs = 200_000L,
    )

    // ---- isPlayableInAuto: the truth table from Track.isUnavailableForDisplay ----

    @Test
    fun `downloaded track is playable`() {
        assertThat(track(downloaded = true, filePath = "/m/a.flac").isPlayableInAuto()).isTrue()
    }

    @Test
    fun `confirmed-streamable track is playable`() {
        assertThat(track(streamable = true, checkedAt = 123L).isPlayableInAuto()).isTrue()
    }

    @Test
    fun `never-checked synced track is playable - the empty-playlist bug`() {
        // is_streamable=0 + checked_at=null means "unknown", NOT "unplayable".
        // The bare-flag filter dropped exactly these rows.
        assertThat(track(streamable = false, checkedAt = null).isPlayableInAuto()).isTrue()
    }

    @Test
    fun `confirmed-unstreamable undownloaded track is excluded`() {
        assertThat(track(streamable = false, checkedAt = 123L).isPlayableInAuto()).isFalse()
    }

    // ---- autoPlaybackUri: never an empty URI ----

    @Test
    fun `downloaded track gets its file uri`() {
        val uri = track(downloaded = true, filePath = "/music/a.flac").autoPlaybackUri()
        assertThat(uri.toString()).isEqualTo("file:///music/a.flac")
    }

    @Test
    fun `stream track gets a stash-resolve placeholder carrying resolver inputs`() {
        val uri = track(id = 42L).autoPlaybackUri()
        assertThat(uri.scheme).isEqualTo("stash-resolve")
        assertThat(uri.lastPathSegment).isEqualTo("42")
        assertThat(uri.getQueryParameter("yt")).isEqualTo("vid42")
        assertThat(uri.getQueryParameter("t")).isEqualTo("Song")
        assertThat(uri.getQueryParameter("a")).isEqualTo("Artist")
    }

    @Test
    fun `downloaded row with a missing path falls back to the placeholder not an empty uri`() {
        val uri = track(id = 7L, downloaded = true, filePath = null).autoPlaybackUri()
        assertThat(uri.scheme).isEqualTo("stash-resolve")
    }

    // ---- toAutoMediaItem: identity extras + playable flags ----

    @Test
    fun `auto item carries track id, playable flag, and streaming marker`() {
        val item = track(id = 9L).toAutoMediaItem(mediaId = "AUTOQ_p1_9")

        assertThat(item.mediaId).isEqualTo("AUTOQ_p1_9")
        assertThat(item.mediaMetadata.isPlayable).isTrue()
        assertThat(item.mediaMetadata.isBrowsable).isFalse()
        val extras = item.mediaMetadata.extras!!
        assertThat(extras.getLong(EXTRA_TRACK_ID)).isEqualTo(9L)
        assertThat(extras.getBoolean(EXTRA_TRACK_IS_STREAMABLE)).isTrue()
        assertThat(item.localConfiguration?.uri?.scheme).isEqualTo("stash-resolve")
    }

    @Test
    fun `downloaded auto item is marked non-streaming`() {
        val item = track(downloaded = true, filePath = "/m/b.flac").toAutoMediaItem()
        assertThat(item.mediaMetadata.extras!!.getBoolean(EXTRA_TRACK_IS_STREAMABLE)).isFalse()
        assertThat(item.localConfiguration?.uri?.toString()).isEqualTo("file:///m/b.flac")
    }

    // ---- Liked Songs in the car: ONE entry, every like source merged ----
    // The first head-unit test of #251 listed "Liked Songs" twice: the in-app
    // likes playlist and the synced Spotify likes, both named the same. The
    // Library's Liked tab merges them; the car must too.

    private fun liked(
        id: Long,
        stash: Long? = null,
        spotify: Long? = null,
        yt: Long? = null,
        lastFm: Long? = null,
        added: Long = 0L,
    ) = track(id = id, downloaded = true, filePath = "/m/$id.flac").copy(
        stashLikedAt = stash,
        spotifySavedAt = spotify,
        ytMusicSavedAt = yt,
        lastFmLovedAt = lastFm,
        dateAdded = java.time.Instant.ofEpochMilli(added),
    )

    private fun playlist(type: PlaylistType) = PlaylistEntity(
        name = "Liked Songs", source = MusicSource.SPOTIFY, sourceId = "src-${type.name}", type = type,
    )

    @Test
    fun `liked playlists are the in-app likes and every synced likes list`() {
        assertThat(playlist(PlaylistType.STASH_LIKED).isLikedPlaylist()).isTrue()
        assertThat(playlist(PlaylistType.LIKED_SONGS).isLikedPlaylist()).isTrue()
        assertThat(playlist(PlaylistType.CUSTOM).isLikedPlaylist()).isFalse()
        assertThat(playlist(PlaylistType.STASH_MIX).isLikedPlaylist()).isFalse()
    }

    @Test
    fun `merged likes list every track once, newest like first, add date as the fallback`() {
        val inStash = listOf(liked(1, stash = 5_000), liked(2, stash = 1_000, spotify = 3_000))
        val onSpotify = listOf(liked(2, spotify = 3_000), liked(3, spotify = 4_500), liked(4, added = 6_000))

        val merged = mergeLikedForAuto(listOf(inStash, onSpotify))

        // Same rule as the Library's Liked tab: 4 (added 6000) > 1 (5000) > 3 (4500) > 2 (3000, once)
        assertThat(merged.map { it.id }).containsExactly(4L, 1L, 3L, 2L).inOrder()
    }
}
