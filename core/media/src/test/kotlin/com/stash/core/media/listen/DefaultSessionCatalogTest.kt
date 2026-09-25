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
