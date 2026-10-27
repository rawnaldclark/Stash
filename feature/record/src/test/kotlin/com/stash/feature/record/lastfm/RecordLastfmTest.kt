package com.stash.feature.record.lastfm

import androidx.work.ExistingWorkPolicy
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.stash.core.data.lastfm.LastFmRateLimitedException
import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.archive.RecordFlags
import com.stash.feature.record.imports.RecordWrites
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test

/** Your Last.fm scrobbles coming in: the service's runs, as the sheet and Settings see them. */
class RecordLastfmTest {
    private val nowS = 1_800_000_000L
    private val t0 = 1_714_564_800L

    /** Last.fm with [all] (title, uts), newest first, 200 a page, answering as the app's client does; [gate] holds a page. */
    private class Remote(var all: List<Pair<String, Long>>) : LastfmRemote {
        val asked = mutableListOf<Int>()
        var fail: (() -> Nothing)? = null
        val gate = HashMap<Int, CompletableDeferred<Unit>>()

        override suspend fun page(user: String, from: Long?, to: Long, page: Int): JsonObject {
            asked += page
            gate[page]?.await()
            fail?.invoke()
            val window = all.filter { it.second <= to && (from == null || it.second >= from) }.sortedByDescending { it.second }
            val pages = (window.size + 199) / 200
            val tracks = window.drop((page - 1) * 200).take(200).joinToString(",") { (t, uts) ->
                """{"name":"$t","artist":{"#text":"Band"},"date":{"uts":"$uts"}}"""
            }
            return Json.parseToJsonElement("""{"recenttracks":{"track":[$tracks],"@attr":{"page":"$page","totalPages":"$pages","total":"${window.size}"}}}""").jsonObject
        }

        override suspend fun signedPage(sessionKey: String, user: String, from: Long?, to: Long, page: Int): JsonObject = page(user, from, to, page)
    }

    private inner class Harness(val test: TestScope, songs: Int, accountFlow: Flow<LastfmAccount?>? = null) {
        val remote = Remote((0 until songs).map { "Song $it" to t0 + it * 60L })
        val store = MemoryScrobbleStore()
        val account = MutableStateFlow<LastfmAccount?>(LastfmAccount("me", "sk"))
        val writes = RecordWrites()
        var spotify = false
        var records = 0
        var removedRecords = 0
        var changes = 0
        val launched = mutableListOf<LastfmAsk>()
        var cancels = 0
        var nowMs = nowS * 1000
        val generation = MutableStateFlow(0L)
        /** The lengths pass as the service drives it: stopped before a run or a removal, started after a run, stopped by Stop. */
        val lengths = mutableListOf<String>()
        /** The store a run or a removal gets (a test can hand over one that waits). */
        var storeFor: () -> ScrobbleStore = { store }
        val lastfm = RecordLastfm(
            newStore = { storeFor() },
            markNow = { store.mark },
            markFlow = flow { emit(store.mark) },
            accountFlow = accountFlow ?: account,
            configured = true,
            remote = remote,
            writes = writes,
            spotifyBusy = { spotify },
            recordAfter = { records++ },
            recordAfterRemoved = { removedRecords++ },
            changed = { changes++ },
            launch = { launched += it },
            cancelJob = { cancels++ },
            scope = test.backgroundScope,
            generation = { generation.value },
            nextGeneration = { generation.value++ },
            generations = generation,
            io = kotlinx.coroutines.test.StandardTestDispatcher(test.testScheduler),
            now = { nowMs },
            gapMs = 0,
            busyMs = 0,
            pauseLengths = { lengths += "pause"; false },
            startLengths = { lengths += "start" },
            stopLengths = { lengths += "stop" },
        )

        init {
            // The connected account read (the screens show Bring them in only then)
            test.runCurrent()
        }

        /** The job runs: what was asked of it (a job run again by WorkManager brings its own [quiet] and the account then). */
        suspend fun run(quiet: Boolean, onProgress: (LastfmProgress?) -> Unit = {}) =
            lastfm.run(LastfmAsk(quiet, account.value?.name ?: "me", generation.value), onProgress)
    }

