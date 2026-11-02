package com.stash.core.data.album

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.sync.workers.pinOnDiscovery
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import org.junit.Test

/**
 * The album rules behind Library › Albums (owner, 2026-11-02): what a saved album is, when two
 * saved copies are one album, which copy a card keeps, and what Download mode lists.
 */
class SavedAlbumsTest {

    // ── What a saved album is ───────────────────────────────────────────

    @Test fun `every way an album is saved today is a saved album`() {
        val custom = PlaylistType.CUSTOM
        // Save on an album page (YouTube, Qobuz) and a pasted album link (Spotify, YouTube).
        assertThat(SavedAlbums.isSavedAlbum(custom, MusicSource.BOTH, "album:youtube:MPREb_abc")).isTrue()
        assertThat(SavedAlbums.isSavedAlbum(custom, MusicSource.BOTH, "album:qobuz:0060253780")).isTrue()
        assertThat(SavedAlbums.isSavedAlbum(custom, MusicSource.BOTH, "album:spotify:4m2880jivSbbyEGAKfITCa")).isTrue()
        // YouTube Music's saved albums, synced.
        assertThat(SavedAlbums.isSavedAlbum(custom, MusicSource.YOUTUBE, "MPREb_abc")).isTrue()
        // Item 5: Spotify's saved albums, synced under the same key a pasted link uses.
        assertThat(SavedAlbums.isSavedAlbum(custom, MusicSource.SPOTIFY, SavedAlbums.sourceId("spotify", "x"))).isTrue()
    }

    @Test fun `playlists, mixes and a saved Qobuz playlist are not albums`() {
        assertThat(SavedAlbums.isSavedAlbum(PlaylistType.CUSTOM, MusicSource.BOTH, "album:qobuz_playlist:123")).isFalse()
        assertThat(SavedAlbums.isSavedAlbum(PlaylistType.CUSTOM, MusicSource.BOTH, "custom_link_yt_PL123")).isFalse()
        assertThat(SavedAlbums.isSavedAlbum(PlaylistType.CUSTOM, MusicSource.BOTH, "custom_9f1c")).isFalse()
        assertThat(SavedAlbums.isSavedAlbum(PlaylistType.CUSTOM, MusicSource.SPOTIFY, "37i9dQZF1DX0XUsuxWHRQd")).isFalse()
        assertThat(SavedAlbums.isSavedAlbum(PlaylistType.CUSTOM, MusicSource.YOUTUBE, "PLabc")).isFalse()
        // A browse id is an album only when YouTube synced it.
        assertThat(SavedAlbums.isSavedAlbum(PlaylistType.CUSTOM, MusicSource.BOTH, "MPREb_abc")).isFalse()
        assertThat(SavedAlbums.isSavedAlbum(PlaylistType.DAILY_MIX, MusicSource.BOTH, "album:youtube:MPREb_abc")).isFalse()
    }

    @Test fun `a synced album and its Stash-saved copy are one album on one service`() {
        assertThat(SavedAlbums.serviceKey(MusicSource.YOUTUBE, "MPREb_abc")).isEqualTo("youtube:MPREb_abc")
        assertThat(SavedAlbums.serviceKey(MusicSource.BOTH, "album:youtube:MPREb_abc")).isEqualTo("youtube:MPREb_abc")
        // Catalog ids are case-sensitive: only the service is folded.
        assertThat(SavedAlbums.serviceKey(MusicSource.BOTH, "album:Spotify:AbC")).isEqualTo("spotify:AbC")
        assertThat(SavedAlbums.serviceKey(MusicSource.BOTH, "custom_1")).isNull()
    }

    /** A playlist sync finds for the first time, after "Stream everything", in Stream only. */
    private fun pinOnDiscoveryForTest(type: PlaylistType, source: MusicSource, sourceId: String) =
        pinOnDiscovery(type, streamingMode = true, pinNewPlaylists = true, source = source, sourceId = sourceId)

    @Test fun `Home never pins a synced saved album`() {
        assertThat(pinOnDiscoveryForTest(PlaylistType.CUSTOM, MusicSource.YOUTUBE, "MPREb_abc")).isFalse()
        assertThat(pinOnDiscoveryForTest(PlaylistType.CUSTOM, MusicSource.SPOTIFY, "album:spotify:x")).isFalse()
        assertThat(pinOnDiscoveryForTest(PlaylistType.CUSTOM, MusicSource.YOUTUBE, "PLabc")).isTrue()
    }

    // ── Names, songs, artists ───────────────────────────────────────────

