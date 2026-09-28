package com.stash.core.data.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LogcatCaptureTest {
    private lateinit var context: Context
    private lateinit var dir: File

    // Small-cap subclass so rotation triggers quickly in the test.
    private class SmallCap(context: Context) : LogcatCapture(context) {
        override val maxBytes: Long = 8L * 1024
    }

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir = File(context.cacheDir, "diagnostics")
        dir.deleteRecursively()
    }
    @After fun tearDown() { dir.deleteRecursively() }

    @Test fun `recentLogs returns the last N lines across rotation`() {
        val capture = SmallCap(context)
        repeat(50) { capture.append("line-$it") }
        val lines = capture.recentLogs(maxLines = 10).trim().lines()
        assertEquals(10, lines.size)
        assertEquals("line-49", lines.last())
    }

    @Test fun `Samsung per-frame View and VRI lines are dropped, everything else is kept`() {
        val capture = LogcatCapture(context)
        // Real lines from a Galaxy S26 Ultra bundle (2026-09-27).
        capture.append("09-27 21:43:24.314  8493  8493 I View    : setRequestedFrameRate frameRate=-4.0, this=android.view.View{4731c7b V.ED..... ........ 0,0-0,0}")
        capture.append("09-27 21:43:24.314  8493  8493 I VRI[MainActivity]@e30962d: Requested frameRateCategory 6 by android.view.View{4731c7b V.ED..... ........ 0,0-0,0}")
        capture.append("09-27 21:43:13.714  8493 25560 D StashDL : download: SUCCESS file=dl_7122.webm size=4398753")
        capture.append("09-27 21:43:26.059  8493  8493 I InsetsSourceConsumer: applyRequestedVisibilityToControl: visible=true")
        capture.append("a line that is not logcat-shaped")
        val lines = capture.recentLogs().trim().lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("StashDL"))
        assertTrue(lines.none { it.contains("frameRate") })
    }

    @Test fun `append rotates when the active file exceeds the cap`() {
        val capture = SmallCap(context)
        val big = "x".repeat(1000)
        repeat(20) { capture.append(big) } // ~20 KB > 8 KB cap
        assertTrue(File(dir, "applog.1.txt").exists()) // rotation happened
        capture.append("freshest")
        assertTrue(capture.recentLogs(5).contains("freshest"))
    }
}
