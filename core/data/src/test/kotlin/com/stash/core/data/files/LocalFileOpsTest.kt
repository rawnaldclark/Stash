package com.stash.core.data.files

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Unit tests for [LocalFileOps]' plain-path branch (the one that matters for
 * the observed junk downloads, which live in internal `/data/user/0/...`
 * storage). The `content://` SAF branch needs an Android ContentResolver and
 * is exercised on-device; a relaxed mock Context is fine here because the
 * plain-path branch never touches it.
 */
class LocalFileOpsTest {

    private val ops = LocalFileOps(mockk(relaxed = true))

    @Test fun `sizeBytes returns the file length`() {
        val f = File.createTempFile("stash-junk", ".webm").apply {
            writeBytes(ByteArray(274)) // the exact junk-download shape from the device
            deleteOnExit()
        }
        assertEquals(274L, ops.sizeBytes(f.absolutePath))
    }

    @Test fun `sizeBytes returns 0 for a missing file`() {
        assertEquals(0L, ops.sizeBytes("/data/user/0/com.stash.app/files/music/nope/missing.flac"))
    }

    @Test fun `sizeBytes returns 0 for null or blank`() {
        assertEquals(0L, ops.sizeBytes(null))
        assertEquals(0L, ops.sizeBytes("  "))
    }

    @Test fun `delete removes a plain file`() {
        val f = File.createTempFile("stash-del", ".flac").apply { writeBytes(ByteArray(10)) }
        ops.delete(f.absolutePath)
        assertFalse(f.exists())
    }

    @Test fun `delete is a no-op for null without throwing`() {
        ops.delete(null) // must not throw
    }

    private val floor = 16L * 1024L

    @Test fun `classify - present-but-tiny plain file is TOO_SMALL`() {
        val f = File.createTempFile("stash-junk", ".webm").apply {
            writeBytes(ByteArray(274)); deleteOnExit()
        }
        assertEquals(LocalFileState.TOO_SMALL, ops.classify(f.absolutePath, floor))
    }

    @Test fun `classify - real-sized plain file is OK`() {
        val f = File.createTempFile("stash-real", ".flac").apply {
            writeBytes(ByteArray(20_000)); deleteOnExit()
        }
        assertEquals(LocalFileState.OK, ops.classify(f.absolutePath, floor))
    }

    @Test fun `classify - missing internal file is MISSING`() {
        assertEquals(
            LocalFileState.MISSING,
            ops.classify("/data/user/0/com.stash.app/files/music/nope/missing.flac", floor),
        )
    }

    @Test fun `classify - null path is MISSING`() {
        assertEquals(LocalFileState.MISSING, ops.classify(null, floor))
    }

    // ── Removable-volume safety (issue #98) ──────────────────────────────
    // A file that "doesn't exist" because its whole volume is unmounted (SD
    // card ejected) must never classify as MISSING — the sweep would un-mark
    // the entire external library and the damage would stick after reinsert.

    @Test fun `classify - missing file on an unmounted volume is INCONCLUSIVE`() {
        ops.volumeMounted = { false }
        assertEquals(
            LocalFileState.INCONCLUSIVE,
            ops.classify("/storage/ABCD-1234/Music/track.flac", floor),
        )
    }

    @Test fun `classify - missing file on a mounted volume is MISSING`() {
        ops.volumeMounted = { true }
        assertEquals(
            LocalFileState.MISSING,
            ops.classify("/storage/ABCD-1234/Music/track.flac", floor),
        )
    }

    @Test fun `classify - internal missing file never consults the mount probe`() {
        ops.volumeMounted = { error("must not be called for /data paths") }
        assertEquals(
            LocalFileState.MISSING,
            ops.classify("/data/user/0/com.stash.app/files/music/nope/missing.flac", floor),
        )
    }
}