    @Test fun `names meet across spellings but an edition is another release`() {
        assertThat(SavedAlbums.nameKey("Ocean Rain", "Echo & the Bunnymen"))
            .isEqualTo(SavedAlbums.nameKey("ocean rain", "Echo and The Bunnymen"))
        assertThat(SavedAlbums.nameKey("Été", "Mylène Farmer")).isEqualTo(SavedAlbums.nameKey("Ete", "Mylene Farmer"))
        // The lead act of a collaboration credit.
        assertThat(SavedAlbums.nameKey("Heroes & Villains", "Metro Boomin, Future"))
            .isEqualTo(SavedAlbums.nameKey("Heroes & Villains", "Metro Boomin"))
        assertThat(SavedAlbums.nameKey("Abbey Road (Super Deluxe)", "The Beatles"))
            .isNotEqualTo(SavedAlbums.nameKey("Abbey Road", "The Beatles"))
    }

    @Test fun `a song is named the same way on both services`() {
        assertThat(SavedAlbums.songKey("Waiting Room - Remastered 2004")).isEqualTo(SavedAlbums.songKey("Waiting Room"))
        assertThat(SavedAlbums.songKey("Burning (feat. X)")).isEqualTo(SavedAlbums.songKey("burning"))
        assertThat(SavedAlbums.songKey("Don’t Stop")).isEqualTo(SavedAlbums.songKey("Don't Stop"))
    }

    @Test fun `an album is by the act most of its songs credit first`() {
        assertThat(SavedAlbums.leadArtist(listOf("Metro Boomin, Future", "Metro Boomin, Travis Scott", "Don Toliver")))
            .isEqualTo("Metro Boomin")
        // A tie goes to the first act A to Z, whatever order the songs come in.
        assertThat(SavedAlbums.leadArtist(listOf("Fugazi", "Ian MacKaye"))).isEqualTo("Fugazi")
        assertThat(SavedAlbums.leadArtist(listOf("Ian MacKaye", "Fugazi"))).isEqualTo("Fugazi")
        assertThat(SavedAlbums.leadArtist(emptyList())).isEmpty()
    }

    // ── One card per album ──────────────────────────────────────────────

    private val thirteenSongs = listOf(
        "Waiting Room", "Bulldog Front", "Bad Mouth", "Burning", "Give Me the Cure", "Suggestion",
        "Glue Man", "Margin Walker", "And the Same", "Burning Too", "Provisional", "Lockdown", "Promises",
    )

    private fun album(
        id: Long,
        title: String = "13 Songs",
        artist: String = "Fugazi",
        source: MusicSource = MusicSource.BOTH,
        sourceId: String = "album:qobuz:$id",
        songs: List<String> = thirteenSongs,
        downloaded: Int = 0,
        downloading: Int = 0,
        dateAdded: Long = id,
    ) = SavedAlbum(
        playlistId = id, title = title, artist = artist, artUrl = null, source = source, sourceId = sourceId,
        dateAdded = dateAdded, trackCount = songs.size, downloadedCount = downloaded, downloadingCount = downloading,
        songKeys = songs.mapTo(LinkedHashSet()) { SavedAlbums.songKey(it) },
    )

    @Test fun `the same album saved on two services is one card, the copy on the phone kept`() {
        val youtube = album(1, source = MusicSource.YOUTUBE, sourceId = "MPREb_13songs", downloaded = 13)
        val qobuz = album(2, sourceId = "album:qobuz:13", dateAdded = 99)

        val cards = SavedAlbums.oneCardPerAlbum(listOf(qobuz, youtube))

        assertThat(cards.map { it.playlistId }).containsExactly(1L)
    }

    @Test fun `a copy missing a song meets the full one, and the fuller copy wins`() {
        val partial = album(1, songs = thirteenSongs.dropLast(1), sourceId = "MPREb_a", source = MusicSource.YOUTUBE, dateAdded = 50)
        val full = album(2, title = "13 songs", artist = "FUGAZI", sourceId = "album:spotify:b")

        assertThat(SavedAlbums.oneCardPerAlbum(listOf(partial, full)).map { it.playlistId }).containsExactly(2L)
    }

    @Test fun `albums that only share a name stay apart`() {
        // Weezer's Blue and Green albums are both "Weezer" by Weezer.
        val blue = album(1, title = "Weezer", artist = "Weezer", songs = listOf("My Name Is Jonas", "No One Else", "Buddy Holly", "Say It Ain't So"))
        val green = album(2, title = "Weezer", artist = "Weezer", songs = listOf("Don't Let Go", "Photograph", "Hash Pipe", "Island in the Sun"))

        assertThat(SavedAlbums.oneCardPerAlbum(listOf(blue, green)).map { it.playlistId }).containsExactly(1L, 2L).inOrder()
    }

    @Test fun `a synced album and the same album saved in Stash meet by id, whatever their names`() {
        val synced = album(1, title = "13 Songs", source = MusicSource.YOUTUBE, sourceId = "MPREb_x", songs = listOf("a"))
        val saved = album(2, title = "Thirteen Songs", sourceId = "album:youtube:MPREb_x", songs = listOf("b"), dateAdded = 9)

        assertThat(SavedAlbums.oneCardPerAlbum(listOf(synced, saved)).map { it.playlistId }).containsExactly(2L)
    }