    @Test fun `a run reads every page, shows how far it is, then what came in, and makes the import record`() = runTest {
        val h = Harness(this, songs = 450)
        h.lastfm.bringIn()
        assertThat(h.lastfm.state.value).isEqualTo(LastfmState.Working(null))
        assertThat(h.launched.map { it.quiet }).containsExactly(false)
        // What the screens show as each page lands (the notification hears the same)
        val shown = mutableListOf<LastfmState>()
        h.run(false) { shown += h.lastfm.state.value }
        // A whole read: the whole import, all of it this time
        assertThat(h.lastfm.state.value).isEqualTo(LastfmState.Done(450, total = 450))
        assertThat(shown).containsExactly(
            LastfmState.Working(LastfmProgress(1, 3, 200)),
            LastfmState.Working(LastfmProgress(2, 3, 400)),
            LastfmState.Working(LastfmProgress(3, 3, 450)),
        ).inOrder()
        assertThat(h.store.mark).isEqualTo(LastfmMark("me", nowS, 4, 3, true, t0 + 449 * 60, 450, 0, nowS))
        assertThat(h.records).isEqualTo(1)
        assertThat(h.changes).isEqualTo(1)
        assertThat(h.writes.lastfmRunning.value).isFalse()
    }

    @Test fun `a run stopped part way says nothing, keeps its mark, and carries on from the next page`() = runTest {
        val h = Harness(this, songs = 450)
        h.remote.gate[2] = CompletableDeferred()
        h.lastfm.bringIn()
        val job = launch { h.run(false) }
        runCurrent()
        assertThat(h.writes.lastfmRunning.value).isTrue()
        job.cancel()
        advanceUntilIdle()
        assertThat(h.lastfm.state.value).isEqualTo(LastfmState.Idle)
        assertThat(h.store.mark!!.next).isEqualTo(2)
        assertThat(h.store.rows).hasSize(200)
        assertThat(h.changes).isEqualTo(1)
        // Carry on: page 2 first, nothing counted twice
        h.remote.gate.clear()
        h.remote.asked.clear()
        h.lastfm.bringIn()
        h.run(false)
        assertThat(h.remote.asked).containsExactly(2, 3).inOrder()
        // Carried on: the whole import, and this run's part of it
        assertThat(h.lastfm.state.value).isEqualTo(LastfmState.Done(250, total = 450))
        assertThat(h.store.rows).hasSize(450)
    }

    @Test fun `the person's Stop before the job took the run up leaves it idle at once`() = runTest {
        val h = Harness(this, songs = 3)
        h.lastfm.bringIn()
        h.lastfm.stop()
        assertThat(h.cancels).isEqualTo(1)
        assertThat(h.lastfm.state.value).isEqualTo(LastfmState.Idle)
        // and a new one can be asked for
        h.lastfm.bringIn()
        assertThat(h.launched.map { it.quiet }).containsExactly(false, false).inOrder()
    }

    @Test fun `a top-up runs quietly, at most hourly, only once all is in and for the same account`() = runTest {
        val h = Harness(this, songs = 10)
        h.lastfm.bringIn()
        h.run(false)
        h.launched.clear()
        // within the hour: nothing
        h.nowMs += 59 * 60_000L
        h.lastfm.topUp()
        runCurrent()
        assertThat(h.launched).isEmpty()
        // another account connected: nothing (its own import starts from Bring them in)
        h.nowMs += 2 * 60_000L
        h.account.value = LastfmAccount("someone else", "sk2")
        h.lastfm.topUp()
        runCurrent()
        assertThat(h.launched).isEmpty()
        // the same account, past the hour: a quiet run, reading only what is newer
        h.account.value = LastfmAccount("me", "sk")
        h.remote.all = h.remote.all + ("Fresh" to t0 + 100_000)
        h.lastfm.topUp()
        runCurrent()
        assertThat(h.launched.map { it.quiet }).containsExactly(true)
        h.remote.asked.clear()
        h.run(true)
        assertThat(h.lastfm.state.value).isEqualTo(LastfmState.Idle)
        assertThat(h.store.rows).hasSize(11)
        assertThat(h.store.mark!!.to).isEqualTo(h.nowMs / 1000)
    }

    @Test fun `a top-up asked to show itself shows its progress and what it brought`() = runTest {
        val h = Harness(this, songs = 10)
        h.lastfm.bringIn()
        h.run(false)
        h.nowMs += 2 * 3_600_000L
        h.lastfm.topUp()
        runCurrent()
        h.lastfm.bringIn() // the person taps Bring in newer while the quiet run is under way
        assertThat(h.lastfm.quiet).isFalse()
        assertThat(h.launched.last().quiet).isTrue() // no second job: the quiet one shows itself
        h.run(true)
        assertThat(h.lastfm.state.value).isEqualTo(LastfmState.Done(0))
    }

