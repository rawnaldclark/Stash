package com.stash.feature.record.imports

import com.google.common.truth.Truth.assertThat
import com.stash.feature.record.importer.MemoryArchive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A provider whose read never returns (a stalled cloud provider, a hostile one), on real threads: it holds up neither the
 * person's Cancel nor the next import, and its part is deleted when the read finally returns.
 */
class BlockedProviderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun resource(name: String): File = File(requireNotNull(javaClass.getResource("/spotify-export/$name")).toURI())

    @Test(timeout = 30_000)
    fun `a provider read that never returns holds up neither a Cancel nor the next import`() = runBlocking {
        val release = CountDownLatch(1)
        val blocked = IntakeSource("my_spotify_data.zip", null) {
            object : InputStream() {
                override fun read(): Int = read(ByteArray(1), 0, 1)
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    release.await(60, TimeUnit.SECONDS)
                    return -1
                }
            }
        }
        val files = tmp.newFolder()
        var launches = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val imports = RecordImports(
            store = RecordImportStore(MemoryPrefs()),
            archive = MemoryArchive(),
            ownSpan = { null to null },
            intake = ImportIntake(files),
            scratch = tmp.newFolder(),
            sourceOf = { error("no uris here") },
            launch = { launches++ },
            cancelJob = {},
            jobAlive = { false },
            scope = scope,
            now = { NOW },
            zone = ZoneOffset.UTC,
            io = Dispatchers.IO,
        )
        fun parts() = files.listFiles().orEmpty().filter { it.name.startsWith("part") }
        fun copies() = files.listFiles().orEmpty().filter { !it.name.startsWith("part") }

        imports.bringSources(listOf(blocked), shared = false)
        waitUntil { parts().isNotEmpty() } // the read is under way, and blocked

        imports.cancel()
        assertThat(imports.state.value).isEqualTo(ImportState.Idle)

        // The next import is copied and queued while the first read still blocks
        val fixture = resource("my_spotify_data.zip")
        imports.bringSources(listOf(IntakeSource("my_spotify_data.zip", fixture.length()) { fixture.inputStream() }), shared = false)
        waitUntil { launches == 1 }
        assertThat(copies()).hasSize(1)
        assertThat(copies().single().length()).isEqualTo(fixture.length())

        // The blocked read returns at last: its part goes with it, and the new import's copy stays
        release.countDown()
        waitUntil { parts().isEmpty() }
        assertThat(copies()).hasSize(1)
        scope.cancel()
    }

    private suspend fun waitUntil(check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!check()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            delay(20)
        }
    }

    private companion object {
        const val NOW = 1_792_756_800_000L
    }
}
