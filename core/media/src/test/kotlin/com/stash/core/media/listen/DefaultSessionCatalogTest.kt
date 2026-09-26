package com.stash.core.media.listen

import androidx.media3.common.MediaItem
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.radio.RadioSeed
import com.stash.core.data.radio.RadioSession
import com.stash.core.data.radio.RadioStationGenerator
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.Track
import com.stash.core.model.share.SharedTrack
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DefaultSessionCatalogTest {
    private val musicRepository: MusicRepository = mockk()
    private val trackDao: TrackDao = mockk()
    private val radio: RadioStationGenerator = mockk()
    private val catalog = DefaultSessionCatalog(musicRepository, trackDao, radio)
    private val descriptor = SharedTrack("Avril 14th", "Aphex Twin", youtubeId = "yt1")

    @Test fun `a listener plays the exact-persisted row, streamed when its download has gone`() = runTest {
        coEvery { musicRepository.ensureExactTrackPersisted(descriptor) } returns 7
        coEvery { trackDao.getById(7) } returns TrackEntity(
            id = 7, title = "Avril 14th", artist = "Aphex Twin", youtubeId = "yt1", isDownloaded = true, filePath = "/nope/missing.flac",
        )
        val item = catalog.mediaItemFor(descriptor)!!
        assertThat(item.mediaId).isEqualTo("7")
        assertThat(item.localConfiguration!!.uri.scheme).isEqualTo("stash-resolve")
    }

    @Test fun `a download whose length differs from the host's by more than 2 s streams instead`() = runTest {
        val file = File.createTempFile("edit", ".flac").apply { writeBytes(ByteArray(16)); deleteOnExit() }
        val timed = descriptor.copy(durationMs = 260_000)
        coEvery { musicRepository.ensureExactTrackPersisted(timed) } returns 7
        coEvery { trackDao.getById(7) } returns TrackEntity(
            id = 7, title = "Avril 14th", artist = "Aphex Twin", youtubeId = "yt1",
            durationMs = 200_000, isDownloaded = true, filePath = file.absolutePath,
        )
        val item = catalog.mediaItemFor(timed)!!
        assertThat(item.mediaId).isEqualTo("7")
        assertThat(item.localConfiguration!!.uri.scheme).isEqualTo("stash-resolve")
    }

    @Test fun `a download of a different edit streams the host's recording, not this phone's`() = runTest {
        val file = File.createTempFile("edit", ".flac").apply { writeBytes(ByteArray(16)); deleteOnExit() }
        val timed = descriptor.copy(durationMs = 260_000, isrc = "HOSTISRC")
        coEvery { musicRepository.ensureExactTrackPersisted(timed) } returns 7
        coEvery { trackDao.getById(7) } returns TrackEntity(
            id = 7, title = "Avril 14th", artist = "Aphex Twin", youtubeId = "mine", isrc = "MYISRC",
            durationMs = 200_000, isDownloaded = true, filePath = file.absolutePath,
        )
        val uri = catalog.mediaItemFor(timed)!!.localConfiguration!!.uri
        assertThat(uri.getQueryParameter("yt")).isEqualTo("yt1")
        assertThat(uri.getQueryParameter("isrc")).isEqualTo("HOSTISRC")
        assertThat(uri.getQueryParameter("d")).isEqualTo("260000")
    }

    @Test fun `a download plays locally when the host's length is unknown`() = runTest {
        val file = File.createTempFile("any", ".flac").apply { writeBytes(ByteArray(16)); deleteOnExit() }
        coEvery { musicRepository.ensureExactTrackPersisted(descriptor) } returns 7
        coEvery { trackDao.getById(7) } returns TrackEntity(
            id = 7, title = "Avril 14th", artist = "Aphex Twin", youtubeId = "yt1",
            durationMs = 200_000, isDownloaded = true, filePath = file.absolutePath,
        )
        assertThat(catalog.mediaItemFor(descriptor)!!.localConfiguration!!.uri.scheme).isNotEqualTo("stash-resolve")
    }

    @Test fun `a descriptor with no ids inserts its row once`() = runTest {
        val bare = SharedTrack("Untitled", "Nobody")
        coEvery { musicRepository.ensureExactTrackPersisted(bare) } returns 11
        coEvery { trackDao.getById(11) } returns TrackEntity(id = 11, title = "Untitled", artist = "Nobody")
        catalog.mediaItemFor(bare); catalog.mediaItemFor(bare)
        coVerify(exactly = 1) { musicRepository.ensureExactTrackPersisted(bare) }
    }

    @Test fun `an item with a synthetic id is described by its metadata`() = runTest {
        coEvery { trackDao.getById(any()) } returns null
        val item = MediaItem.Builder().setMediaId("-42").setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder().setTitle("Xtal").setArtist("Aphex Twin")
                .setExtras(android.os.Bundle().apply {
                    putLong(com.stash.core.media.service.StashPlaybackService.EXTRA_TRACK_DURATION_MS, 290_000)
                    putString(com.stash.core.media.service.StashPlaybackService.EXTRA_TRACK_YOUTUBE_ID, "yt2")
                }).build(),
        ).build()
        assertThat(catalog.sharedTrackFor(item)).isEqualTo(SharedTrack("Xtal", "Aphex Twin", durationMs = 290_000, youtubeId = "yt2"))
    }

    @Test fun `a download within 2 s of the host's length plays the local file`() = runTest {
        val file = File.createTempFile("same", ".flac").apply { writeBytes(ByteArray(16)); deleteOnExit() }
        val timed = descriptor.copy(durationMs = 201_500)
        coEvery { musicRepository.ensureExactTrackPersisted(timed) } returns 7
        coEvery { trackDao.getById(7) } returns TrackEntity(
            id = 7, title = "Avril 14th", artist = "Aphex Twin", youtubeId = "yt1",
            durationMs = 200_000, isDownloaded = true, filePath = file.absolutePath,
        )
        // Not "file": on Windows the temp path parses as scheme "C". The point is it skips the resolver.
        assertThat(catalog.mediaItemFor(timed)!!.localConfiguration!!.uri.scheme).isNotEqualTo("stash-resolve")
    }

    @Test fun `a descriptor this phone made from its own row plays that row`() = runTest {
        coEvery { trackDao.getById(3) } returns TrackEntity(id = 3, title = "Home Row", artist = "Me")
        val made = catalog.sharedTrackFor(MediaItem.Builder().setMediaId("3").build())!!
        assertThat(catalog.mediaItemFor(made)!!.mediaId).isEqualTo("3")
        coVerify(exactly = 0) { musicRepository.ensureExactTrackPersisted(any()) }
    }

    @Test fun `a song brings its cover link to the room, never a local file or an unknown host`() = runTest {
        val cover = "https://lastfm-img.freetls.fastly.net/i/u/770x0/ab12.jpg"
        coEvery { trackDao.getById(3) } returns TrackEntity(id = 3, title = "Home Row", artist = "Me", albumArtUrl = cover, albumArtPath = "/data/art/3.jpg")
        coEvery { trackDao.getById(4) } returns TrackEntity(id = 4, title = "Tracked", artist = "Me", albumArtUrl = "https://tracker.example/x.jpg")
        assertThat(catalog.sharedTrackFor(MediaItem.Builder().setMediaId("3").build())!!.artUrl).isEqualTo(cover)
        assertThat(catalog.sharedTrackFor(MediaItem.Builder().setMediaId("4").build())!!.artUrl).isNull()
    }

    @Test fun `our own song, echoed back with an adder and its cover, still plays its row`() = runTest {
        coEvery { trackDao.getById(3) } returns TrackEntity(id = 3, title = "Home Row", artist = "Me", albumArtUrl = "https://i.scdn.co/image/aa")
        val made = catalog.sharedTrackFor(MediaItem.Builder().setMediaId("3").build())!!
        assertThat(catalog.mediaItemFor(made.copy(addedBy = "h"))!!.mediaId).isEqualTo("3")
        coVerify(exactly = 0) { musicRepository.ensureExactTrackPersisted(any()) }
    }

    @Test fun `radio leaves out the seed song`() = runTest {
        coEvery { radio.start(RadioSeed.Song("Avril 14th", "Aphex Twin", "yt1")) } returns (mockk<RadioSession>() to listOf(
            Track(title = "avril  14th ", artist = "Aphex Twin", youtubeId = "yt1"),
            Track(title = "Xtal", artist = "Aphex Twin", youtubeId = "yt2"),
        ))
        assertThat(catalog.radioAfter(descriptor).map { it.title }).containsExactly("Xtal")
    }

    @Test fun `a radio that fails is an empty station`() = runTest {
        coEvery { radio.start(any()) } throws java.io.IOException("offline")
        assertThat(catalog.radioAfter(descriptor)).isEmpty()
    }
}