    @Test fun `another account starts over`() = runTest {
        val h = Harness(this, songs = 5)
        h.lastfm.bringIn()
        h.run(false)
        h.account.value = LastfmAccount("someone else", null)
        runCurrent()
        h.remote.asked.clear()
        h.lastfm.bringIn()
        h.run(false)
        assertThat(h.store.mark!!.user).isEqualTo("someone else")
        assertThat(h.remote.asked).containsExactly(1)
        // the scrobbles came in already (same words, same times): kept once, the first account's the base, and the count this
        // account's own reads (lastfm.ts: each scrobble of a whole read the archive holds)
        assertThat(h.lastfm.state.value).isEqualTo(LastfmState.Done(5, total = 5))
        assertThat(h.store.mark!!.base).isEqualTo(5)
        assertThat(h.store.rows).hasSize(5)
    }

    @Test fun `busy six times stops short and says so, a quiet run says nothing`() = runTest {
        val h = Harness(this, songs = 5)
        h.remote.fail = { throw LastFmRateLimitedException("HTTP 429") }
        h.lastfm.bringIn()
        h.run(false)
        assertThat(h.remote.asked).hasSize(ImportScrobbles.BUSY_TRIES)
        assertThat(h.lastfm.state.value).isEqualTo(LastfmState.Failed(LastfmFailure.BUSY))
        assertThat(h.records).isEqualTo(0)
        h.run(true)
        assertThat(h.lastfm.state.value).isEqualTo(LastfmState.Idle)
    }

    @Test fun `no account connected, or one that went, is a reconnect`() = runTest {
        val h = Harness(this, songs = 5)
        h.account.value = null
        h.run(false)
        assertThat(h.lastfm.state.value).isEqualTo(LastfmState.Failed(LastfmFailure.RECONNECT))
        assertThat(h.remote.asked).isEmpty()
    }

    @Test fun `nothing starts while the Spotify import works, and a Spotify commit waits for a run`() = runTest {
        val h = Harness(this, songs = 450)
        h.spotify = true
        h.lastfm.bringIn()
        assertThat(h.launched).isEmpty()
        assertThat(h.lastfm.remove()).isFalse()
        h.spotify = false
        h.remote.gate[2] = CompletableDeferred()
        h.lastfm.bringIn()
        val run = launch { h.run(false) }
        runCurrent()
        var committed = false
        val commit = launch { h.writes.mutex.withLock { committed = true } }
        runCurrent()
        assertThat(committed).isFalse()
        assertThat(h.lastfm.remove()).isFalse()
        h.remote.gate.getValue(2).complete(Unit)
        run.join()
        commit.join()
        assertThat(committed).isTrue()
    }

    @Test fun `remove takes every scrobble and the mark, keeps the rest, and the record is counted again`() = runTest {
        val h = Harness(this, songs = 5)
        h.store.rows["kept|band|1"] = ArchivePlay("kept|band|1", "Kept", "Band", s = 1, ms = 200_000, f = RecordFlags.REASONS)
        h.lastfm.bringIn()
        h.run(false)
        assertThat(h.store.rows).hasSize(6)
        val changes = h.changes
        assertThat(h.lastfm.remove()).isTrue()
        assertThat(h.store.rows.keys).containsExactly("kept|band|1")
        assertThat(h.store.mark).isNull()
        assertThat(h.removedRecords).isEqualTo(1)
        assertThat(h.changes).isEqualTo(changes + 1)
        assertThat(h.lastfm.state.value).isEqualTo(LastfmState.Idle)
    }

    // ── the fix round (10-27 review) ────────────────────────────────────────────────────────────────────────────────────

    @Test fun `Remove after the system stopped a run takes the job too, and the job run again brings nothing back`() = runTest {
        val h = Harness(this, songs = 450)
        h.remote.gate[2] = CompletableDeferred()
        h.lastfm.bringIn()
        val asked = h.launched.last()
        val job = launch { h.lastfm.run(asked) }
        runCurrent()
        job.cancel() // the system stops the job: it will run again by itself
        advanceUntilIdle()
        assertThat(h.store.rows).hasSize(200)
        assertThat(h.lastfm.remove()).isTrue()
        assertThat(h.cancels).isEqualTo(1)
        assertThat(h.store.rows).isEmpty()
        assertThat(h.store.mark).isNull()
        // WorkManager runs the stopped job again, with what it was asked
        h.remote.gate.clear()
        h.remote.asked.clear()
        h.lastfm.run(asked)
        assertThat(h.remote.asked).isEmpty()
        assertThat(h.store.rows).isEmpty()
        assertThat(h.store.mark).isNull()
        assertThat(h.lastfm.state.value).isEqualTo(LastfmState.Idle)
    }

