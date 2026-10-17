package com.stash.feature.library.allsongs

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScannedCoverStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = ScannedCoverStore(File(tmp.root, "art"))

    @Test
    fun `no embedded art is an empty name, not a failure`() {
        assertThat(store().save(null)).isEqualTo("")
        assertThat(store().save(ByteArray(0))).isEqualTo("")
    }

    @Test
    fun `a cover is saved whole under a name of its content and shared`() {
        val store = store()
        val bytes = ByteArray(1000) { it.toByte() }
        val a = store.save(bytes)
        val b = store.save(bytes)
        assertThat(a).isNotNull()
        assertThat(a).isEqualTo(b)
        assertThat(store.file(a!!).readBytes()).isEqualTo(bytes)
        assertThat(store.names()).containsExactly(a)
    }

    @Test
    fun `a half-written file from an older build is rewritten`() {
        val store = store()
        val bytes = ByteArray(1000) { it.toByte() }
        val name = store.save(bytes)!!
        store.file(name).writeBytes(bytes.copyOf(10))
        assertThat(store.save(bytes)).isEqualTo(name)
        assertThat(store.file(name).readBytes()).isEqualTo(bytes)
    }

    @Test
    fun `a failed write is null, so the row stays NULL and is retried`() {
        val blocker = File(tmp.root, "art").apply { writeText("a file where the directory should be") }
        assertThat(ScannedCoverStore(blocker).save(ByteArray(10) { 1 })).isNull()
    }

    @Test
    fun `no temp file is left behind`() {
        val store = store()
        store.save(ByteArray(10) { 1 })
        assertThat(store.names().none { it.endsWith(".tmp") }).isTrue()
    }

    @Test
    fun `sweep keeps named covers and deletes the rest of ours, nothing else`() {
        val store = store()
        store.dir.mkdirs()
        listOf("scanned_a.jpg", "scanned_b.jpg", "scanned_x.tmp", "other.jpg").forEach { store.file(it).writeText("x") }
        store.sweep(setOf("scanned_a.jpg"))
        assertThat(store.names()).containsExactly("scanned_a.jpg", "other.jpg")
    }
}