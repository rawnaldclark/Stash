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
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.util.concurrent.atomic.AtomicReference

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
        /** The ids of this app's files in the Files app's Trash. */
        private val bin = HashSet<Long>()
        private var nextId = 100L
        @Volatile var list: String? = null
        /** Runs as the list is written, before it is there: a test holds the copy here, or cancels it. */
        @Volatile var onList: () -> Unit = {}
        /** Runs as the folder is cleared, before anything goes. */
        @Volatile var onClear: () -> Unit = {}
        /** Runs as the folder is listed, before it answers: a test holds a look of the watch here. */
        @Volatile var onFiles: () -> Unit = {}
        @Volatile var listInTheWay = false
        /** The phone is full after this many writes: the next one fails as a full phone's does, after [onFull]. */
        @Volatile var fullAfter: Int? = null
        @Volatile var onFull: () -> Unit = {}
        /** How many listings to come go unanswered. */
        val unanswered = AtomicInteger()
        /** How many reads of the list to come go unanswered. */
        val listUnanswered = AtomicInteger()
        /** How many looks in the Trash to come go unanswered, once something is in it. */
        val trashUnanswered = AtomicInteger()
        private var writes = 0

        @Synchronized fun names(): Set<String> = files.keys.toSet() + listOfNotNull(list?.let { SongSender.LIST_NAME })
        @Synchronized fun put(name: String, bytes: Long) { files[name] = SentFile(name, bytes) }
        /** The computer takes [name]: it deletes the file. */
        @Synchronized fun take(name: String) { if (name == SongSender.LIST_NAME) list = null else files.remove(name) }
        /** [name] is put in the Trash on the phone: gone from the folder, under its own id in the Trash. */
        @Synchronized fun trash(name: String) { files.remove(name)?.id?.let { bin += it } }
        @Synchronized fun emptyTrash() { bin.clear() }

        override fun files(): List<SentFile> {
            onFiles()
            if (unanswered.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) throw NoAnswerException()
            return synchronized(this) { files.values.toList() + listOfNotNull(list?.let { SentFile(SongSender.LIST_NAME, it.length.toLong()) }) }
        }

        override fun trashed(): Set<Long> {
            val binned = synchronized(this) { bin.toSet() }
            if (binned.isNotEmpty() && trashUnanswered.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) throw NoAnswerException()
            return binned
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
                SentFile(name, bytes.size.toLong(), id = nextId++).also { files[name] = it }
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

        override fun readList(): String? {
            if (listUnanswered.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) throw NoAnswerException()
            return list
        }
    }

    /** A list naming songs 1 and 2, as a send of them leaves it. */
    private val listOfTwo = """{"songs":[{"file":"1.flac"},{"file":"2.flac"}]}"""
    private fun sent(vararg ids: Long) = ids.associate { "$it.flac" to SendMemory.Sent(it, null) }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val folder = Folder()
    private val tracks = mutableListOf<TrackEntity>()
    private val trackDao: TrackDao = mockk { coEvery { getAllDownloaded() } answers { tracks.toList() } }
    private val playlistDao: PlaylistDao = mockk { coEvery { playlistNamesOfSongs() } returns emptyList() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var coordinator: SendToComputerCoordinator

    /** A cancel's clear-up under way. */
    private val clearing get() = File(context.filesDir, "send_to_computer.clearing")
    /** A clear-up of the copies the computer left under way. */
    private val left get() = File(context.filesDir, "send_to_computer.clearing-left")
    /** The clear-up under way has begun removing. */
    private val cleared get() = File(context.filesDir, "send_to_computer.cleared")
    private fun marked() = clearing.exists() || left.exists() || cleared.exists()
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
        assertThat(coordinator.state.value).isEqualTo(SendToComputerState.Waiting(2))
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
        // Marked at once, before the wait for the copy: the app killed now still has the clear-up finished.
        assertThat(clearing.exists()).isTrue()
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
        val shown = AtomicReference<SendToComputerState>()
        folder.onClear = { shown.set(coordinator.state.value) }

        coordinator.resume()
        awaitTrue { !clearing.exists() && coordinator.state.value is SendToComputerState.Idle }
        assertThat(folder.names()).isEmpty()
        assertThat(seen.none { it is SendToComputerState.Waiting }).isTrue()
        // A cancel's clear-up says it is stopping.
        assertThat(shown.get()).isEqualTo(SendToComputerState.Cancelling)
    }

    @Test fun `a new send drops what a cancel cut short left marked`() {
        clearing.createNewFile()
        left.createNewFile()
        runBlocking { coordinator.copy { _, _ -> } }
        assertThat(marked()).isFalse()
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

        // The copies, which nothing takes now, can be removed from the card.
        assertThat(error.copiesLeft).isTrue()
        coordinator.cancel()
        awaitState { it is SendToComputerState.Idle }
        assertThat(folder.names()).isEmpty()
    }

    @Test fun `a phone that fills up partway waits for the computer with what was copied`() {
        folder.fullAfter = 1
        folder.onFull = { room(0) }
        runBlocking { coordinator.copy { _, _ -> } }
        val waiting = coordinator.state.value as SendToComputerState.Waiting
        assertThat(waiting.total).isEqualTo(1)
        // Song 2 was not copied for room (song 2's 10 bytes and the room to spare, with none free, less the 10 bytes song
        // 1's copy frees once taken), and none for another reason.
        val rest = SendMemory.Rest(failed = 0, full = 1, noRoom = SongSender.SPARE)
        assertThat(waiting.rest).isEqualTo(rest)
        assertThat(folder.names()).containsExactly("1.flac", SongSender.LIST_NAME)
        assertThat(memory().sending().keys).containsExactly("1.flac")
        // Kept with the send, to be told after a restart.
        assertThat(memory().rest()).isEqualTo(rest)

        // The computer takes what was copied: the end says what wasn't, why, and what it needs.
        folder.take("1.flac")
        folder.take(SongSender.LIST_NAME)
        assertThat(awaitState { it is SendToComputerState.Finished }).isEqualTo(SendToComputerState.Finished(1, 1, rest = rest))
        assertThat(memory().rest()).isEqualTo(SendMemory.Rest())
    }

    @Test fun `what a send left out is shown again after a restart`() {
        folder.put("1.flac", 10)
        folder.list = """{"songs":[{"file":"1.flac"}]}"""
        val rest = SendMemory.Rest(full = 1, noRoom = 0L)
        memory().startSending(sent(1), rest)

        coordinator.resume()
        assertThat(awaitState { it is SendToComputerState.Waiting }).isEqualTo(SendToComputerState.Waiting(1, rest))
        folder.take("1.flac")
        folder.take(SongSender.LIST_NAME)
        assertThat(awaitState { it is SendToComputerState.Finished }).isEqualTo(SendToComputerState.Finished(1, 1, rest = rest))
    }

    @Test fun `a song that can't be copied is told apart from the ones left for room`() {
        // Song 2's file is gone from the phone: no room would carry it.
        File(tracks[1].filePath!!).delete()
        runBlocking { coordinator.copy { _, _ -> } }
        val rest = SendMemory.Rest(failed = 1)
        assertThat(coordinator.state.value).isEqualTo(SendToComputerState.Waiting(1, rest))
        folder.take("1.flac")
        folder.take(SongSender.LIST_NAME)
        assertThat(awaitState { it is SendToComputerState.Finished }).isEqualTo(SendToComputerState.Finished(1, 1, rest = rest))
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

    @Test fun `a look for the list that isn't answered is no news, and the send waiting stays as it is`() {
        folder.put("1.flac", 10)
        folder.put("2.flac", 10)
        folder.list = listOfTwo
        memory().startSending(sent(1, 2))
        folder.listUnanswered.set(1)

        coordinator.resume()
        awaitTrue { folder.listUnanswered.get() == 0 }
        Thread.sleep(500)
        // Not taken for a send the computer finished: nothing let go, nothing removed, nothing shown.
        assertThat(coordinator.state.value).isEqualTo(SendToComputerState.Idle)
        assertThat(folder.names()).containsExactly("1.flac", "2.flac", SongSender.LIST_NAME)
        assertThat(memory().sending().keys).containsExactly("1.flac", "2.flac")

        // The next look, answered, shows the send waiting.
        coordinator.resume()
        assertThat(awaitState { it is SendToComputerState.Waiting }).isEqualTo(SendToComputerState.Waiting(2))
    }

    @Test fun `the copies the computer left are cleared in a way that outlives the app`() {
        runBlocking { coordinator.copy { _, _ -> } }
        // As the copies go: the clear-up's kind is marked, and that it has begun removing, each as an empty file (a phone
        // too full to write any data can still make one).
        val marks = AtomicReference<List<Long?>>()
        val shown = AtomicReference<SendToComputerState>()
        folder.onClear = {
            marks.set(listOf(left, cleared, clearing).map { f -> f.takeIf { it.isFile }?.length() })
            shown.set(coordinator.state.value)
        }
        val seen = recordStates()
        // The computer took song 1 and removed the list: song 2 was not chosen there.
        folder.take("1.flac")
        folder.take(SongSender.LIST_NAME)
        assertThat(awaitState { it is SendToComputerState.Finished }).isEqualTo(SendToComputerState.Finished(1, 2, chosen = true))
        awaitTrue { folder.names().isEmpty() && !marked() }
        assertThat(marks.get()).containsExactly(0L, 0L, null).inOrder()
        assertThat(memory().onComputer()).containsExactly(1L)
        assertThat(memory().sending()).isEmpty()
        // The computer finished: nothing was stopped, and the end is shown as the copies go.
        assertThat(shown.get()).isEqualTo(SendToComputerState.Finished(1, 2, chosen = true))
        assertThat(seen.none { it is SendToComputerState.Cancelling }).isTrue()
    }

    @Test fun `a send the computer finished while the app was away is cleared in a way that outlives the app`() {
        // The computer took song 1 and removed the list while the app was closed.
        folder.put("2.flac", 10)
        memory().startSending(sent(1, 2))
        val wasMarked = AtomicBoolean()
        val shown = AtomicReference<SendToComputerState>()
        folder.onClear = {
            wasMarked.set(left.exists() && cleared.exists())
            shown.set(coordinator.state.value)
        }
        val seen = recordStates()

        coordinator.resume()
        awaitTrue { wasMarked.get() && folder.names().isEmpty() && !marked() }
        assertThat(memory().onComputer()).containsExactly(1L)
        assertThat(memory().sending()).isEmpty()
        assertThat(coordinator.state.value).isEqualTo(SendToComputerState.Idle)
        // Nothing was stopped: nothing is shown as the copies go.
        assertThat(shown.get()).isEqualTo(SendToComputerState.Idle)
        assertThat(seen.none { it is SendToComputerState.Cancelling }).isTrue()
    }

    @Test fun `a clear-up of the copies the computer left, cut short, is finished with nothing shown`() {
        // The computer took song 1 and removed the list; the app was killed before song 2's copy went.
        folder.put("2.flac", 10)
        memory().startSending(sent(1, 2))
        left.createNewFile()
        val seen = recordStates()
        val shown = AtomicReference<SendToComputerState>()
        folder.onClear = { shown.set(coordinator.state.value) }

        coordinator.resume()
        awaitTrue { folder.names().isEmpty() && !marked() }
        Thread.sleep(200)
        // Not "Stopping…": nothing was stopped.
        assertThat(shown.get()).isEqualTo(SendToComputerState.Idle)
        assertThat(seen.all { it is SendToComputerState.Idle }).isTrue()
        assertThat(memory().onComputer()).containsExactly(1L)
        assertThat(memory().sending()).isEmpty()
    }

    @Test fun `a send tapped while the copies the computer left are cleared copies once they are gone`() {
        // The computer took song 1 and removed the list while the app was closed; song 2's copy is left.
        folder.put("2.flac", 10)
        memory().startSending(sent(1, 2))
        val clearingNow = CountDownLatch(1)
        val goOn = CountDownLatch(1)
        folder.onClear = {
            clearingNow.countDown()
            goOn.await(10, TimeUnit.SECONDS)
        }
        coordinator.resume()
        assertThat(clearingNow.await(10, TimeUnit.SECONDS)).isTrue()

        // Send is tapped during the clear-up: the copy waits for it.
        val send = scope.launch { coordinator.copy { _, _ -> } }
        Thread.sleep(500)
        assertThat(send.isActive).isTrue()
        assertThat(coordinator.state.value).isEqualTo(SendToComputerState.Idle)

        goOn.countDown()
        runBlocking { withTimeout(10_000L) { send.join() } }
        // The new send is whole: its copy, its list, and which song each file is, none of it touched by the clear-up.
        assertThat(coordinator.state.value).isEqualTo(SendToComputerState.Waiting(1))
        assertThat(folder.names()).containsExactly("2.flac", SongSender.LIST_NAME)
        assertThat(memory().sending().keys).containsExactly("2.flac")
        assertThat(memory().onComputer()).containsExactly(1L)
        assertThat(marked()).isFalse()
    }

    @Test fun `a send tapped while the copies the computer did not choose are cleared copies once they are gone`() {
        runBlocking { coordinator.copy { _, _ -> } }
        val clearingNow = CountDownLatch(1)
        val goOn = CountDownLatch(1)
        folder.onClear = {
            clearingNow.countDown()
            goOn.await(10, TimeUnit.SECONDS)
        }
        // The computer took song 1 and removed the list: song 2 was not chosen there. The end is shown as its copy goes.
        folder.take("1.flac")
        folder.take(SongSender.LIST_NAME)
        assertThat(awaitState { it is SendToComputerState.Finished }).isEqualTo(SendToComputerState.Finished(1, 2, chosen = true))
        assertThat(clearingNow.await(10, TimeUnit.SECONDS)).isTrue()

        // Dismissed, and Send tapped during the clear-up: the copy waits for it.
        coordinator.dismiss()
        val send = scope.launch { coordinator.copy { _, _ -> } }
        Thread.sleep(500)
        assertThat(send.isActive).isTrue()
        assertThat(coordinator.state.value).isEqualTo(SendToComputerState.Idle)

        goOn.countDown()
        runBlocking { withTimeout(10_000L) { send.join() } }
        assertThat(coordinator.state.value).isEqualTo(SendToComputerState.Waiting(1))
        assertThat(folder.names()).containsExactly("2.flac", SongSender.LIST_NAME)
        assertThat(memory().sending().keys).containsExactly("2.flac")
        assertThat(memory().onComputer()).containsExactly(1L)
        assertThat(marked()).isFalse()
    }

    @Test fun `a clear-up cut short before it removed anything notes what the computer took`() {
        // Cancel was tapped and the app killed at once; the computer had taken song 1.
        folder.put("2.flac", 10)
        folder.list = listOfTwo
        memory().startSending(sent(1, 2))
        clearing.createNewFile()

        coordinator.resume()
        awaitTrue { !clearing.exists() && coordinator.state.value is SendToComputerState.Idle }
        assertThat(folder.names()).isEmpty()
        assertThat(memory().onComputer()).containsExactly(1L)
        assertThat(memory().sending()).isEmpty()
    }

    @Test fun `a clear-up cut short once it was removing notes nothing it removed itself`() {
        // The clear had removed song 1's copy when the app was killed, before the send was let go.
        folder.put("2.flac", 10)
        memory().startSending(sent(1, 2))
        clearing.createNewFile()
        cleared.createNewFile()

        coordinator.resume()
        awaitTrue { !marked() && coordinator.state.value is SendToComputerState.Idle }
        assertThat(folder.names()).isEmpty()
        assertThat(memory().onComputer()).isEmpty()
        assertThat(memory().sending()).isEmpty()
    }

    @Test fun `a phone too full to let the send go is cleared anyway, and let go once there is room`() {
        runBlocking { coordinator.copy { _, _ -> } }
        // The memory can't be written, as on a full phone.
        val blocked = File(context.filesDir, "send_to_computer.json.new").apply { mkdirs() }

        coordinator.cancel()
        awaitState { it is SendToComputerState.Idle }
        // The copies went, which frees room; the marks stay, saying the clear-up removed them, as the send isn't let go.
        assertThat(folder.names()).isEmpty()
        assertThat(clearing.exists()).isTrue()
        assertThat(cleared.exists()).isTrue()
        assertThat(memory().sending().keys).containsExactly("1.flac", "2.flac")

        // With room, the next look lets it go, and the copies the clear-up removed are not taken.
        blocked.delete()
        coordinator.resume()
        awaitTrue { !marked() && coordinator.state.value is SendToComputerState.Idle }
        assertThat(memory().sending()).isEmpty()
        assertThat(memory().onComputer()).isEmpty()
    }

    @Test fun `a new send after a clear-up cut short while removing notes nothing it removed`() {
        memory().startSending(sent(1, 2))
        left.createNewFile()
        cleared.createNewFile()
        runBlocking { coordinator.copy { _, _ -> } }
        assertThat(marked()).isFalse()
        assertThat(memory().onComputer()).isEmpty()
        assertThat(coordinator.state.value).isEqualTo(SendToComputerState.Waiting(2))
    }

    @Test fun `a copy starting during a cancel's clear-up waits for it, and leaves the cancel's mark`() {
        runBlocking { coordinator.copy { _, _ -> } }
        val clearingNow = CountDownLatch(1)
        val goOn = CountDownLatch(1)
        folder.onClear = {
            clearingNow.countDown()
            goOn.await(10, TimeUnit.SECONDS)
        }
        coordinator.cancel()
        assertThat(clearingNow.await(10, TimeUnit.SECONDS)).isTrue()

        // The work starting now, as it can when cancelled as it starts: it waits for the clear-up, and leaves the state
        // and the mark to the cancel.
        val work = scope.launch { coordinator.copy { _, _ -> } }
        Thread.sleep(300)
        assertThat(work.isActive).isTrue()
        assertThat(clearing.exists()).isTrue()
        assertThat(coordinator.state.value).isEqualTo(SendToComputerState.Cancelling)
        // WorkManager stops it, as the work was cancelled.
        work.cancel()

        goOn.countDown()
        awaitState { it is SendToComputerState.Idle }
        runBlocking { withTimeout(10_000L) { work.join() } }
        assertThat(marked()).isFalse()
        assertThat(folder.names()).isEmpty()
    }

    @Test fun `the notification's Cancel marks the clear-up before it waits for the copy`() {
        val job: Job = scope.launch(start = CoroutineStart.LAZY) { coordinator.copy { _, _ -> } }
        folder.onList = { job.cancel() }
        job.start()
        runBlocking { job.join() }
        // The folder held (as by a copy still returning): the clear-up waits.
        runBlocking { coordinator.folderBusy.lock() }
        try {
            // The work reads as cancelled: marked then, before the wait, so the app killed now still has it finished.
            awaitTrue { clearing.exists() }
            assertThat(coordinator.state.value).isEqualTo(SendToComputerState.Cancelling)
            Thread.sleep(300)
            assertThat(folder.names()).containsExactly("1.flac", "2.flac", SongSender.LIST_NAME)
        } finally {
            coordinator.folderBusy.unlock()
        }
        awaitState { it is SendToComputerState.Idle }
        assertThat(folder.names()).isEmpty()
        assertThat(marked()).isFalse()
    }

    /** The state start() leaves as it hands the send to WorkManager (the work itself is not run here). */
    private fun preparing() {
        val field = SendToComputerCoordinator::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(coordinator) as MutableStateFlow<SendToComputerState>).value = SendToComputerState.Preparing
    }

    @Test fun `the notification's Cancel while a send waits behind a clear-up puts the card right`() {
        // A clear-up holds the folder (the copies the computer left being removed) as Send is tapped.
        runBlocking { coordinator.folderBusy.lock() }
        try {
            preparing()
            val send = scope.launch { coordinator.copy { _, _ -> } }
            Thread.sleep(300)
            assertThat(send.isActive).isTrue()
            // The notification's Cancel, as the copy waits: not left saying the send is starting with nothing running.
            send.cancel()
            awaitState { it is SendToComputerState.Cancelling }
            awaitTrue { clearing.exists() }
        } finally {
            coordinator.folderBusy.unlock()
        }
        awaitState { it is SendToComputerState.Idle }
        assertThat(marked()).isFalse()
    }

    @Test fun `a look under way as Cancel is tapped does not show the computer taking`() {
        runBlocking { coordinator.copy { _, _ -> } }
        val looking = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val held = AtomicBoolean()
        folder.onFiles = {
            if (held.compareAndSet(false, true)) {
                looking.countDown()
                answer.await(10, TimeUnit.SECONDS)
            }
        }
        // The computer takes song 1: the look held now would show it taking.
        folder.take("1.flac")
        assertThat(looking.await(10, TimeUnit.SECONDS)).isTrue()
        val clearingNow = CountDownLatch(1)
        val goOn = CountDownLatch(1)
        folder.onClear = {
            clearingNow.countDown()
            goOn.await(10, TimeUnit.SECONDS)
        }
        coordinator.cancel()
        assertThat(clearingNow.await(10, TimeUnit.SECONDS)).isTrue()

        // The look answers while the copies are being removed.
        answer.countDown()
        Thread.sleep(500)
        assertThat(coordinator.state.value).isEqualTo(SendToComputerState.Cancelling)

        goOn.countDown()
        awaitState { it is SendToComputerState.Idle }
        Thread.sleep(300)
        assertThat(coordinator.state.value).isEqualTo(SendToComputerState.Idle)
    }

    @Test fun `a look that answers after Cancel notes nothing of what the clear-up removed`() {
        runBlocking { coordinator.copy { _, _ -> } }
        val looking = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val held = AtomicBoolean()
        folder.onFiles = {
            if (held.compareAndSet(false, true)) {
                looking.countDown()
                answer.await(10, TimeUnit.SECONDS)
            }
        }
        assertThat(looking.await(10, TimeUnit.SECONDS)).isTrue()
        // The phone is full: the send can't be let go, so it is still known which song each copy was.
        val blocked = File(context.filesDir, "send_to_computer.json.new").apply { mkdirs() }
        coordinator.cancel()
        awaitState { it is SendToComputerState.Idle }
        assertThat(folder.names()).isEmpty()

        // The clear freed room; then the look under way answers, with the copies the clear-up removed gone.
        blocked.delete()
        answer.countDown()
        Thread.sleep(500)
        assertThat(memory().onComputer()).isEmpty()
    }

    @Test fun `a look whose Trash doesn't answer is noted at the next, so a copy put in the Trash is never taken`() {
        runBlocking { coordinator.copy { _, _ -> } }
        // Song 2's copy goes to the Trash on the phone, and the Trash doesn't answer the look that finds it gone.
        folder.trashUnanswered.set(1)
        folder.trash("2.flac")
        awaitTrue { "2.flac" !in memory().sending() }
        assertThat(folder.trashUnanswered.get()).isEqualTo(0)

        // The Trash emptied, then the computer takes song 1: song 2 was not taken.
        folder.emptyTrash()
        folder.take("1.flac")
        folder.take(SongSender.LIST_NAME)
        awaitState { it is SendToComputerState.Finished }
        assertThat(memory().onComputer()).containsExactly(1L)
    }

    @Test fun `a folder that doesn't answer stops the send with a plain message`() {
        folder.unanswered.set(1)
        runBlocking { coordinator.copy { _, _ -> } }
        assertThat((coordinator.state.value as SendToComputerState.Error).noAnswer).isTrue()
    }

    @Test fun `a computer with every song is told so even when the old copies can't be removed`() {
        val m = memory()
        m.startSending(sent(1, 2))
        m.taken(present = emptySet(), trashed = emptySet())
        m.doneSending()
        folder.onClear = { throw NoAnswerException() }

        runBlocking { coordinator.copy { _, _ -> } }
        assertThat(coordinator.state.value).isEqualTo(SendToComputerState.Error("Your computer has all your downloaded songs already."))
    }
}
