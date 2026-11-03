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

    @Test fun `SoundCloud car queue marks manifest routing while a downloaded copy keeps its file URI`() {
        val upload = track().copy(source = MusicSource.SOUNDCLOUD, soundCloudId = "123")
        assertThat(upload.autoPlaybackUri().getQueryParameter("sc")).isEqualTo("1")
        val local = upload.copy(isDownloaded = true, filePath = "/music/existing.flac")
        assertThat(local.autoPlaybackUri().scheme).isEqualTo("file")
    }

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

    // ---- autoSearchMatchQuery: car search text never reaches MATCH raw ----

    @Test
    fun `each word becomes a quoted prefix match`() {
        assertThat(autoSearchMatchQuery("  my bloody ")).isEqualTo("\"my*\" \"bloody*\"")
    }

    @Test
    fun `quotes, parentheses and operator words cannot break or steer the MATCH`() {
        // Was `foo"*`, `(live*`, `NOT*`: a malformed MATCH expression, or an operator.
        assertThat(autoSearchMatchQuery("foo\" (live NOT")).isEqualTo("\"foo*\" \"live*\" \"NOT*\"")
        assertThat(autoSearchMatchQuery("AC/DC")).isEqualTo("\"AC*\" \"DC*\"")
    }

    @Test
    fun `nothing searchable left is no query`() {
        assertThat(autoSearchMatchQuery(" \"() ")).isNull()
    }

    // ---- Saved albums (item 5, 2026-11-02): under Albums, never among the Playlists ----

    private fun row(source: MusicSource, sourceId: String) = PlaylistEntity(
        name = "X", source = source, sourceId = sourceId, type = PlaylistType.CUSTOM,
    )

    @Test
    fun `a saved album from any service is no playlist in the car, a playlist is`() {
        assertThat(row(MusicSource.SPOTIFY, "album:spotify:FakeAlb1xxxxxxxxxxxxxx").isSavedAlbumForAuto()).isTrue()
        assertThat(row(MusicSource.BOTH, "album:qobuz:0886446224536").isSavedAlbumForAuto()).isTrue()
        assertThat(row(MusicSource.YOUTUBE, "MPREb_fake").isSavedAlbumForAuto()).isTrue()
        assertThat(row(MusicSource.SPOTIFY, "37i9dQZF1DXcBWIGoYBM5M").isSavedAlbumForAuto()).isFalse()
        assertThat(row(MusicSource.BOTH, "album:qobuz_playlist:123").isSavedAlbumForAuto()).isFalse()
    }

    private fun albumRow(id: Long, sourceId: String, tracks: Int, downloaded: Int, source: MusicSource = MusicSource.SPOTIFY) =
        com.stash.core.data.db.dao.SavedAlbumRow(
            id = id, name = "Album", source = source, sourceId = sourceId, artUrl = null, dateAdded = id,
            trackCount = tracks, downloadedCount = downloaded, explicitCount = 0,
            artists = "Artist", titles = (1..tracks).joinToString(com.stash.core.data.db.dao.SAVED_ALBUM_LIST_SEPARATOR.toString()) { "Song $it" },
        )

    @Test
    fun `the car's Albums are the downloaded ones, an album saved twice listed once`() {
        val albums = albumsForAuto(
            listOf(
                albumRow(1, "album:spotify:FakeAlb1xxxxxxxxxxxxxx", tracks = 3, downloaded = 3),
                // The same album saved from YouTube Music too: one entry.
                albumRow(2, "MPREb_fake", tracks = 3, downloaded = 0, source = MusicSource.YOUTUBE),
                // Not on the phone yet: the car can't play it.
                albumRow(3, "album:spotify:FakeAlb2xxxxxxxxxxxxxx", tracks = 2, downloaded = 1).copy(name = "Other"),
            ),
        )

        assertThat(albums.map { it.playlistId }).containsExactly(1L)
    }

    @Test
    fun `the car's Albums stop at the cap, newest saved first`() {
        // Rows come newest saved first (observeSavedAlbums); each a different album, all on the phone.
        // Their names sort another way, so neither a sort by name nor one by id passes.
        val names = listOf("Mango", "Zebra", "Apple", "Kiwi", "Banana")
        val rows = listOf(3L, 1L, 5L, 2L, 4L).mapIndexed { i, id ->
            albumRow(id, "album:spotify:Alb$id", tracks = 1, downloaded = 1).copy(name = names[i])
        }

        assertThat(albumsForAuto(rows, limit = 3).map { it.playlistId }).containsExactly(3L, 1L, 5L).inOrder()
        assertThat(MAX_AUTO_ALBUMS).isAtMost(1000)
    }

    @Test
    fun `an album's song and the copy linked beside it are one row in the car, in album order`() {
        val own = track(id = 10).copy(title = "One")
        val copy = track(id = 11, downloaded = true, filePath = "/m/one.flac").copy(title = "One")
        val two = track(id = 12).copy(title = "Two")
        val slots = listOf(
            com.stash.core.data.db.dao.SavedAlbumSlot(10, 0, "one", "artist"),
            com.stash.core.data.db.dao.SavedAlbumSlot(11, 0, "one", "artist"),
            com.stash.core.data.db.dao.SavedAlbumSlot(12, 1, "two", "artist"),
        )

        val rows = oneRowPerSongForAuto(listOf(own, copy, two), slots)

        assertThat(rows.map { it.id }).containsExactly(11L, 12L).inOrder()
        assertThat(oneRowPerSongForAuto(listOf(own, copy, two), emptyList())).hasSize(3)
    }
}
