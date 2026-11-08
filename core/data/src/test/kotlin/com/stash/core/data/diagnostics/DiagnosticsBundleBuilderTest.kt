package com.stash.core.data.diagnostics

import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.PlaylistDiagnosticsRow
import com.stash.core.data.db.dao.SourceAccountDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.dao.TrackBlocklistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.TrackDiagnosticsTotals
import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticsBundleBuilderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val crashFileStore: CrashFileStore = mockk(relaxed = true) {
        every { deviceMetadataBlock() } returns "App version:    0.9.42 (versionCode 78)\n"
        every { allCrashFiles() } returns emptyList()
    }
    private val logcatCapture: LogcatCapture = mockk {
        every { recentLogs(any()) } returns "log line one"
        every { logFiles() } returns emptyList()
    }
    private val syncHistoryDao: SyncHistoryDao = mockk { every { getRecentSyncs(any()) } returns flowOf(emptyList()) }
    private val downloadQueueDao: DownloadQueueDao = mockk {
        coEvery { getStatusCounts() } returns emptyList()
        every { getFailedDownloads() } returns flowOf(emptyList())
        every { getUnmatchedCount() } returns flowOf(0)
    }
    private val trackBlocklistDao: TrackBlocklistDao = mockk { every { observeCount() } returns flowOf(3) }
    private val sourceAccountDao: SourceAccountDao = mockk { every { getAll() } returns flowOf(emptyList()) }
    private val playlistDao: PlaylistDao = mockk {
        coEvery { diagnosticsCounts() } returns listOf(
            PlaylistDiagnosticsRow("SPOTIFY", "DAILY_MIX", total = 6, switchedOn = 4, neverSynced = 1, empty = 2),
        )
        coEvery { inactiveCount() } returns 3
    }
    private val trackDao: TrackDao = mockk { coEvery { diagnosticsTotals() } returns TrackDiagnosticsTotals(120, 80, 2) }
    private val tokenManager: TokenManager = mockk {
        every { spotifyAuthState } returns MutableStateFlow(AuthState.NotConnected)
        every { youTubeAuthState } returns MutableStateFlow(AuthState.NotConnected)
    }

    // `answers`, not `returns`: the rule creates the folder after this field initialises.
    private val context: Context = mockk(relaxed = true) { every { cacheDir } answers { tmp.root } }

    private fun builder(contributors: Set<DiagnosticsContributor> = emptySet()) = DiagnosticsBundleBuilder(
        context, syncHistoryDao, downloadQueueDao, trackBlocklistDao,
        sourceAccountDao, playlistDao, trackDao, tokenManager, crashFileStore, logcatCapture, contributors,
    )

    private fun contributor(name: String, body: suspend () -> String) = object : DiagnosticsContributor {
        override val title = name
        override suspend fun section() = body()
    }

    @Test fun `bundle text includes header, sections, and logs`() = runTest {
        val text = builder().buildText()
        assertTrue(text.contains("App version:"))
        assertTrue(text.contains("log line one"))
        assertTrue(text.contains("Blocklist: 3"))
        assertTrue(text.contains("Tracks: 120 · downloaded 80 · file missing 2"))
        assertTrue(text.contains("  SPOTIFY/DAILY_MIX: 6 · on 4 · never synced 1 · empty 2"))
        assertTrue(text.contains("Inactive playlists: 3"))
    }

    @Test fun `contributed sections appear under their own headers, sorted by title, before the crash reports`() = runTest {
        val text = builder(
            setOf(contributor("Playback") { "origin=qbdlx" }, contributor("Lossless") { "Mode: Download" }),
        ).buildText()
        val lossless = text.indexOf("== Lossless ==\nMode: Download")
        val playback = text.indexOf("== Playback ==\norigin=qbdlx")
        val crashes = text.indexOf("== Recent crash reports ==")
        assertTrue(lossless in 0 until playback)
        assertTrue(playback in 0 until crashes)
    }

    @Test fun `a throwing contributor degrades to an unavailable line, like a builder section`() = runTest {
        val text = builder(setOf(contributor("Lossless") { error("relay client not ready") })).buildText()
        assertTrue(text.contains("[Lossless unavailable: relay client not ready]"))
        assertTrue(text.contains("log line one")) // the rest still built
    }

    @Test fun `build writes a zip with the report, the whole log capture and crash files, each redacted`() = runTest {
        val log = tmp.newFile("applog.txt").apply { writeText("I OkHttp: Cookie: sp_dc=TOPSECRETVALUE\nplain line\n") }
        val crash = tmp.newFile("crash-1.txt").apply { writeText("Stash crash report\nuser=alice@example.com\n") }
        every { logcatCapture.logFiles() } returns listOf(log)
        every { crashFileStore.allCrashFiles() } returns listOf(crash)

        val bundle = builder().build()

        assertEquals(DiagnosticsBundleBuilder.ZIP_MIME, bundle.mimeType)
        assertTrue(bundle.file.name.endsWith(".zip"))
        ZipFile(bundle.file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertEquals(listOf("report.txt", "logs/applog.txt", "crashes/crash-1.txt"), names)
            val report = zip.getInputStream(zip.getEntry("report.txt")).bufferedReader().readText()
            assertEquals(bundle.text, report)
            val logs = zip.getInputStream(zip.getEntry("logs/applog.txt")).bufferedReader().readText()
            assertFalse(logs.contains("TOPSECRETVALUE"))
            assertTrue(logs.contains("plain line"))
            val crashText = zip.getInputStream(zip.getEntry("crashes/crash-1.txt")).bufferedReader().readText()
            assertFalse(crashText.contains("alice@example.com"))
        }
    }

    @Test fun `build keeps only the newest bundle file`() = runTest {
        val dir = File(tmp.root, "diagnostics").apply { mkdirs() }
        val stale = File(dir, "stash-diagnostics-1.txt").apply { writeText("old") }
        val staleZip = File(dir, "stash-diagnostics-2.zip").apply { writeText("old") }

        val bundle = builder().build()

        assertFalse(stale.exists())
        assertFalse(staleZip.exists())
        assertEquals(listOf(bundle.file.name), dir.list()!!.toList())
    }

    @Test fun `secrets in captured logs are redacted in the final bundle`() = runTest {
        io.mockk.every { logcatCapture.recentLogs(any()) } returns
            "12:00 I OkHttp: Cookie: sp_dc=TOPSECRETVALUE; user=alice@example.com"
        val text = builder().buildText()
        assertTrue(text.contains("[REDACTED")) // redaction fired
        org.junit.Assert.assertFalse(text.contains("TOPSECRETVALUE"))
        org.junit.Assert.assertFalse(text.contains("alice@example.com"))
    }

    @Test fun `an extra Spotify account's steps are named by its label, the main's as before`() = runTest {
        val steps = """[{"service":"SPOTIFY","step":"getUserPlaylists","status":"SUCCESS","itemCount":4},""" +
            """{"service":"SPOTIFY","step":"getUserPlaylists","status":"SUCCESS","itemCount":2,"account":"extra 1"}]"""
        every { syncHistoryDao.getRecentSyncs(any()) } returns
            flowOf(listOf(com.stash.core.data.db.entity.SyncHistoryEntity(diagnostics = steps)))

        val text = builder().buildText()

        assertTrue(text.contains("    SPOTIFY · getUserPlaylists · SUCCESS · http=null · items=4"))
        assertTrue(text.contains("    SPOTIFY (extra 1) · getUserPlaylists · SUCCESS · http=null · items=2"))
    }

    @Test fun `a failing data source degrades to an unavailable note, not a crash`() = runTest {
        coEvery { downloadQueueDao.getStatusCounts() } throws RuntimeException("db locked")
        val text = builder().buildText()
        assertTrue(text.contains("unavailable"))
        assertTrue(text.contains("log line one")) // rest still built
    }
}
