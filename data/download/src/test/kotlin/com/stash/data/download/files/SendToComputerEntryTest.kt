package com.stash.data.download.files

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.TrackEntity
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** What the list tells the computer of each song: enough to find it, and to choose it by. */
class SendToComputerEntryTest {

    private val track = TrackEntity(
        id = 76,
        title = "Song",
        artist = "Band",
        album = "Album",
        durationMs = 200_000L,
        canonicalTitle = "song",
        canonicalArtist = "band",
        filePath = "/data/music/76.flac",
        isDownloaded = true,
        fileFormat = "flac",
        bitsPerSample = 24,
        sampleRateHz = 96000,
    )

    @Test fun `a song's playlists go with it, each once, blanks left out`() {
        val e = SendToComputerCoordinator.entry(track, "76.flac", 10, listOf("Gym", " Road trip ", "Gym", " "))
        assertThat(e["playlists"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("Gym", "Road trip").inOrder()
        assertThat(e["format"]!!.jsonPrimitive.content).isEqualTo("flac")
        assertThat(e["bits"]!!.jsonPrimitive.content).isEqualTo("24")
        assertThat(e["sample_rate"]!!.jsonPrimitive.content).isEqualTo("96000")
    }

    @Test fun `a song in no playlist says none`() {
        assertThat(SendToComputerCoordinator.entry(track, "76.flac", 10).containsKey("playlists")).isFalse()
    }

    @Test fun `a send with no room says how much more it needs and where room hides`() {
        assertThat(SendToComputerCoordinator.noRoom(3_400_000_000L)).contains("3.4 GB more")
        assertThat(SendToComputerCoordinator.noRoom(250_000_000L)).contains("250 MB more")
        assertThat(SendToComputerCoordinator.noRoom(1L)).contains("1 MB more")
        assertThat(SendToComputerCoordinator.noRoom(1L)).contains("Trash")
    }
}
