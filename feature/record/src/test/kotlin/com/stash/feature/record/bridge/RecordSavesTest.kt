package com.stash.feature.record.bridge

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/** The record's files put back together from `stash:save` chunks (stash-on-record 9dff865 `src/stash/save.ts`). */
class RecordSavesTest {
    @get:Rule val dir = TemporaryFolder()

    /** What reached the saver: the bytes, the name and the type, and whether it was a share. */
    private class Saver(private val ok: Boolean = true, private val saved: SaveOutcome = SaveOutcome.SAVED) : RecordSaver {
        val got = mutableListOf<Triple<ByteArray, String, Boolean>>()
        override suspend fun save(file: File, name: String, mime: String): SaveOutcome {
            got += Triple(file.readBytes(), name, false)
            return if (ok) saved else SaveOutcome.FAILED
        }

        override suspend fun share(file: File, name: String, mime: String): Boolean {
            got += Triple(file.readBytes(), name, true)
            return ok
        }
    }

    private val parts get() = File(dir.root, "parts")

    private fun saves(saver: RecordSaver, max: Long = RecordSaves.MAX_BYTES, files: Int = RecordSaves.MAX_FILES) =
        RecordSaves(parts, saver, maxBytes = max, maxFiles = files, io = Dispatchers.Unconfined)

    /** A chunk taken, its ack ignored. */
    private suspend fun RecordSaves.put(chunk: RecordAsk.Save): RecordNote? = take(chunk) {}

    /** [bytes] as the page sends them: chunks of a multiple of 3 bytes (so only the last one is padded). */
    private fun chunks(id: String, bytes: ByteArray, size: Int = 3, name: String = "Your record.png", mime: String = "image/png", share: Boolean = false): List<RecordAsk.Save> {
        if (bytes.isEmpty()) return listOf(RecordAsk.Save(id, name, mime, share, 0, true, ""))
        val parts = bytes.toList().chunked(size)
        return parts.mapIndexed { i, p -> RecordAsk.Save(id, name, mime, share, i, i == parts.lastIndex, Base64.getEncoder().encodeToString(p.toByteArray())) }
    }

    @Test fun `a file comes back whole, saved under a safe name`() = runTest {
        val saver = Saver()
        val s = saves(saver)
        val bytes = ByteArray(10) { it.toByte() }
        val notes = chunks("u1", bytes).map { s.put(it) }
        assertThat(notes.dropLast(1).all { it == null }).isTrue()
        assertThat(notes.last()).isEqualTo(RecordNote.Saved)
        val (got, name, share) = saver.got.single()
        assertThat(got).isEqualTo(bytes)
        assertThat(name).isEqualTo("Your record.png")
        assertThat(share).isFalse()
        // nothing left behind
        assertThat(parts.listFiles().orEmpty()).isEmpty()
    }

    @Test fun `a share goes to the share sheet, and says nothing more`() = runTest {
        val saver = Saver()
        val s = saves(saver)
        val notes = chunks("u2", byteArrayOf(1, 2, 3, 4), name = "race.mp4", mime = "video/mp4", share = true).map { s.put(it) }
        assertThat(notes.all { it == null }).isTrue()
        assertThat(saver.got.single().third).isTrue()
    }

    @Test fun `an empty file is one chunk`() = runTest {
        val saver = Saver()
        assertThat(saves(saver).put(chunks("u3", ByteArray(0)).single())).isEqualTo(RecordNote.Saved)
        assertThat(saver.got.single().first).isEmpty()
    }

    @Test fun `a chunk out of order, or of another file, drops what was gathered`() = runTest {
        val saver = Saver()
        val s = saves(saver)
        val a = chunks("a", ByteArray(9) { 1 })
        s.put(a[0])
        assertThat(s.put(a[2])).isNull() // seq 1 never came
        assertThat(s.put(a[1])).isNull() // and the file is gone: this one is an orphan now
        assertThat(saver.got).isEmpty()
        // a new file starts over: the old one's pieces don't leak in
        val b = chunks("b", byteArrayOf(7, 8, 9))
        s.put(a[0])
        assertThat(s.put(b.single())).isEqualTo(RecordNote.Saved)
        assertThat(saver.got.single().first).isEqualTo(byteArrayOf(7, 8, 9))
    }