    @Test fun `Remove while a run waits to be taken up cancels it and leaves nothing waiting`() = runTest {
        val h = Harness(this, songs = 5)
        h.lastfm.bringIn()
        h.lastfm.run(h.launched.last())
        h.nowMs += 2 * 3_600_000L
        h.lastfm.bringIn() // Bring in newer: queued, not taken up yet
        assertThat(h.lastfm.remove()).isTrue()
        assertThat(h.cancels).isEqualTo(1)
        assertThat(h.lastfm.state.value).isEqualTo(LastfmState.Idle)
        // and the next Bring them in is a new run
        h.lastfm.bringIn()
        assertThat(h.launched).hasSize(3)
    }

    @Test fun `a removal goes to its end when the screen that asked for it goes, the mark first`() = runTest {
        val h = Harness(this, songs = 5)
        h.lastfm.bringIn()
        h.run(false)
        val order = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        h.storeFor = {
            object : ScrobbleStore by h.store {
                override suspend fun clearMark() {
                    order += "mark"
                    gate.await()
                    h.store.clearMark()
                }

                override suspend fun deleteScrobbles(): Int {
                    order += "rows"
                    return h.store.deleteScrobbles()
                }
            }
        }
        val asking = launch { h.lastfm.remove() }
        runCurrent()
        asking.cancel() // Settings closed mid-way
        gate.complete(Unit)
        // the removal runs in the app's scope (the test's background): run what is due
        runCurrent()
        assertThat(order).containsExactly("mark", "rows").inOrder()
        assertThat(h.store.mark).isNull()
        assertThat(h.store.rows).isEmpty()
    }

    @Test fun `an account that can't be read ends the run, and the next Bring them in starts one`() = runTest {
        var reads = 0
        val h = Harness(this, songs = 5, accountFlow = flow { if (reads++ == 0) emit(LastfmAccount("me", "sk")) else throw java.io.IOException("datastore") })
        runCurrent()
        h.lastfm.bringIn()
        h.lastfm.run(h.launched.last())
        assertThat(h.lastfm.state.value).isEqualTo(LastfmState.Failed(LastfmFailure.OTHER))
        h.lastfm.bringIn()
        assertThat(h.launched).hasSize(2)
    }

    @Test fun `what a run brought is counted from the mark as it was when the run took the archive`() = runTest {
        val h = Harness(this, songs = 450)
        h.lastfm.bringIn()
        h.run(false)
        // Another writer holds the archive and clears the mark while the next run waits for it
        val held = CompletableDeferred<Unit>()
        val writer = launch { h.writes.mutex.withLock { h.store.mark = null; held.await() } }
        runCurrent()
        h.nowMs += 2 * 3_600_000L
        h.lastfm.bringIn()
        val run = launch { h.lastfm.run(h.launched.last()) }
        runCurrent()
        held.complete(Unit)
        writer.join()
        run.join()
        // The whole history read again from page 1, every scrobble kept already, never a negative count (the mark read before
        // the run took the archive said 450, which made it -450). With no mark the rows held are another account's for all it
        // knows (startMark: the base), so the count is this read's own: all 450
        assertThat(h.lastfm.state.value).isEqualTo(LastfmState.Done(450, total = 450))
        assertThat(h.store.rows).hasSize(450)
    }

    @Test fun `a run asked for one account does nothing for another connected since`() = runTest {
        val h = Harness(this, songs = 5)
        h.lastfm.bringIn()
        val asked = h.launched.last()
        h.account.value = LastfmAccount("someone else", "sk2")
        runCurrent()
        h.lastfm.run(asked)
        assertThat(h.remote.asked).isEmpty()
        assertThat(h.store.rows).isEmpty()
        assertThat(h.lastfm.state.value).isEqualTo(LastfmState.Idle)
        // Asked again for the account connected now: it runs, for that account
        h.lastfm.bringIn()
        h.lastfm.run(h.launched.last())
        assertThat(h.store.mark!!.user).isEqualTo("someone else")
    }

