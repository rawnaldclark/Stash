package com.stash.data.download

import com.stash.core.auth.TokenManager
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.audio.AudioMetadata
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.files.LocalFileOps
import com.stash.core.model.Track
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.shared.TrackFinalizer
import com.stash.data.soundcloud.SoundCloudClient
import com.stash.data.soundcloud.SoundCloudStream
import com.stash.data.soundcloud.SoundCloudTrack
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SoundCloudDownloadsTest {
    @get:Rule val temp = TemporaryFolder()
    private val client = mockk<SoundCloudClient>()
    private val tokens = mockk<TokenManager> { coEvery { getSoundCloudToken() } returns "private-token" }
    private val executor = mockk<DownloadExecutor>()
    private val files = mockk<FileOrganizer>()
    private val probe = mockk<AudioDurationExtractor>()
    private val finalizer = mockk<TrackFinalizer>()
    private val dao = mockk<TrackDao>(relaxed = true)
    private val localFiles = mockk<LocalFileOps> { every { acceptDownloadOrDelete(any()) } returns true }
    private val track = Track(id = 9, title = "Remix", artist = "Artist", soundCloudId = "123")
    private fun metadata(preview: Boolean = false) = SoundCloudTrack("123", "https://soundcloud.com/artist/remix",
        "Remix", "Artist", 180000, null, null, preview, emptyList(), null)
    private fun downloader() = SoundCloudDownloads(client, tokens, executor, files, probe, finalizer, dao, localFiles)

    @Test fun `preview never starts yt-dlp`() = runTest {
        coEvery { client.track("123", any()) } returns metadata(true)
        coEvery { client.stream("123", any()) } returns SoundCloudStream("https://cf-media.sndcdn.com/preview", "audio/mp4", true, true)
        assertTrue(downloader().download(track) {} is TrackDownloadResult.Failed)
        coVerify(exactly = 0) { executor.downloadSoundCloud(any(), any(), any(), any(), any()) }
    }

    @Test fun `unmarked 30 second file is deleted before commit and permalink bypasses YouTube`() = runTest {
        coEvery { client.track("123", any()) } returns metadata()
        coEvery { client.stream("123", any()) } returns SoundCloudStream("https://cf-media.sndcdn.com/full", "audio/mp4", true, false)
        val output = temp.newFile("clip.m4a").also { it.writeBytes(ByteArray(10000)) }
        every { files.getTempDir() } returns temp.root
        coEvery { executor.downloadSoundCloud("https://soundcloud.com/artist/remix", "private-token", any(), any(), any()) } returns DownloadResult.Success(output)
        coEvery { probe.extract(output.absolutePath) } returns AudioMetadata(30000, 160, "aac")
        assertTrue(downloader().download(track) {} is TrackDownloadResult.Failed)
        assertFalse(output.exists())
        coVerify(exactly = 0) { finalizer.finalizeFile(any(), any(), any(), any()) }
        coVerify(exactly = 0) { executor.download(any(), any(), any(), any(), any()) }
    }

    @Test fun `verified full audio reaches the existing file finalizer`() = runTest {
        coEvery { client.track("123", any()) } returns metadata()
        coEvery { client.stream("123", any()) } returns SoundCloudStream("https://cf-media.sndcdn.com/full", "audio/mp4", true, false)
        val output = temp.newFile("full.m4a").also { it.writeBytes(ByteArray(10000)) }
        every { files.getTempDir() } returns temp.root
        coEvery { executor.downloadSoundCloud(any(), any(), any(), any(), any()) } returns DownloadResult.Success(output)
        coEvery { probe.extract(any()) } returns AudioMetadata(180000, 160, "aac")
        coEvery { finalizer.finalizeFile(output, track, any(), any()) } returns TrackFinalizer.FinalizeResult.Success(
            FileOrganizer.CommittedTrack("/music/Remix.m4a", 10000), null)
        assertEquals(TrackDownloadResult.Success("/music/Remix.m4a"), downloader().download(track) {})
        assertFalse(output.exists())
    }

    @Test fun `cancellation is propagated`() = runTest {
        coEvery { client.track(any(), any()) } throws CancellationException()
        try { downloader().download(track) {}; fail("cancellation swallowed") } catch (_: CancellationException) { }
    }

    @Test fun `full storage during commit discards temporary audio and permits a later retry`() = runTest {
        coEvery { client.track("123", any()) } returns metadata()
        coEvery { client.stream("123", any()) } returns SoundCloudStream("https://cf-media.sndcdn.com/full", "audio/mp4", true, false)
        every { files.getTempDir() } returns temp.root
        val first = temp.newFile("first.m4a").also { it.writeBytes(ByteArray(10000)) }
        val retry = temp.newFile("retry.m4a").also { it.writeBytes(ByteArray(10000)) }
        coEvery { executor.downloadSoundCloud(any(), any(), any(), any(), any()) } returnsMany listOf(
            DownloadResult.Success(first), DownloadResult.Success(retry))
        coEvery { probe.extract(any()) } returns AudioMetadata(180000, 160, "aac")
        val embedder = mockk<com.stash.data.download.files.MetadataEmbedder>(relaxed = true)
        val art = mockk<com.stash.data.download.files.AlbumArtCache>()
        coEvery { art.resolveArt(any()) } returns null
        val actualFinalizer = TrackFinalizer(embedder, files, probe, art)
        coEvery { files.commitDownload(any(), any(), any(), any(), any(), any()) } throws java.io.IOException("No space left on device")
        val subject = SoundCloudDownloads(client, tokens, executor, files, probe, actualFinalizer, dao, localFiles)

        assertTrue(subject.download(track) {} is TrackDownloadResult.Failed)
        assertFalse("failed commit must discard downloaded temporary audio", first.exists())

        coEvery { files.commitDownload(any(), any(), any(), any(), any(), any()) } returns
            FileOrganizer.CommittedTrack("/music/Remix.m4a", 10000)
        assertEquals(TrackDownloadResult.Success("/music/Remix.m4a"), subject.download(track) {})
        assertFalse(retry.exists())
    }

    @Test fun `length gate rejects unknown truncated and overlong audio`() {
        assertFalse(SoundCloudDownloads.fullLength(30000, 180000))
        assertFalse(SoundCloudDownloads.fullLength(180000, 0))
        assertFalse(SoundCloudDownloads.fullLength(240000, 180000))
        assertTrue(SoundCloudDownloads.fullLength(179900, 180000))
    }
}