    @Test fun `the page going drops a file half made`() = runTest {
        val saver = Saver()
        val s = saves(saver)
        val a = chunks("a", ByteArray(6) { 1 })
        s.put(a[0])
        s.drop()
        assertThat(s.put(a[1])).isNull()
        assertThat(saver.got).isEmpty()
        assertThat(parts.listFiles().orEmpty()).isEmpty()
    }

    @Test fun `too big, or not base64, is a failed save`() = runTest {
        val saver = Saver()
        assertThat(saves(saver, max = 5).let { s -> chunks("a", ByteArray(6)).map { s.put(it) } }).contains(RecordNote.SaveFailed)
        assertThat(saves(saver).put(RecordAsk.Save("b", "x.png", "image/png", false, 0, true, "%%%"))).isEqualTo(RecordNote.SaveFailed)
        assertThat(saver.got).isEmpty()
        assertThat(saves(Saver(ok = false)).put(chunks("c", byteArrayOf(1)).single())).isEqualTo(RecordNote.SaveFailed)
    }

    @Test fun `names are made safe, with the extension the type calls for`() {
        assertThat(RecordSaves.fileName("Your record · 2026.png", "image/png")).isEqualTo("Your record - 2026.png")
        assertThat(RecordSaves.fileName("../../etc/passwd", "image/png")).isEqualTo("-.-etc-passwd.png")
        assertThat(RecordSaves.fileName("race", "video/mp4")).isEqualTo("race.mp4")
        assertThat(RecordSaves.fileName("race.webm", "video/mp4")).isEqualTo("race.mp4")
        assertThat(RecordSaves.fileName("   ", "video/webm")).isEqualTo("stash-record.webm")
        assertThat(RecordSaves.fileName("Bj\u00f6rk's record", "image/png")).isEqualTo("Bj\u00f6rk-s record.png")
        assertThat(RecordSaves.fileName("data", "application/octet-stream")).isEqualTo("data.bin")
        assertThat(RecordSaves.fileName("x".repeat(500), "image/png")).hasLength(96 + 4)
    }

    @Test fun `each chunk is acknowledged once it is in the file, not before`() = runTest {
        val s = saves(Saver())
        val sizes = mutableListOf<Long>()
        chunks("u1", ByteArray(9) { it.toByte() }).forEach { c ->
            s.take(c) { sizes += parts.listFiles().orEmpty().sumOf { it.length() } }
        }
        assertThat(sizes).containsExactly(3L, 6L, 9L).inOrder()
    }

    @Test fun `the page giving up drops that file, and only that one`() = runTest {
        val saver = Saver()
        val s = saves(saver)
        val a = chunks("a", ByteArray(6) { 1 })
        s.put(a[0])
        s.abort("someone-else")
        assertThat(s.put(a[1])).isEqualTo(RecordNote.Saved)
        val b = chunks("b", ByteArray(6) { 2 })
        s.put(b[0])
        s.abort("b")
        assertThat(parts.listFiles().orEmpty()).isEmpty()
        assertThat(s.put(b[1])).isNull()
        assertThat(saver.got).hasSize(1)
    }

    @Test fun `a visit saves at most MAX_FILES files, then says so`() = runTest {
        val saver = Saver()
        val s = saves(saver, files = 2)
        repeat(2) { assertThat(s.put(chunks("f$it", byteArrayOf(1)).single())).isEqualTo(RecordNote.Saved) }
        val third = chunks("f2", byteArrayOf(1, 2, 3, 4))
        assertThat(s.put(third[0])).isEqualTo(RecordNote.TooManySaves)
        assertThat(s.put(third[1])).isNull()
        assertThat(saver.got).hasSize(2)
        assertThat(parts.listFiles().orEmpty()).isEmpty()
    }

    @Test fun `below Android 10 a save opens the share sheet, and claims nothing`() = runTest {
        val saver = Saver(saved = SaveOutcome.SHARED)
        assertThat(saves(saver).put(chunks("u", byteArrayOf(1)).single())).isNull()
        assertThat(saver.got).hasSize(1)
    }

    @Test fun `clearNow clears a file half made, with nothing running`() = runTest {
        val s = saves(Saver())
        s.put(chunks("a", ByteArray(6) { 1 })[0])
        assertThat(parts.listFiles().orEmpty()).hasLength(1)
        s.clearNow()
        assertThat(parts.listFiles().orEmpty()).isEmpty()
    }
}
