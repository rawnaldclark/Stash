package com.stash.core.media.actions

import android.os.SystemClock
import androidx.media3.common.PlaybackException
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.preview.PreviewErrorEvent
import com.stash.core.media.preview.PreviewPlayer
import com.stash.core.media.preview.PreviewState
import com.stash.core.model.MusicSource
import com.stash.core.model.QualityTier
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.DownloadResult
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.preview.PreviewUrlCache
import com.stash.data.download.preview.PreviewUrlExtractor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class TrackActionsDelegateTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tear() { Dispatchers.resetMain() }

    private fun delegate(
        previewPlayer: PreviewPlayer = stubPreviewPlayer(),
        urlExtractor: PreviewUrlExtractor = mock(),
        urlCache: PreviewUrlCache = PreviewUrlCache(),
        downloadExecutor: DownloadExecutor = mock(),
        trackDao: TrackDao = mock(),
        fileOrganizer: FileOrganizer = mock(),
        qualityPrefs: QualityPreferencesManager = mock {
            on { qualityTier } doReturn flowOf(QualityTier.NORMAL)
        },
        musicRepository: MusicRepository = mock(),
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher),
        bind: Boolean = true,
    ) = TrackActionsDelegate(
        previewPlayer, urlExtractor, urlCache, downloadExecutor,
        trackDao, fileOrganizer, qualityPrefs, musicRepository,
    ).also { if (bind) it.bindToScope(scope) }

    private fun stubPreviewPlayer(): PreviewPlayer = mock {
        on { previewState } doReturn MutableStateFlow<PreviewState>(PreviewState.Idle).asStateFlow()
        on { playerErrors } doReturn MutableSharedFlow<PreviewErrorEvent>()
    }

    private fun ioError(): PlaybackException = PlaybackException(
        /* message = */ "io fail",
        /* cause = */ null,
        /* errorCode = */ PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    )

    private fun nonIoError(): PlaybackException = PlaybackException(
        /* message = */ "decoder fail",
        /* cause = */ null,
        /* errorCode = */ PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    )

    private fun downloadedEntity(ytId: String) = TrackEntity(
        title = "",
        artist = "",
        album = "",
        source = MusicSource.YOUTUBE,
        youtubeId = ytId,
        isDownloaded = true,
    )

    private fun notDownloadedEntity(ytId: String) = TrackEntity(
        title = "",
        artist = "",
        album = "",
        source = MusicSource.YOUTUBE,
        youtubeId = ytId,
        isDownloaded = false,
    )

    // ------------------------------------------------------------------
    // Preview — cache paths
    // ------------------------------------------------------------------

    @Test
    fun `previewTrack with cached URL plays directly without calling extractor`() = runTest {
        val urlCache = PreviewUrlCache().also { it["v1"] = "https://warm.example/x" }
        val extractor = mock<PreviewUrlExtractor>()
        val player = stubPreviewPlayer()
        val d = delegate(previewPlayer = player, urlExtractor = extractor, urlCache = urlCache)

        d.previewTrack("v1")
        advanceUntilIdle()

        verify(player).playUrl(eq("v1"), eq("https://warm.example/x"))
        verify(extractor, never()).extractStreamUrl(any())
        assertNull(d.previewLoadingId.value)
    }

    @Test
    fun `previewTrack cache miss calls extractor then plays and warms cache`() = runTest {
        val urlCache = PreviewUrlCache()
        val extractor = mock<PreviewUrlExtractor> {
            onBlocking { extractStreamUrl("v1") } doReturn "https://fresh.example/y"
        }
        val player = stubPreviewPlayer()
        val d = delegate(previewPlayer = player, urlExtractor = extractor, urlCache = urlCache)

        d.previewTrack("v1")
        advanceUntilIdle()

        verify(extractor).extractStreamUrl("v1")
        verify(player).playUrl(eq("v1"), eq("https://fresh.example/y"))
        assertEquals("https://fresh.example/y", urlCache["v1"])
        assertNull(d.previewLoadingId.value)
    }

    @Test
    fun `previewTrack extractor failure emits userMessage and clears loading`() = runTest {
        val extractor = mock<PreviewUrlExtractor> {
            onBlocking { extractStreamUrl("v1") } doThrow RuntimeException("extract boom")
        }
        val player = stubPreviewPlayer()
        val d = delegate(previewPlayer = player, urlExtractor = extractor)

        val messages = mutableListOf<String>()
        val collectorScope = CoroutineScope(SupervisorJob() + dispatcher)
        val job = collectorScope.launch { d.userMessages.collect { messages += it } }
        try {
            d.previewTrack("v1")
            advanceUntilIdle()

            assertNull(d.previewLoadingId.value)
            assertEquals(listOf("Couldn't load preview."), messages)
            // stop() is called twice: once at the top of previewTrack,
            // once in the catch block after failure.
            verify(player, times(2)).stop()
            verify(player, never()).playUrl(any(), any())
        } finally {
            job.cancel()
        }
    }

    // ------------------------------------------------------------------
    // Preview — error retry
    // ------------------------------------------------------------------

    @Test
    fun `onPreviewError within window triggers yt-dlp retry and plays on success`() = runTest {
        val urlCache = PreviewUrlCache().also { it["v1"] = "https://inner.example/x" }
        val extractor = mock<PreviewUrlExtractor> {
            onBlocking { extractViaYtDlpForRetry("v1") } doReturn "https://ytdlp.example/y"
        }
        val player = stubPreviewPlayer()
        val d = delegate(previewPlayer = player, urlExtractor = extractor, urlCache = urlCache)

        // Prime lastPreviewVideoId + lastPreviewStartedAt via a cache-hit preview.
        d.previewTrack("v1")
        advanceUntilIdle()

        d.onPreviewError("v1", ioError())
        advanceUntilIdle()

        verify(extractor).extractViaYtDlpForRetry("v1")
        // One from previewTrack (cache hit) and one from the retry path.
        verify(player).playUrl(eq("v1"), eq("https://inner.example/x"))
        verify(player).playUrl(eq("v1"), eq("https://ytdlp.example/y"))
        assertEquals("https://ytdlp.example/y", urlCache["v1"])
        assertNull(d.previewLoadingId.value)
    }

    @Test
    fun `onPreviewError outside window is ignored`() = runTest {
        val extractor = mock<PreviewUrlExtractor>()
        val player = stubPreviewPlayer()
        val urlCache = PreviewUrlCache().also { it["v1"] = "https://inner.example/x" }

        // Mock SystemClock so we control "start" vs "error" time.
        val clock: MockedStatic<SystemClock> = Mockito.mockStatic(SystemClock::class.java)
        try {
            // First call (inside previewTrack after playUrl) records t0 = 0.
            clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(0L)
            val d = delegate(previewPlayer = player, urlExtractor = extractor, urlCache = urlCache)
            d.previewTrack("v1")
            advanceUntilIdle()

            // Second call (inside onPreviewError) reports 10s later — outside the 3s window.
            clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(10_000L)
            d.onPreviewError("v1", ioError())
            advanceUntilIdle()

            verify(extractor, never()).extractViaYtDlpForRetry(any())
        } finally {
            clock.close()
        }
    }

    @Test
    fun `onPreviewError ignored when videoId differs from last preview`() = runTest {
        val extractor = mock<PreviewUrlExtractor>()
        val player = stubPreviewPlayer()
        val urlCache = PreviewUrlCache().also { it["v1"] = "https://inner.example/x" }
        val d = delegate(previewPlayer = player, urlExtractor = extractor, urlCache = urlCache)

        d.previewTrack("v1")
        advanceUntilIdle()

        // Error for a DIFFERENT videoId than the last one we kicked off.
        d.onPreviewError("v2", ioError())
        advanceUntilIdle()

        verify(extractor, never()).extractViaYtDlpForRetry(any())
    }

    @Test
    fun `onPreviewError ignores non-IO error codes`() = runTest {
        val extractor = mock<PreviewUrlExtractor>()
        val player = stubPreviewPlayer()
        val urlCache = PreviewUrlCache().also { it["v1"] = "https://inner.example/x" }
        val d = delegate(previewPlayer = player, urlExtractor = extractor, urlCache = urlCache)

        d.previewTrack("v1")
        advanceUntilIdle()

        d.onPreviewError("v1", nonIoError())
        advanceUntilIdle()

        verify(extractor, never()).extractViaYtDlpForRetry(any())
    }

    // ------------------------------------------------------------------
    // Download
    // ------------------------------------------------------------------

    @Test
    fun `downloadTrack success inserts track and flips state from downloading to downloaded`() = runTest {
        val tempFile = File.createTempFile("tad_tmp_", ".mp3").apply { writeText("x") }
        val finalFile = File.createTempFile("tad_final_", ".mp3").apply { delete() }
        try {
            val executor = mock<DownloadExecutor> {
                onBlocking { download(any(), any(), any(), any(), any()) } doReturn
                    DownloadResult.Success(tempFile)
            }
            val fileOrganizer = mock<FileOrganizer> {
                on { getTempDir() } doReturn tempFile.parentFile!!
                on { getTrackFile(any(), anyOrNull(), any(), any()) } doReturn finalFile
            }
            val repo = mock<MusicRepository> {
                onBlocking { insertTrack(any()) } doReturn 1L
            }

            val d = delegate(
                downloadExecutor = executor,
                fileOrganizer = fileOrganizer,
                musicRepository = repo,
            )

            d.downloadTrack(TrackItem("v1", "Title", "Artist", 180.0, null))

            // Optimistic flip BEFORE the coroutine runs.
            assertEquals(setOf("v1"), d.downloadingIds.value)

            advanceUntilIdle()

            assertEquals(emptySet<String>(), d.downloadingIds.value)
            assertEquals(setOf("v1"), d.downloadedIds.value)
            org.mockito.kotlin.verifyBlocking(repo) { insertTrack(any()) }
        } finally {
            tempFile.delete()
            finalFile.delete()
        }
    }

    @Test
    fun `downloadTrack YtDlpError marks failed and drops from downloading`() = runTest {
        val executor = mock<DownloadExecutor> {
            onBlocking { download(any(), any(), any(), any(), any()) } doReturn
                DownloadResult.YtDlpError("sig solve failed")
        }
        val fileOrganizer = mock<FileOrganizer> {
            on { getTempDir() } doReturn File(System.getProperty("java.io.tmpdir")!!)
        }
        val repo = mock<MusicRepository>()

        val d = delegate(
            downloadExecutor = executor,
            fileOrganizer = fileOrganizer,
            musicRepository = repo,
        )

        d.downloadTrack(TrackItem("v1", "T", "A", 60.0, null))
        advanceUntilIdle()

        assertEquals(emptySet<String>(), d.downloadingIds.value)
        assertEquals(emptySet<String>(), d.downloadedIds.value)
        verify(repo, never()).insertTrack(any())
    }

    @Test
    fun `downloadTrack skips when already downloading`() = runTest {
        // Hang the first download indefinitely so the second call sees an
        // in-flight entry in _downloadingIds and returns early.
        val hang = CompletableDeferred<DownloadResult>()
        val executor = mock<DownloadExecutor> {
            onBlocking { download(any(), any(), any(), any(), any()) } doSuspendableAnswer
                { hang.await() }
        }
        val fileOrganizer = mock<FileOrganizer> {
            on { getTempDir() } doReturn File(System.getProperty("java.io.tmpdir")!!)
        }
        val d = delegate(downloadExecutor = executor, fileOrganizer = fileOrganizer)

        val item = TrackItem("v1", "T", "A", 60.0, null)
        d.downloadTrack(item)
        advanceUntilIdle() // let the first download launch + suspend on executor.
        d.downloadTrack(item) // second call: should early-return before launching.
        advanceUntilIdle()

        // Exactly one in-flight download, executor invoked exactly once.
        assertEquals(setOf("v1"), d.downloadingIds.value)
        verify(executor, times(1)).download(any(), any(), any(), any(), any())
        hang.cancel() // release the hanging coroutine so runTest can finish.
    }

    @Test
    fun `downloadTrack skips when already downloaded`() = runTest {
        val executor = mock<DownloadExecutor>()
        val dao = mock<TrackDao> {
            onBlocking { findByYoutubeId("v1") } doReturn downloadedEntity("v1")
        }
        val d = delegate(downloadExecutor = executor, trackDao = dao)

        // Seed downloadedIds via the public surface.
        d.refreshDownloadedIds(listOf("v1"))
        advanceUntilIdle()
        assertEquals(setOf("v1"), d.downloadedIds.value)

        d.downloadTrack(TrackItem("v1", "T", "A", 60.0, null))
        advanceUntilIdle()

        verify(executor, never()).download(any(), any(), any(), any(), any())
        assertEquals(emptySet<String>(), d.downloadingIds.value)
        assertEquals(setOf("v1"), d.downloadedIds.value)
    }

    // ------------------------------------------------------------------
    // refreshDownloadedIds
    // ------------------------------------------------------------------

    @Test
    fun `refreshDownloadedIds adds only ids whose TrackEntity is marked downloaded`() = runTest {
        val dao = mock<TrackDao> {
            onBlocking { findByYoutubeId("v1") } doReturn downloadedEntity("v1")
            onBlocking { findByYoutubeId("v2") } doReturn notDownloadedEntity("v2")
            onBlocking { findByYoutubeId("v3") } doReturn null
        }
        val d = delegate(trackDao = dao)

        d.refreshDownloadedIds(listOf("v1", "v2", "v3"))
        advanceUntilIdle()

        assertEquals(setOf("v1"), d.downloadedIds.value)
    }

    // ------------------------------------------------------------------
    // Lifecycle contract
    // ------------------------------------------------------------------

    @Test
    fun `bindToScope called twice throws IllegalStateException`() {
        val d = delegate(bind = false)
        d.bindToScope(CoroutineScope(SupervisorJob() + dispatcher))
        assertThrows(IllegalStateException::class.java) {
            d.bindToScope(CoroutineScope(SupervisorJob() + dispatcher))
        }
    }

    @Test
    fun `previewTrack before bindToScope throws IllegalStateException`() {
        val d = delegate(bind = false)
        assertThrows(IllegalStateException::class.java) {
            d.previewTrack("v1")
        }
    }

    // ------------------------------------------------------------------
    // stopPreview
    // ------------------------------------------------------------------

    @Test
    fun `stopPreview clears previewLoadingId and stops player`() = runTest {
        // Kick a preview with a cache miss that hangs forever, so previewLoadingId
        // stays non-null when we call stopPreview.
        val hang = CompletableDeferred<String>()
        val extractor = mock<PreviewUrlExtractor> {
            onBlocking { extractStreamUrl(any()) } doSuspendableAnswer { hang.await() }
        }
        val player = stubPreviewPlayer()
        val d = delegate(previewPlayer = player, urlExtractor = extractor)

        d.previewTrack("v1")
        advanceUntilIdle()
        assertEquals("v1", d.previewLoadingId.value)

        d.stopPreview()
        advanceUntilIdle()

        assertNull(d.previewLoadingId.value)
        // stop() is called: once at the top of previewTrack, once from stopPreview.
        verify(player, times(2)).stop()
        hang.cancel() // release the hanging coroutine so runTest can finish.
    }
}
