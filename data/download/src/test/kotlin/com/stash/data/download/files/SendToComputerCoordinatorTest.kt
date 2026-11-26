package com.stash.data.download.files

import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * [SendToComputerCoordinator] with the send folder in memory and a test WorkManager (no send work runs: the copy is
 * called as the worker would call it). How a send ends when it is cancelled, cut short, out of room or not answered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SendToComputerCoordinatorTest {

    /** The send folder, in memory, safe to use from the coordinator's threads and the test's. */
    private class Folder : SendFolder {
        private val files = LinkedHashMap<String, SentFile>()
        @Volatile var list: String? = null
        /** Runs as the list is written, before it is there: a test holds the copy here, or cancels it. */
        @Volatile var onList: () -> Unit = {}
        /** Runs as the folder is cleared, before anything goes. */
        @Volatile var onClear: () -> Unit = {}
        @Volatile var listInTheWay = false
        /** The phone is full after this many writes: the next one fails as a full phone's does, after [onFull]. */
        @Volatile var fullAfter: Int? = null
        @Volatile var onFull: () -> Unit = {}
        /** How many listings to come go unanswered. */
        val unanswered = AtomicInteger()
        private var writes = 0

        @Synchronized fun names(): Set<String> = files.keys.toSet() + listOfNotNull(list?.let { SongSender.LIST_NAME })
        @Synchronized fun put(name: String, bytes: Long) { files[name] = SentFile(name, bytes) }
        /** The computer takes [name]: it deletes the file. */
        @Synchronized fun take(name: String) { if (name == SongSender.LIST_NAME) list = null else files.remove(name) }

        override fun files(): List<SentFile> {
            if (unanswered.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) throw IllegalStateException("MediaStore did not answer.")
            return synchronized(this) { files.values.toList() + listOfNotNull(list?.let { SentFile(SongSender.LIST_NAME, it.length.toLong()) }) }
        }

        override fun write(name: String, source: InputStream, keepGoing: () -> Boolean): SentFile? {
            val full = synchronized(this) { fullAfter?.let { writes >= it } == true }
            if (full) {
                onFull()
                throw IOException("write failed: ENOSPC (No space left on device)")
            }
            val bytes = source.readBytes()
            return synchronized(this) {
                writes++
                SentFile(name, bytes.size.toLong()).also { files[name] = it }
            }
        }

        @Synchronized override fun delete(name: String) = take(name)

        override fun writeList(text: String) {
            onList()
            if (listInTheWay) throw ListInTheWayException()
            list = text
        }

        override fun clear(): Int {
            onClear()
            return synchronized(this) { names().size.also { files.clear(); list = null } }
        }

        override fun readList(): String? = list
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val folder = Folder()
    private val tracks = mutableListOf<TrackEntity>()
    private val trackDao: TrackDao = mockk { coEvery { getAllDownloaded() } answers { tracks.toList() } }
    private val playlistDao: PlaylistDao = mockk { coEvery { playlistNamesOfSongs() } returns emptyList() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var coordinator: SendToComputerCoordinator

    private val clearing get() = File(context.filesDir, "send_to_computer.clearing")
    private fun memory() = SendMemory(File(context.filesDir, "send_to_computer.json"))

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build(),
        )
        room(4_000_000_000L)
        val music = File(context.filesDir, "music").apply { mkdirs() }
        for (id in 1L..2L) {
            val file = File(music, "$id.flac").apply { writeBytes(ByteArray(10) { id.toByte() }) }
            tracks += TrackEntity(id = id, title = "Song $id", artist = "Band", filePath = file.path, isDownloaded = true, fileFormat = "flac")
        }
        coordinator = SendToComputerCoordinator(context, trackDao, playlistDao).also { it.folderForTest = folder }
    }

    @After fun tearDown() {
        // A watch left running is stopped (it clears the fake folder, which goes with the test).
        val s = coordinator.state.value
        if (s is SendToComputerState.Waiting || s is SendToComputerState.Taking) {
            coordinator.cancel()
            awaitState { it is SendToComputerState.Idle }
        }
        scope.cancel()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    /** The room free on the phone, as StatFs tells it (in its 4 KB blocks). */
    private fun room(bytes: Long) {
        val blocks = (bytes / 4096).toInt()
        ShadowStatFs.registerStats(Environment.getExternalStorageDirectory(), blocks, blocks, blocks)
    }

    private fun awaitState(timeoutMs: Long = 10_000L, what: (SendToComputerState) -> Boolean): SendToComputerState =
        runBlocking { withTimeout(timeoutMs) { coordinator.state.first(what) } }

    private fun awaitTrue(timeoutMs: Long = 10_000L, what: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!what()) {
            check(System.currentTimeMillis() < until) { "timed out" }
            Thread.sleep(20)
        }
    }

    /** Every state shown from now on. */
    private fun recordStates(): List<SendToComputerState> {
        val seen = Collections.synchronizedList(mutableListOf<SendToComputerState>())
        scope.launch { coordinator.state.collect { seen += it } }
        return seen
    }

    @Test fun `a send copies the songs, then waits for the computer`() {
        runBlocking { coordinator.copy { _, _ -> } }
        assertThat(coordinator.state.value).isEqualTo(SendToComputerState.Waiting(2, 0))
        assertThat(folder.names()).containsExactly("1.flac", "2.flac", SongSender.LIST_NAME)
    }

    @Test fun `the notification's Cancel as the list is written removes the send, not leave it waiting`() {
        val seen = recordStates()
        val job: Job = scope.launch(start = CoroutineStart.LAZY) { coordinator.copy { _, _ -> } }
        folder.onList = { job.cancel() }
        job.start()
        runBlocking { job.join() }
        awaitState { it is SendToComputerState.Idle }
        assertThat(folder.names()).isEmpty()
        assertThat(seen.none { it is SendToComputerState.Waiting }).isTrue()
    }

    @Test fun `Cancel removes nothing until the copy has stopped, then everything it wrote`() {
        val writing = CountDownLatch(1)
        val goOn = CountDownLatch(1)
        folder.onList = {
            writing.countDown()
            goOn.await(10, TimeUnit.SECONDS)
        }
        val markedWhileClearing = AtomicBoolean()
        folder.onClear = { markedWhileClearing.set(clearing.exists()) }
        val job = scope.launch { coordinator.copy { _, _ -> } }
        assertThat(writing.await(10, TimeUnit.SECONDS)).isTrue()

        coordinator.cancel()
        Thread.sleep(500)
        // Still writing the list: nothing removed behind it.
        assertThat(folder.names()).containsExactly("1.flac", "2.flac")

        goOn.countDown()
        runBlocking { job.join() }
        awaitState { it is SendToComputerState.Idle }
        // The list written after the cancel went too.
        assertThat(folder.names()).isEmpty()
        assertThat(markedWhileClearing.get()).isTrue()
        assertThat(clearing.exists()).isFalse()
        assertThat(memory().sending()).isEmpty()
        assertThat(memory().onComputer()).isEmpty()
    }

    @Test fun `a cancel cut short is finished when the app looks again, not shown as waiting`() {
        folder.put("1.flac", 10)
        folder.put("2.flac", 10)
        folder.list = """{"songs":[{"file":"1.flac"},{"file":"2.flac"}]}"""
        clearing.createNewFile()
        val seen = recordStates()

        coordinator.resume()
        awaitTrue { !clearing.exists() && coordinator.state.value is SendToComputerState.Idle }
        assertThat(folder.names()).isEmpty()
        assertThat(seen.none { it is SendToComputerState.Waiting }).isTrue()
    }

    @Test fun `a new send drops what a cancel cut short left marked`() {
        clearing.createNewFile()
        runBlocking { coordinator.copy { _, _ -> } }
        assertThat(clearing.exists()).isFalse()
        assertThat(coordinator.state.value).isInstanceOf(SendToComputerState.Waiting::class.java)
    }

    @Test fun `another copy of Stash's list in the folder stops the send, keeps the copies and notes nothing`() {
        folder.listInTheWay = true
        runBlocking { coordinator.copy { _, _ -> } }
        val error = coordinator.state.value as SendToComputerState.Error
        assertThat(error.listInTheWay).isTrue()
        assertThat(folder.names()).containsExactly("1.flac", "2.flac")
        // Not being sent: the copies going later (the computer deleting the folder with the other send) is no taking.
        assertThat(memory().sending()).isEmpty()
        assertThat(memory().onComputer()).isEmpty()
    }

    @Test fun `a phone that fills up partway waits for the computer with what was copied`() {
        folder.fullAfter = 1
        folder.onFull = { room(0) }
        runBlocking { coordinator.copy { _, _ -> } }
        val waiting = coordinator.state.value as SendToComputerState.Waiting
        assertThat(waiting.total).isEqualTo(1)
        assertThat(waiting.notCopied).isEqualTo(1)
        assertThat(waiting.noRoom).isEqualTo(10L + SongSender.SPARE)
        assertThat(folder.names()).containsExactly("1.flac", SongSender.LIST_NAME)
        assertThat(memory().sending().keys).containsExactly("1.flac")
    }

    @Test fun `no room with copies of the send left offers to remove them`() {
        // Song 1 is in the folder whole from a send that stopped; song 2 won't fit.
        folder.put("1.flac", 10)
        room(0)
        runBlocking { coordinator.copy { _, _ -> } }
        val error = coordinator.state.value as SendToComputerState.Error
        assertThat(error.copiesLeft).isTrue()

        coordinator.cancel()
        awaitState { it is SendToComputerState.Idle }
        assertThat(folder.names()).isEmpty()
    }

    @Test fun `no room with nothing in the folder has nothing to remove`() {
        room(0)
        runBlocking { coordinator.copy { _, _ -> } }
        assertThat((coordinator.state.value as SendToComputerState.Error).copiesLeft).isFalse()
    }

    @Test fun `a folder that doesn't answer for a while is no news, and the watch goes on`() {
        runBlocking { coordinator.copy { _, _ -> } }
        folder.unanswered.set(2)
        Thread.sleep(2_500)
        assertThat(coordinator.state.value).isInstanceOf(SendToComputerState.Waiting::class.java)
        assertThat(memory().onComputer()).isEmpty()

        // The computer takes everything: seen by the watch still running.
        folder.take("1.flac")
        folder.take("2.flac")
        folder.take(SongSender.LIST_NAME)
        assertThat(awaitState { it is SendToComputerState.Finished }).isEqualTo(SendToComputerState.Finished(2, 2))
        assertThat(memory().onComputer()).containsExactly(1L, 2L)
    }
}
