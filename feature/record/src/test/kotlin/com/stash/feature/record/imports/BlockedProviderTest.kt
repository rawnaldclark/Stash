package com.stash.feature.record.imports

import com.google.common.truth.Truth.assertThat
import com.stash.feature.record.importer.MemoryArchive
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
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
        val reading = CountDownLatch(1) // the provider's read is under way (and about to block)
        val queued = CountDownLatch(1) // the second import was copied and queued
        val blocked = IntakeSource("my_spotify_data.zip", null) {
            object : InputStream() {
                override fun read(): Int = read(ByteArray(1), 0, 1)
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    reading.countDown()
                    release.await(60, TimeUnit.SECONDS)
                    return -1
                }
            }
        }
        val files = tmp.newFolder()
        // Everything the import runs (its scope and its io) is on this one executor, so the test can wait for it to be done.
        val pool = TrackedExecutor()
        val scope = CoroutineScope(SupervisorJob() + pool.dispatcher)
        val imports = RecordImports(
            store = RecordImportStore(MemoryPrefs()),
            archive = MemoryArchive(),
            ownSpan = { null to null },
            intake = ImportIntake(files),
            scratch = tmp.newFolder(),
            sourceOf = { error("no uris here") },
            launch = { queued.countDown() },
            cancelJob = {},
            jobAlive = { false },
            scope = scope,
            now = { NOW },
            zone = ZoneOffset.UTC,
            io = pool.dispatcher,
        )
        fun parts() = files.listFiles().orEmpty().filter { it.name.startsWith("part") }
        fun copies() = files.listFiles().orEmpty().filter { !it.name.startsWith("part") }

        imports.bringSources(listOf(blocked), shared = false)
        await(reading) // the read is under way, and blocked; its part was reserved before it
        assertThat(parts()).isNotEmpty()

        imports.cancel()
        assertThat(imports.state.value).isEqualTo(ImportState.Idle)

        // The next import is copied and queued while the first read still blocks
        val fixture = resource("my_spotify_data.zip")
        imports.bringSources(listOf(IntakeSource("my_spotify_data.zip", fixture.length()) { fixture.inputStream() }), shared = false)
        await(queued)
        assertThat(copies()).hasSize(1)
        assertThat(copies().single().length()).isEqualTo(fixture.length())

        // The blocked read returns at last: its part goes with it, and the new import's copy stays
        release.countDown()
        pool.awaitIdle() // the read ends, its part goes, and nothing else is left running
        assertThat(parts()).isEmpty()
        assertThat(copies()).hasSize(1)
        scope.cancel()
        pool.close()
    }

    /** Waits for [latch]; the timeout only turns a hang into a failure, nothing depends on it. */
    private fun await(latch: CountDownLatch) = check(latch.await(60, TimeUnit.SECONDS)) { "timed out" }

    /**
     * An executor that counts the tasks it has not finished: [awaitIdle] returns when there are none. Work that hops to
     * another dispatcher does so from inside a running task, so the count is never zero in between. The timeout only turns
     * a hang into a failure, nothing depends on it.
     */
    private class TrackedExecutor : Executor {
        private val threads = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        private val lock = Object()
        private var inFlight = 0
        val dispatcher: CoroutineDispatcher = (this as Executor).asCoroutineDispatcher()

        override fun execute(command: Runnable) {
            synchronized(lock) { inFlight++ }
            try {
                threads.execute {
                    try {
                        command.run()
                    } finally {
                        synchronized(lock) { inFlight--; lock.notifyAll() }
                    }
                }
            } catch (e: Throwable) {
                synchronized(lock) { inFlight--; lock.notifyAll() }
                throw e
            }
        }

        fun awaitIdle() {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            synchronized(lock) {
                while (inFlight > 0) {
                    val left = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime())
                    check(left > 0) { "timed out" }
                    lock.wait(left)
                }
            }
        }

        fun close() {
            threads.shutdownNow()
        }
    }

    private companion object {
        const val NOW = 1_792_756_800_000L
    }
}
