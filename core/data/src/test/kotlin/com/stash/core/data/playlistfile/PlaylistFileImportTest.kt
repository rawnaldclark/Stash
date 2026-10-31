package com.stash.core.data.playlistfile

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.link.LinkKind
import com.stash.core.data.weblink.handoff.WireSong
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
}