    // ── Download mode ───────────────────────────────────────────────────

    @Test fun `Download mode lists an album once every song is on the phone, or while it downloads`() {
        val complete = album(1, title = "Repeater", downloaded = 13)
        val half = album(2, title = "In on the Kill Taker", downloaded = 6)
        val downloading = album(3, title = "Red Medicine", downloaded = 9, downloading = 4)
        val empty = album(4, title = "Steady Diet", songs = emptyList())

        val download = SavedAlbums.forLibrary(listOf(complete, half, downloading, empty), streamOnly = false)
        val stream = SavedAlbums.forLibrary(listOf(complete, half, downloading, empty), streamOnly = true)

        assertThat(download.map { it.playlistId }).containsExactly(1L, 3L).inOrder()
        assertThat(download.single { it.playlistId == 3L }.downloading).isTrue()
        // Stream only lists every saved album with songs.
        assertThat(stream.map { it.playlistId }).containsExactly(1L, 2L, 3L).inOrder()
    }

    @Test fun `in Download mode the downloaded copy keeps a merged album listed`() {
        val onPhone = album(1, source = MusicSource.YOUTUBE, sourceId = "MPREb_x", downloaded = 13, dateAdded = 1)
        val newer = album(2, sourceId = "album:qobuz:x", dateAdded = 100)

        assertThat(SavedAlbums.forLibrary(listOf(newer, onPhone), streamOnly = false).map { it.playlistId }).containsExactly(1L)
    }

    // ── The album page's "Saved" ────────────────────────────────────────

    @Test fun `an album page finds its saved copy by key, by synced id, or by name and songs`() {
        val synced = album(1, source = MusicSource.YOUTUBE, sourceId = "MPREb_x")
        val spotify = album(2, sourceId = "album:spotify:sp", title = "Repeater")

        // The page's own key, from a synced YouTube Music album.
        assertThat(SavedAlbums.findSaved(listOf(synced), listOf("album:youtube:MPREb_x"), "", "", emptyList())?.playlistId).isEqualTo(1L)
        // A Qobuz page for an album saved from Spotify: same title, artist and songs.
        assertThat(
            SavedAlbums.findSaved(listOf(spotify), listOf("album:qobuz:q"), "Repeater", "Fugazi", thirteenSongs)?.playlistId,
        ).isEqualTo(2L)
        // While the page loads (no songs yet) only a key can tell.
        assertThat(SavedAlbums.findSaved(listOf(spotify), listOf("album:qobuz:q"), "Repeater", "Fugazi", emptyList())).isNull()
        // Another album of the same name is not this one.
        assertThat(SavedAlbums.findSaved(listOf(spotify), listOf("album:qobuz:q"), "Repeater", "Fugazi", listOf("Other", "Songs"))).isNull()
    }

    // ── One row per song ────────────────────────────────────────────────

    @Test fun `a copy already on the phone, linked beside the song, is the song, shown where it sits`() {
        // Download linked the phone's copy (id 20) of song 2 at song 2's place; song 2's own row stays.
        val rows = listOf(song(1), song(2), song(20, downloaded = true), song(3))
        val slots = listOf(slot(1, 0, "waiting room"), slot(2, 1, "bulldog front"), slot(20, 1, "bulldog front"), slot(3, 2, "bad mouth"))

        assertThat(SavedAlbums.oneRowPerSong(rows, slots).map { it.id }).containsExactly(1L, 20L, 3L).inOrder()
    }

    @Test fun `two songs never become one, and a playlist that isn't an album keeps every row`() {
        // Same place, other words: two songs (a tie in positions never hides one).
        val rows = listOf(song(1), song(2))
        assertThat(SavedAlbums.oneRowPerSong(rows, listOf(slot(1, 0, "burning"), slot(2, 0, "glue man"))).map { it.id })
            .containsExactly(1L, 2L).inOrder()
        // No slots: not a saved album.
        assertThat(SavedAlbums.oneRowPerSong(rows, emptyList())).isEqualTo(rows)
        // Neither copy on the phone: the song's own row (the first) stays.
        assertThat(SavedAlbums.oneRowPerSong(rows, listOf(slot(1, 0, "burning"), slot(2, 0, "burning"))).map { it.id })
            .containsExactly(1L)
    }

    private fun song(id: Long, downloaded: Boolean = false) =
        com.stash.core.model.Track(id = id, title = "song $id", artist = "Fugazi", isDownloaded = downloaded)

    private fun slot(trackId: Long, position: Int, title: String) =
        com.stash.core.data.db.dao.SavedAlbumSlot(trackId, position, title, "fugazi")
}
