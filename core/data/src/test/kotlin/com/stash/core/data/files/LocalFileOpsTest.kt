package com.stash.core.data.files

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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

    @get:Rule val tmp = TemporaryFolder()

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

    // -- #531: the wrong-match swap's file safety -------------------------------

    @Test fun `exists reports a plain file`() {
        val f = File.createTempFile("stash-exists", ".opus").apply { deleteOnExit() }
        assertTrue(ops.exists(f.absolutePath))
        assertFalse(ops.exists(f.absolutePath + ".gone"))
        assertFalse(ops.exists(null))
    }

    @Test fun `a file set aside survives a failed write and comes back over the leftover`() {
        val dir = tmp.newFolder()
        val song = File(dir, "lacrymosa.opus").apply { writeText("the old song") }

        val backup = ops.setAside(song.absolutePath)

        assertFalse("the spot is free for the new file", song.exists())
        assertEquals("the old song", File(backup!!).readText())
        song.writeText("half a replacement") // what a failed save leaves behind
        assertTrue(ops.restoreSetAside(backup, song.absolutePath))
        assertEquals("the old song", song.readText())
        assertFalse("no backup is left lying around", File(backup).exists())
    }

    @Test fun `setAside returns null when there is no file`() {
        assertNull(ops.setAside(File(tmp.newFolder(), "missing.opus").absolutePath))
    }

    @Test fun `isSameFile compares normalized plain paths`() {
        val dir = tmp.newFolder()
        assertTrue(ops.isSameFile(File(dir, "a/../b.opus").path, File(dir, "b.opus").absolutePath))
        assertFalse(ops.isSameFile(File(dir, "a.opus").path, File(dir, "b.opus").path))
        assertFalse(ops.isSameFile(null, File(dir, "b.opus").path))
    }

    @Test fun `isSameFile compares SAF documents by document id`() {
        val inTree = "content://com.android.externalstorage.documents/tree/primary%3AMusic" +
            "/document/primary%3AMusic%2Fevanescence%2Flacrymosa.opus"
        val single = "content://com.android.externalstorage.documents" +
            "/document/primary%3AMusic%2Fevanescence%2Flacrymosa.opus"
        val other = "content://com.android.externalstorage.documents/tree/primary%3AMusic" +
            "/document/primary%3AMusic%2Fevanescence%2Fmy-immortal.opus"
        assertTrue(ops.isSameFile(inTree, single))
        assertFalse(ops.isSameFile(inTree, other))
    }

    @Test fun `setting a file aside never overwrites an earlier backup`() {
        val dir = tmp.newFolder()
        val song = File(dir, "lacrymosa.opus").apply { writeText("the song now") }
        // Left by an earlier swap whose restore failed: the user's original audio.
        val earlier = File(dir, "lacrymosa.opus.swapbak").apply { writeText("the original audio") }

        val backup = ops.setAside(song.absolutePath)

        assertEquals("the original audio", earlier.readText())
        assertEquals("the song now", File(backup!!).readText())
        assertTrue(File(backup).absolutePath != earlier.absolutePath)
    }

    @Test fun `the backup name is known before the move, and the set-aside uses it`() {
        val dir = tmp.newFolder()
        val song = File(dir, "lacrymosa.opus").apply { writeText("the song") }

        // So the swap can write it down before anything moves.
        val planned = ops.backupPathFor(song.absolutePath)
        val backup = ops.setAside(song.absolutePath, planned)

        assertEquals(planned, backup)
        assertEquals("the song", File(backup!!).readText())
    }

    @Test fun `delete says whether it deleted`() {
        val f = File.createTempFile("stash-del", ".opus")
        assertTrue(ops.delete(f.absolutePath))
        assertFalse(ops.delete(f.absolutePath))
    }
}
