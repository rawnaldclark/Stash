package com.stash.core.data.db

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** [PreferencesFileScrubber] against files DataStore itself wrote and reads back. */
class PreferencesFileScrubberTest {
    private val dir: File = Files.createTempDirectory("scrub").toFile()
    private var n = 0

    @After fun tearDown() { dir.deleteRecursively() }

    private fun <T> withStore(file: File, block: suspend (androidx.datastore.core.DataStore<Preferences>) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            block(PreferenceDataStoreFactory.create(scope = scope) { file })
        } finally {
            scope.cancel()
        }
    }

    private fun written(block: (MutablePreferences) -> Unit): ByteArray {
        val f = File(dir, "w${n++}.preferences_pb")
        withStore(f) { it.edit(block) }
        return f.readBytes()
    }

    private fun read(bytes: ByteArray): Preferences {
        val f = File(dir, "r${n++}.preferences_pb").apply { writeBytes(bytes) }
        return withStore(f) { it.data.first() }
    }

    private val endpoint = stringPreferencesKey("bitchord_endpoint")
    private val token = stringPreferencesKey("bitchord_access_token")

    @Test fun `drops the named keys and keeps every other value of every type`() {
        val bytes = written {
            it[endpoint] = "https://tidal.mine.example/scrub-test-path"
            it[booleanPreferencesKey("enabled")] = false
            it[token] = "scrub-test-token"
            it[intPreferencesKey("i")] = -7
            it[longPreferencesKey("l")] = 1L shl 40
            it[floatPreferencesKey("f")] = 1.5f
            it[doublePreferencesKey("d")] = 2.25
            it[stringSetPreferencesKey("s")] = setOf("a", "b")
            it[stringPreferencesKey("lossless_quality_tier")] = "HI_RES"
        }
        val clean = PreferencesFileScrubber.scrub(bytes, setOf("bitchord_endpoint", "bitchord_access_token"))

        val text = clean.toString(Charsets.ISO_8859_1)
        assertThat(text).doesNotContain("scrub-test-path")
        assertThat(text).doesNotContain("scrub-test-token")
        assertThat(text).doesNotContain("bitchord")

        val p = read(clean)
        assertThat(p[endpoint]).isNull()
        assertThat(p[token]).isNull()
        assertThat(p[booleanPreferencesKey("enabled")]).isFalse()
        assertThat(p[intPreferencesKey("i")]).isEqualTo(-7)
        assertThat(p[longPreferencesKey("l")]).isEqualTo(1L shl 40)
        assertThat(p[floatPreferencesKey("f")]).isEqualTo(1.5f)
        assertThat(p[doublePreferencesKey("d")]).isEqualTo(2.25)
        assertThat(p[stringSetPreferencesKey("s")]).containsExactly("a", "b")
        assertThat(p[stringPreferencesKey("lossless_quality_tier")]).isEqualTo("HI_RES")
    }

    @Test fun `a file without the keys comes back byte for byte`() {
        val bytes = written { it[booleanPreferencesKey("enabled")] = true }
        assertThat(PreferencesFileScrubber.scrub(bytes, setOf("bitchord_endpoint"))).isEqualTo(bytes)
        assertThat(PreferencesFileScrubber.scrub(ByteArray(0), setOf("bitchord_endpoint"))).isEmpty()
    }

    @Test fun `bytes that are not a preferences file are refused`() {
        for (junk in listOf("theme".toByteArray(), byteArrayOf(0x0A, 0x7F), byteArrayOf(0x0F), byteArrayOf(0x0A, 0x05, 0x0A))) {
            assertThrows(IllegalArgumentException::class.java) { PreferencesFileScrubber.scrub(junk, setOf("x")) }
        }
        // A copy cut short (DataStore's .tmp after a crash): refused, never half-read.
        val whole = written { it[endpoint] = "https://tidal.mine.example/x"; it[token] = "t" }
        assertThrows(IllegalArgumentException::class.java) {
            PreferencesFileScrubber.scrub(whole.copyOf(whole.size - 3), setOf("bitchord_endpoint", "bitchord_access_token"))
        }
    }
}