    @Test fun `the person asking again before the job runs asks for the account connected now`() = runTest {
        val h = Harness(this, songs = 5)
        h.lastfm.bringIn()
        val first = h.launched.single()
        h.account.value = LastfmAccount("someone else", "sk2")
        runCurrent()
        h.lastfm.bringIn() // still queued: it takes this account
        h.lastfm.run(first)
        assertThat(h.store.mark!!.user).isEqualTo("someone else")
    }

    @Test fun `a run asked for while one finishes waits behind it, never dropped`() {
        // KEEP gave a run asked for after the last one's end, before its job returned, to the finishing job, which never ran it
        assertThat(RecordLastfmWorker.POLICY).isEqualTo(ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    // ── the lengths pass around a run ────────────────────────────────────────────────────────────────────────────────

    @Test fun `the lengths pass lets go of the lock for a run, and times what came in after it`() = runTest {
        val h = Harness(this, songs = 450)
        h.lastfm.bringIn()
        h.run(false)
        assertThat(h.lengths).containsExactly("pause", "start").inOrder()
        // A quiet top-up too, even one that finds nothing
        h.lengths.clear()
        h.nowMs += 2 * 3_600_000L
        h.lastfm.topUp()
        runCurrent()
        h.run(true)
        assertThat(h.lengths).containsExactly("pause", "start").inOrder()
    }

    @Test fun `stopped short the pass still times what came in, after a reconnect too, not after a Stop or a run that did nothing`() = runTest {
        val busy = Harness(this, songs = 450)
        busy.remote.fail = { throw LastFmRateLimitedException("HTTP 429") }
        busy.lastfm.bringIn()
        busy.run(false)
        assertThat(busy.lastfm.state.value).isEqualTo(LastfmState.Failed(LastfmFailure.BUSY))
        assertThat(busy.lengths).containsExactly("pause", "start").inOrder()

        // The account gone under the run: a reconnect
        val gone = Harness(this, songs = 450)
        gone.remote.gate[2] = CompletableDeferred()
        gone.lastfm.bringIn()
        val run = launch { gone.run(false) }
        runCurrent()
        gone.account.value = null
        gone.remote.gate[2]!!.complete(Unit)
        run.join()
        assertThat(gone.lastfm.state.value).isEqualTo(LastfmState.Failed(LastfmFailure.RECONNECT))
        // Its lookups need no session: what came in before is timed all the same
        assertThat(gone.lengths).containsExactly("pause", "start").inOrder()

        // The person's Stop: the pass stops too, and doesn't start after
        val stopped = Harness(this, songs = 450)
        stopped.remote.gate[2] = CompletableDeferred()
        stopped.lastfm.bringIn()
        val job = launch { stopped.run(false) }
        runCurrent()
        stopped.lastfm.stop()
        job.cancel()
        advanceUntilIdle()
        assertThat(stopped.lengths).containsExactly("pause", "stop").inOrder()

        // Asked for another account than the one connected now: nothing read, nothing paused or started
        val other = Harness(this, songs = 5)
        other.lastfm.run(LastfmAsk(false, "someone else", 0))
        assertThat(other.lengths).isEmpty()
    }

    @Test fun `removing the scrobbles stops the pass first`() = runTest {
        val h = Harness(this, songs = 5)
        h.lastfm.bringIn()
        h.run(false)
        h.lengths.clear()
        assertThat(h.lastfm.remove()).isTrue()
        assertThat(h.lengths).containsExactly("pause")
    }

    // ── the web's service, run for run (service.json, from lastfm-vectors.ts) ───────────────────────────────────────────

    /**
     * Last.fm as service.json's runs fake it, behind the app's client: a profile that keeps its listening private (so every page
     * reads signed), the vector's scrobbles newest first, and its odd pages (busy: a rate limit; a hole: a page before the last
     * listing nothing; reconnect: error 9 on the signed read, a session Last.fm no longer takes), each only in a top-up's asks
     * (`topup`), only in the whole read's, or in both.
     */
    private class VectorRemote : LastfmRemote {
        class Odd(val page: Int, var times: Int, val kind: String, val hole: Boolean, val topup: Boolean?)

        var all: List<Scrobble> = emptyList()
        val fails = mutableListOf<Odd>()

        override suspend fun page(user: String, from: Long?, to: Long, page: Int): JsonObject =
            throw com.stash.core.data.lastfm.LastFmApiException(17, "Login required")

        override suspend fun signedPage(sessionKey: String, user: String, from: Long?, to: Long, page: Int): JsonObject {
            val window = all.filter { it.uts <= to && (from == null || it.uts >= from) }.sortedByDescending { it.uts }
            val pages = (window.size + 199) / 200
            val odd = fails.firstOrNull { it.page == page && it.times > 0 && (it.topup == null || it.topup == (from != null)) }
            odd?.let { f ->
                f.times--
                when {
                    f.hole -> {}
                    f.kind == "busy" -> throw LastFmRateLimitedException("HTTP 429")
                    f.kind == "reconnect" -> throw com.stash.core.data.lastfm.LastFmApiException(9, "Invalid session key")
                    else -> throw com.stash.core.data.lastfm.LastFmHttpException(404, "HTTP 404")
                }
            }
            // A hole: the page lists nothing, though Last.fm says there are more after it
            val shown = if (odd?.hole == true) emptyList() else window.drop((page - 1) * 200).take(200)
            val tracks = shown.joinToString(",") { s ->
                """{"name":${Json.encodeToString(String.serializer(), s.t)},"artist":{"#text":${Json.encodeToString(String.serializer(), s.a)}},"date":{"uts":"${s.uts}"}}"""
            }
            return Json.parseToJsonElement("""{"recenttracks":{"track":[$tracks],"@attr":{"page":"$page","totalPages":"$pages","total":"${window.size}"}}}""").jsonObject
        }
    }

    @Test fun `each run says what the web's service says`() = runTest {
        val cases = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/lastfm/service.json")).readText()).jsonArray
        assertThat(cases.size).isAtLeast(5)
        for (c in cases.map { it.jsonObject }) {
            val name = c["name"]!!.jsonPrimitive.content
            val remote = VectorRemote()
            val store = MemoryScrobbleStore()
            val account = MutableStateFlow<LastfmAccount?>(null)
            var nowMs = 0L
            val lastfm = RecordLastfm(
                newStore = { store }, markNow = { store.mark }, markFlow = flow { emit(store.mark) }, accountFlow = account,
                configured = true, remote = remote, writes = RecordWrites(), spotifyBusy = { false }, recordAfter = {}, recordAfterRemoved = {},
                changed = {}, launch = {}, cancelJob = {}, scope = backgroundScope, generation = { 0 }, nextGeneration = {},
                generations = MutableStateFlow(0L), io = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler), now = { nowMs },
                gapMs = 0, busyMs = 0,
            )
            for ((i, r) in c["runs"]!!.jsonArray.map { it.jsonObject }.withIndex()) {
                val at = "$name, run ${i + 1}"
                val user = r["user"]!!.jsonPrimitive.content
                account.value = LastfmAccount(user, "sk")
                runCurrent()
                nowMs = r["nowS"]!!.jsonPrimitive.long * 1000
                remote.all = r["scrobbles"]!!.jsonArray.map { it.jsonObject }.map {
                    Scrobble(it["t"]!!.jsonPrimitive.content, it["a"]!!.jsonPrimitive.content, null, it["uts"]!!.jsonPrimitive.long)
                }
                remote.fails.clear()
                r["fails"]!!.jsonArray.map { it.jsonObject }.forEach {
                    remote.fails += VectorRemote.Odd(
                        it["page"]!!.jsonPrimitive.int, it["times"]!!.jsonPrimitive.int, it["kind"]!!.jsonPrimitive.content,
                        (it["hole"] as? JsonPrimitive)?.boolean == true, (it["topup"] as? JsonPrimitive)?.boolean,
                    )
                }
                lastfm.bringIn()
                lastfm.run(LastfmAsk(false, user, 0))
                val st = r["state"]!!.jsonObject
                val want = when (st["step"]!!.jsonPrimitive.content) {
                    "done" -> LastfmState.Done(st["added"]!!.jsonPrimitive.int, (st["total"] as? JsonPrimitive)?.int, (st["missing"] as? JsonPrimitive)?.boolean == true)
                    else -> LastfmState.Failed(LastfmFailure.valueOf(st["reason"]!!.jsonPrimitive.content.uppercase()))
                }
                assertWithMessage("$at: state").that(lastfm.state.value).isEqualTo(want)
                val m = r["mark"]!!.jsonObject
                assertWithMessage("$at: added").that(store.mark!!.added).isEqualTo(m["added"]!!.jsonPrimitive.int)
                assertWithMessage("$at: base").that(store.mark!!.base).isEqualTo((m["base"] as? JsonPrimitive)?.int)
                lastfm.reset()
            }
        }
    }
}
