package com.stash.core.data.playlistfile

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.link.LinkKind
import com.stash.core.data.weblink.handoff.WireSong
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Issue #61: what a picked playlist file becomes in the link import's preview, and the playlist it is added to. */
class PlaylistFileImportTest {
    private val songs = listOf(WireSong("Creep", "Radiohead"))

    @Test fun `a playlist is named by its file when the file gives no name`() {
        assertThat(PlaylistFileImport.nameOf("Late Night Drive.m3u8")).isEqualTo("Late Night Drive")
        // Exportify's file for "Road Trip", and the browser's second download of it.
        assertThat(PlaylistFileImport.nameOf("road_trip.csv")).isEqualTo("Road trip")
        assertThat(PlaylistFileImport.nameOf("road_trip (1).csv")).isEqualTo("Road trip")
        assertThat(PlaylistFileImport.nameOf("Mix Vol. 2.txt")).isEqualTo("Mix Vol. 2")
        assertThat(PlaylistFileImport.nameOf(".csv")).isNull()
        assertThat(PlaylistFileImport.nameOf(null)).isNull()
    }

    @Test fun `the same name finds the same playlist however the file writes it, and another name another`() {
        val id = PlaylistFileImport.sourceIdOf("Road Trip")
        assertThat(id).startsWith("custom_link_file_")
        assertThat(id).hasLength("custom_link_file_".length + 24)
        assertThat(PlaylistFileImport.sourceIdOf("road_trip")).isEqualTo(id)
        assertThat(PlaylistFileImport.sourceIdOf("ROAD TRIP!")).isEqualTo(id)
        assertThat(PlaylistFileImport.sourceIdOf("Road Trip 2")).isNotEqualTo(id)
        // A name of symbols alone is still a name.
        assertThat(PlaylistFileImport.sourceIdOf("🔥")).isNotEqualTo(PlaylistFileImport.sourceIdOf("✨"))
    }

    @Test fun `the preview is a playlist from a file, named by the file's own title first`() {
        val content = PlaylistFileContent("Sunday & Chill", PlaylistFileFormat.XSPF, songs, cut = false)
        val preview = PlaylistFileImport.preview(content, "export.xspf")
        assertThat(preview.kind).isEqualTo(LinkKind.PLAYLIST)
        assertThat(preview.name).isEqualTo("Sunday & Chill")
        assertThat(preview.fromFile).isTrue()
        assertThat(preview.byline).isNull()
        assertThat(preview.coverUrl).isNull()
        assertThat(preview.songs).isEqualTo(songs)
        assertThat(preview.partial).isFalse()
        assertThat(preview.sourceId).isEqualTo(PlaylistFileImport.sourceIdOf("Sunday & Chill"))
    }

    @Test fun `a file without a title is named by its file, or plainly when it has no name either, and a cut one says so`() {
        val content = PlaylistFileContent(null, PlaylistFileFormat.CSV, songs, cut = true)
        assertThat(PlaylistFileImport.preview(content, "road_trip.csv").name).isEqualTo("Road trip")
        assertThat(PlaylistFileImport.preview(content, "road_trip.csv").partial).isTrue()
        assertThat(PlaylistFileImport.preview(content, null).name).isEqualTo(PlaylistFileImport.UNNAMED)
    }

    @Test fun `a title every playlist gets names none, so VLC's Playlist, in any language, gives way to the file's name`() {
        fun named(title: String?, file: String?) =
            PlaylistFileImport.nameFor(PlaylistFileContent(title, PlaylistFileFormat.XSPF, songs, cut = false), file)
        assertThat(named("Playlist", "Gym.xspf")).isEqualTo("Gym")
        assertThat(named("Liste de lecture", "Sommeil.xspf")).isEqualTo("Sommeil")
        assertThat(named("Wiedergabeliste", "Laufen.xspf")).isEqualTo("Laufen")
        assertThat(named("Untitled playlist 2", "Drive.m3u")).isEqualTo("Drive")
        assertThat(named("Road Trip", "export.csv")).isEqualTo("Road Trip")
        // With no file name, the title is still the best name there is.
        assertThat(named("Playlist", null)).isEqualTo("Playlist")
        assertThat(PlaylistFileImport.isGeneric("Плейлист")).isTrue()
        assertThat(PlaylistFileImport.isGeneric("Playlist for Sunday")).isFalse()
    }

