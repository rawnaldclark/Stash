package com.stash.data.download.lossless

import android.content.Context
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.audio.AudioMetadata
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.model.Track
import com.stash.core.model.UpgradeResult
import com.stash.data.download.DownloadManager
import com.stash.data.download.TrackDownloadResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LosslessUpgraderImplTest {

    private val context: Context = mockk(relaxed = true)
    private val downloadManager: DownloadManager = mockk()
    private val trackDao: TrackDao = mockk(relaxUnitFun = true)
    private val audioExtractor: AudioDurationExtractor = mockk()
    private val losslessPrefs: LosslessSourcePreferences = mockk()
    private val registry: LosslessSourceRegistry = mockk {
        coEvery { canSearchNow() } returns true
    }
    private val availability: LosslessAvailability = mockk {
        coEvery { fileUrlAvailableNow() } returns true
    }
    private val subject = LosslessUpgraderImpl(
        context,
        downloadManager,
        trackDao,
        audioExtractor,
        losslessPrefs,
        registry,
        availability,
    )

    @Test fun `isLosslessEnabled delegates to the master preference`() = runTest {
        coEvery { losslessPrefs.enabledNow() } returns false

        assertEquals(false, subject.isLosslessEnabled())

        coVerify(exactly = 1) { losslessPrefs.enabledNow() }
    }

    @Test fun `Success maps to Upgraded`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns
            TrackDownloadResult.Success(filePath = "/path/to/file.flac")
        coEvery { audioExtractor.extract(any()) } returns null
        assertEquals(UpgradeResult.Upgraded, subject.upgradeToLossless(stubTrack()))
    }

    @Test fun `null maps to NoMatch`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns null
        assertEquals(UpgradeResult.NoMatch, subject.upgradeToLossless(stubTrack()))
    }

    @Test fun `a sweep is labelled a download, and a paced null is Paced, not NoMatch`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } coAnswers {
            kotlinx.coroutines.currentCoroutineContext()[com.stash.data.download.lossless.relay.LosslessDownloadPurpose]
                ?.pacedRetryAfterSec = 3600
            null
        }
        assertEquals(UpgradeResult.Paced, subject.upgradeToLossless(stubTrack(), sweep = true))
        // A tap carries no label, so the same null is still an honest NoMatch.
        assertEquals(UpgradeResult.NoMatch, subject.upgradeToLossless(stubTrack()))
    }

    // -- A sweep's NoMatch parks the track for two weeks, so it has to be a real miss --

    @Test fun `a sweep miss with a source able to search is a real NoMatch`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns null

        assertEquals(UpgradeResult.NoMatch, subject.upgradeToLossless(stubTrack(), sweep = true))
    }

    @Test fun `a sweep miss while no lossless source could search is Error, not NoMatch`() = runTest {
        // A circuit-broken, degraded or failing source answers null exactly like a miss.
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns null
        coEvery { registry.canSearchNow() } returns false

        assertEquals(UpgradeResult.Error, subject.upgradeToLossless(stubTrack(), sweep = true))
    }

    @Test fun `a sweep miss while every relay is cooled and no login is live is Error`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns null
        coEvery { availability.fileUrlAvailableNow() } returns false

        assertEquals(UpgradeResult.Error, subject.upgradeToLossless(stubTrack(), sweep = true))
    }

    @Test fun `a sweep that found a lossless copy but could not save it is Error, not NoMatch`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } coAnswers {
            // The fetch or the file write failed after the match.
            kotlinx.coroutines.currentCoroutineContext()[com.stash.data.download.lossless.relay.LosslessDownloadPurpose]
                ?.matchFound = true
            null
        }

        assertEquals(UpgradeResult.Error, subject.upgradeToLossless(stubTrack(), sweep = true))
    }

    @Test fun `a tap miss stays NoMatch whatever the sources look like`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns null
        coEvery { registry.canSearchNow() } returns false

        assertEquals(UpgradeResult.NoMatch, subject.upgradeToLossless(stubTrack()))
    }

    @Test fun `Unmatched maps to NoMatch`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns
            TrackDownloadResult.Unmatched()
        assertEquals(UpgradeResult.NoMatch, subject.upgradeToLossless(stubTrack()))
    }

    @Test fun `Failed maps to NoMatch`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns
            TrackDownloadResult.Failed("network")
        assertEquals(UpgradeResult.NoMatch, subject.upgradeToLossless(stubTrack()))
    }

    @Test fun `Deferred maps to NoMatch`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns
            TrackDownloadResult.Deferred
        assertEquals(UpgradeResult.NoMatch, subject.upgradeToLossless(stubTrack()))
    }

    @Test fun `thrown exception maps to Error`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } throws
            RuntimeException("boom")
        assertEquals(UpgradeResult.Error, subject.upgradeToLossless(stubTrack()))
    }

    @Test fun `a cancelled upgrade is rethrown, not reported as Error`() = runTest {
        // Cancel on the FLAC upgrade notification: the worker's cancel handler has to see it.
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } throws
            kotlinx.coroutines.CancellationException("worker stopped")

        val thrown = runCatching { subject.upgradeToLossless(stubTrack(), sweep = true) }.exceptionOrNull()

        assertEquals(true, thrown is kotlinx.coroutines.CancellationException)
    }

    @Test fun `passes forced = true to bypass global lossless toggle`() = runTest {
        val track = stubTrack()
        coEvery { losslessPrefs.enabledNow() } returns false
        coEvery { downloadManager.tryLosslessDownload(track, forced = true) } returns null

        subject.upgradeToLossless(track)

        coVerify(exactly = 1) { downloadManager.tryLosslessDownload(track, forced = true) }
        coVerify(exactly = 0) { losslessPrefs.enabledNow() }
    }

    /**
     * Regression for the bug where Find-in-FLAC wrote the new file to disk
     * but never updated tracks.file_path / file_format / quality_kbps. The
     * Now Playing player kept reading the stale 'opus' row, the new FLAC
     * file was orphaned on disk, and "Recently downloaded" never surfaced
     * the upgraded track because no row changed.
     */
    @Test fun `Success persists markAsDownloaded + setFormatAndQuality + deletes old file`() = runTest {
        val track = stubTrack(filePath = "/old/Artist - Song.m4a", fileFormat = "opus")
        val newPath = "/new/Artist - Song.flac"
        coEvery { downloadManager.tryLosslessDownload(track, forced = true) } returns
            TrackDownloadResult.Success(filePath = newPath)
        coEvery { audioExtractor.extract(newPath) } returns AudioMetadata(
            durationMs = 0,  // skip setDuration branch
            bitrateKbps = 1411,
            format = "flac",
            sampleRateHz = 44_100,
            bitsPerSample = 16,
        )

        val result = subject.upgradeToLossless(track)

        assertEquals(UpgradeResult.Upgraded, result)
        // Note: positional matchers — markAsDownloaded has a defaulted
        // downloadedAt: Long that the production call leaves implicit.
        // Named-arg coVerify computes its own System.currentTimeMillis()
        // for the default and never matches. Positional-with-any() sidesteps
        // that. Order is (trackId, filePath, fileSizeBytes, downloadedAt,
        // sampleRateHz, bitsPerSample).
        coVerify {
            trackDao.markAsDownloaded(
                track.id,
                newPath,
                any(),
                any(),
                44_100,
                16,
            )
        }
        coVerify {
            trackDao.setFormatAndQuality(
                trackId = track.id,
                fileFormat = "flac",
                qualityKbps = 1411,
            )
        }
    }

    /**
     * Two rows can share a file (an import that took a downloaded song's file
     * before imports picked a name of their own). Upgrading one must not
     * delete the old file the other still plays from.
     */
    @Test fun `an upgrade keeps an old file another song still plays from`() = runTest {
        val old = java.io.File.createTempFile("shared", ".m4a").apply { deleteOnExit() }
        val track = stubTrack(filePath = old.absolutePath)
        coEvery { downloadManager.tryLosslessDownload(track, forced = true) } returns
            TrackDownloadResult.Success(filePath = "/new/Artist - Song.flac")
        coEvery { audioExtractor.extract(any()) } returns null
        coEvery { trackDao.countOtherTracksWithFilePath(old.absolutePath, track.id) } returns 1

        assertEquals(UpgradeResult.Upgraded, subject.upgradeToLossless(track))

        assertEquals(true, old.exists())
        old.delete()
    }

    @Test fun `an upgrade deletes an old file only it records`() = runTest {
        val old = java.io.File.createTempFile("own", ".m4a").apply { deleteOnExit() }
        val track = stubTrack(filePath = old.absolutePath)
        coEvery { downloadManager.tryLosslessDownload(track, forced = true) } returns
            TrackDownloadResult.Success(filePath = "/new/Artist - Song.flac")
        coEvery { audioExtractor.extract(any()) } returns null
        coEvery { trackDao.countOtherTracksWithFilePath(old.absolutePath, track.id) } returns 0

        assertEquals(UpgradeResult.Upgraded, subject.upgradeToLossless(track))

        assertEquals(false, old.exists())
    }

    @Test fun `NoMatch does not call markAsDownloaded`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns null
        subject.upgradeToLossless(stubTrack())
        coVerify(exactly = 0) {
            trackDao.markAsDownloaded(any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) {
            trackDao.setFormatAndQuality(any(), any(), any())
        }
    }

    private fun stubTrack(
        filePath: String? = null,
        fileFormat: String = "opus",
    ): Track = Track(
        id = 1,
        title = "Karma Police",
        artist = "Radiohead",
        filePath = filePath,
        fileFormat = fileFormat,
    )

    // -- #531 review ----------------------------------------------------------------

    @Test fun `saving an upgrade the user asked for forgets their pick`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns
            TrackDownloadResult.Success(filePath = "/path/to/file.flac")
        coEvery { audioExtractor.extract(any()) } returns null

        subject.upgradeToLossless(stubTrack())

        // Kept, a later re-download would fetch the lossy pick instead of the FLAC.
        coVerify { trackDao.clearMatchPick(1L) }
    }

    @Test fun `an upgrade that found nothing keeps the pick`() = runTest {
        coEvery { downloadManager.tryLosslessDownload(any(), forced = true) } returns null

        subject.upgradeToLossless(stubTrack())

        coVerify(exactly = 0) { trackDao.clearMatchPick(any()) }
    }
}
