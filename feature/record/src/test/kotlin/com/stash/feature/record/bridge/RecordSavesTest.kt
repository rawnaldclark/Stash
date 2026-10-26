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
    private class Saver(private val ok: Boolean = true) : RecordSaver {
        val got = mutableListOf<Triple<ByteArray, String, Boolean>>()
        override suspend fun save(file: File, name: String, mime: String): Boolean {
            got += Triple(file.readBytes(), name, false)
            return ok
        }

        override suspend fun share(file: File, name: String, mime: String): Boolean {
            got += Triple(file.readBytes(), name, true)
            return ok
        }
    }

    private fun saves(saver: RecordSaver, max: Long = RecordSaves.MAX_BYTES) = RecordSaves(File(dir.root, "parts"), saver, max, Dispatchers.Unconfined)

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
        val notes = chunks("u1", bytes).map { s.take(it) }
        assertThat(notes.dropLast(1).all { it == null }).isTrue()
        assertThat(notes.last()).isEqualTo(RecordNote.Saved)
        val (got, name, share) = saver.got.single()
        assertThat(got).isEqualTo(bytes)
        assertThat(name).isEqualTo("Your record.png")
        assertThat(share).isFalse()
        // nothing left behind
        assertThat(File(dir.root, "parts").listFiles().orEmpty()).isEmpty()
    }

    @Test fun `a share goes to the share sheet, and says nothing more`() = runTest {
        val saver = Saver()
        val s = saves(saver)
        val notes = chunks("u2", byteArrayOf(1, 2, 3, 4), name = "race.mp4", mime = "video/mp4", share = true).map { s.take(it) }
        assertThat(notes.all { it == null }).isTrue()
        assertThat(saver.got.single().third).isTrue()
    }

    @Test fun `an empty file is one chunk`() = runTest {
        val saver = Saver()
        assertThat(saves(saver).take(chunks("u3", ByteArray(0)).single())).isEqualTo(RecordNote.Saved)
        assertThat(saver.got.single().first).isEmpty()
    }

    @Test fun `a chunk out of order, or of another file, drops what was gathered`() = runTest {
        val saver = Saver()
        val s = saves(saver)
        val a = chunks("a", ByteArray(9) { 1 })
        s.take(a[0])
        assertThat(s.take(a[2])).isNull() // seq 1 never came
        assertThat(s.take(a[1])).isNull() // and the file is gone: this one is an orphan now
        assertThat(saver.got).isEmpty()
        // a new file starts over: the old one's pieces don't leak in
        val b = chunks("b", byteArrayOf(7, 8, 9))
        s.take(a[0])
        assertThat(s.take(b.single())).isEqualTo(RecordNote.Saved)
        assertThat(saver.got.single().first).isEqualTo(byteArrayOf(7, 8, 9))
    }

    @Test fun `the page going drops a file half made`() = runTest {
        val saver = Saver()
        val s = saves(saver)
        val a = chunks("a", ByteArray(6) { 1 })
        s.take(a[0])
        s.drop()
        assertThat(s.take(a[1])).isNull()
        assertThat(saver.got).isEmpty()
        assertThat(File(dir.root, "parts").listFiles().orEmpty()).isEmpty()
    }

    @Test fun `too big, or not base64, is a failed save`() = runTest {
        val saver = Saver()
        assertThat(saves(saver, max = 5).let { s -> chunks("a", ByteArray(6)).map { s.take(it) } }).contains(RecordNote.SaveFailed)
        assertThat(saves(saver).take(RecordAsk.Save("b", "x.png", "image/png", false, 0, true, "%%%"))).isEqualTo(RecordNote.SaveFailed)
        assertThat(saver.got).isEmpty()
        assertThat(saves(Saver(ok = false)).take(chunks("c", byteArrayOf(1)).single())).isEqualTo(RecordNote.SaveFailed)
    }

    @Test fun `names are made safe, with the extension the type calls for`() {
        assertThat(RecordSaves.fileName("Your record · 2026.png", "image/png")).isEqualTo("Your record - 2026.png")
        assertThat(RecordSaves.fileName("../../etc/passwd", "image/png")).isEqualTo("-.-etc-passwd.png")
        assertThat(RecordSaves.fileName("race", "video/mp4")).isEqualTo("race.mp4")
        assertThat(RecordSaves.fileName("race.webm", "video/mp4")).isEqualTo("race.mp4")
        assertThat(RecordSaves.fileName("   ", "video/webm")).isEqualTo("stash-record.webm")
        assertThat(RecordSaves.fileName("Björk's record", "image/png")).isEqualTo("Björk-s record.png")
        assertThat(RecordSaves.fileName("data", "application/octet-stream")).isEqualTo("data.bin")
        assertThat(RecordSaves.fileName("x".repeat(500), "image/png")).hasLength(96 + 4)
    }
}