    @Test fun `two VLC files are two playlists, though VLC titles both Playlist`() = runTest {
        fun vlc(vararg tracks: Pair<String, String>) = """<?xml version="1.0" encoding="UTF-8"?>
<playlist xmlns="http://xspf.org/ns/0/" xmlns:vlc="http://www.videolan.org/vlc/playlist/ns/0/" version="1">
	<title>Playlist</title>
	<trackList>
${tracks.joinToString("\n") { (artist, title) -> "\t\t<track><location>file:///sdcard/Music/x.mp3</location><title>$title</title><creator>$artist</creator></track>" }}
	</trackList>
</playlist>
""".toByteArray(Charsets.UTF_8)
        val gym = PlaylistFileParser.parse(vlc("Daft Punk" to "Harder, Better, Faster, Stronger", "Kanye West" to "Stronger"), "Gym.xspf")
        val sleep = PlaylistFileParser.parse(vlc("Brian Eno" to "An Ending (Ascent)", "Nils Frahm" to "Says"), "Sleep.xspf")
        assertThat(gym.title).isEqualTo("Playlist")

        // Named by their files ("Gym", "Sleep"), they are apart from the start.
        assertThat(PlaylistFileImport.preview(gym, "Gym.xspf").name).isEqualTo("Gym")
        assertThat(PlaylistFileImport.preview(gym, "Gym.xspf").sourceId)
            .isNotEqualTo(PlaylistFileImport.preview(sleep, "Sleep.xspf").sourceId)

        // Both saved under VLC's own file name, they share a name, and their songs keep them apart.
        val base = PlaylistFileImport.preview(gym, "playlist.xspf").sourceId
        assertThat(PlaylistFileImport.preview(sleep, "playlist.xspf").sourceId).isEqualTo(base)
        val held = HashMap<String, PlaylistFileImport.Held>()
        suspend fun place(file: PlaylistFileContent) = PlaylistFileImport.placeOf(base, file.songs) { held[it] }
        assertThat(place(gym)).isEqualTo(base)
        held[base] = PlaylistFileImport.Held(gym.songs)
        assertThat(place(sleep)).isEqualTo("${base}_2")
        held["${base}_2"] = PlaylistFileImport.Held(sleep.songs)

        // Gym again, and a newer Gym that grew, go into Gym; Sleep again into Sleep.
        assertThat(place(gym)).isEqualTo(base)
        assertThat(place(gym.copy(songs = gym.songs + (1..6).map { WireSong("Song $it", "Artist $it") }))).isEqualTo(base)
        assertThat(place(sleep)).isEqualTo("${base}_2")

        // A playlist no longer shown is passed over, never added to.
        assertThat(PlaylistFileImport.placeOf("x", gym.songs) { if (it == "x") PlaylistFileImport.Held(null) else null }).isEqualTo("x_2")
    }

    @Test fun `one list is another when half of the shorter one's songs are in both`() {
        val four = listOf(WireSong("A", "X"), WireSong("B", "X"), WireSong("C", "X"), WireSong("D", "X"))
        val others = listOf(WireSong("E", "X"), WireSong("F", "X"), WireSong("G", "X"))
        assertThat(PlaylistFileImport.sameList(four, four.take(2) + others.take(2))).isTrue()
        assertThat(PlaylistFileImport.sameList(four, four.take(1) + others)).isFalse()
        // An empty playlist of the name takes the songs.
        assertThat(PlaylistFileImport.sameList(emptyList(), four)).isTrue()
    }
}
