package com.stash.feature.record.lastfm

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.lastfm.LastFmRateLimitedException
import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.archive.RecordFlags
import com.stash.feature.record.imports.RecordWrites
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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

    private inner class Harness(val test: TestScope, songs: Int) {
        val remote = Remote((0 until songs).map { "Song $it" to t0 + it * 60L })
        val store = MemoryScrobbleStore()
        val account = MutableStateFlow<LastfmAccount?>(LastfmAccount("me", "sk"))
        val writes = RecordWrites()
        var spotify = false
        var records = 0
        var removedRecords = 0
        var changes = 0
        val launched = mutableListOf<Boolean>()
        var cancels = 0
        var nowMs = nowS * 1000
        val lastfm = RecordLastfm(
            newStore = { store },
            markNow = { store.mark },
            markFlow = flow { emit(store.mark) },
            accountFlow = account,
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
            now = { nowMs },
            gapMs = 0,
            busyMs = 0,
        )
    }

    @Test fun `a run reads every page, shows how far it is, then what came in, and makes the import record`() = runTest {
        val h = Harness(this, songs = 450)
        h.lastfm.bringIn()
        assertThat(h.lastfm.state.value).isEqualTo(LastfmState.Working(null))
        assertThat(h.launched).containsExactly(false)
        // What the screens show as each page lands (the notification hears the same)
        val shown = mutableListOf<LastfmState>()
        h.lastfm.run(quietAsked = false) { shown += h.lastfm.state.value }
        assertThat(h.lastfm.state.value).isEqualTo(LastfmState.Done(450))
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
        val job = launch { h.lastfm.run(quietAsked = false) }
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
        h.lastfm.run(quietAsked = false)
        assertThat(h.remote.asked).containsExactly(2, 3).inOrder()
        assertThat(h.lastfm.state.value).isEqualTo(LastfmState.Done(250))
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
        assertThat(h.launched).containsExactly(false, false).inOrder()
    }

    @Test fun `a top-up runs quietly, at most hourly, only once all is in and for the same account`() = runTest {
        val h = Harness(this, songs = 10)
        h.lastfm.bringIn()
        h.lastfm.run(quietAsked = false)
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
        assertThat(h.launched).containsExactly(true)
        h.remote.asked.clear()
        h.lastfm.run(quietAsked = true)
        assertThat(h.lastfm.state.value).isEqualTo(LastfmState.Idle)
        assertThat(h.store.rows).hasSize(11)
        assertThat(h.store.mark!!.to).isEqualTo(h.nowMs / 1000)
    }

    @Test fun `a top-up asked to show itself shows its progress and what it brought`() = runTest {
        val h = Harness(this, songs = 10)
        h.lastfm.bringIn()
        h.lastfm.run(quietAsked = false)
        h.nowMs += 2 * 3_600_000L
        h.lastfm.topUp()
        runCurrent()
        h.lastfm.bringIn() // the person taps Bring in newer while the quiet run is under way
        assertThat(h.lastfm.quiet).isFalse()
        assertThat(h.launched.last()).isTrue() // no second job: the quiet one shows itself
        h.lastfm.run(quietAsked = true)
        assertThat(h.lastfm.state.value).isEqualTo(LastfmState.Done(0))
    }

    @Test fun `another account starts over`() = runTest {
        val h = Harness(this, songs = 5)
        h.lastfm.bringIn()
        h.lastfm.run(quietAsked = false)
        h.account.value = LastfmAccount("someone else", null)
        h.remote.asked.clear()
        h.lastfm.bringIn()
        h.lastfm.run(quietAsked = false)
        assertThat(h.store.mark!!.user).isEqualTo("someone else")
        assertThat(h.remote.asked).containsExactly(1)
        // the scrobbles came in already (same words, same times): kept once, and the count is this account's own
        assertThat(h.lastfm.state.value).isEqualTo(LastfmState.Done(0))
    }

    @Test fun `busy six times stops short and says so, a quiet run says nothing`() = runTest {
        val h = Harness(this, songs = 5)
        h.remote.fail = { throw LastFmRateLimitedException("HTTP 429") }
        h.lastfm.bringIn()
        h.lastfm.run(quietAsked = false)
        assertThat(h.remote.asked).hasSize(ImportScrobbles.BUSY_TRIES)
        assertThat(h.lastfm.state.value).isEqualTo(LastfmState.Failed(LastfmFailure.BUSY))
        assertThat(h.records).isEqualTo(0)
        h.lastfm.run(quietAsked = true)
        assertThat(h.lastfm.state.value).isEqualTo(LastfmState.Idle)
    }

    @Test fun `no account connected, or one that went, is a reconnect`() = runTest {
        val h = Harness(this, songs = 5)
        h.account.value = null
        h.lastfm.run(quietAsked = false)
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
        val run = launch { h.lastfm.run(quietAsked = false) }
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
        h.lastfm.run(quietAsked = false)
        assertThat(h.store.rows).hasSize(6)
        val changes = h.changes
        assertThat(h.lastfm.remove()).isTrue()
        assertThat(h.store.rows.keys).containsExactly("kept|band|1")
        assertThat(h.store.mark).isNull()
        assertThat(h.removedRecords).isEqualTo(1)
        assertThat(h.changes).isEqualTo(changes + 1)
        assertThat(h.lastfm.state.value).isEqualTo(LastfmState.Idle)
    }
}
