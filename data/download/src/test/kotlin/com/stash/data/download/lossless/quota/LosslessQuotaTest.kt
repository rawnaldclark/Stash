package com.stash.data.download.lossless.quota

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test

class LosslessQuotaTest {

    /** 2026-10-17 12:00 UTC; the day resets at [RESET]. */
    private var now = 1_792_238_400_000L
    private val quota = LosslessQuota.inMemory { now }

    @Test fun `parseCount reads used and limit, unlimited, and rejects junk`() {
        assertThat(LosslessQuota.parseCount("412/500")).isEqualTo(412 to 500)
        assertThat(LosslessQuota.parseCount(" 3 / unlimited ")).isEqualTo(3 to null)
        assertThat(LosslessQuota.parseCount("412")).isNull()
        assertThat(LosslessQuota.parseCount("a/500")).isNull()
        assertThat(LosslessQuota.parseCount("-1/500")).isNull()
        assertThat(LosslessQuota.parseCount("4/0")).isNull()
        assertThat(LosslessQuota.parseCount(null)).isNull()
    }

    @Test fun `headers set the allowance, and the limit makes it spent until the reset`() {
        assertThat(quota.current()).isNull()
        assertThat(quota.isSpent()).isFalse()

        quota.onHeaders("499/500", "$RESET")
        assertThat(quota.current()).isEqualTo(LosslessAllowance(499, 500, RESET))
        assertThat(quota.isSpent()).isFalse()

        quota.onHeaders("500/500", "$RESET")
        assertThat(quota.isSpent()).isTrue()
        assertThat(quota.resetsAtMs()).isEqualTo(RESET * 1000)

        // Clears by itself at the reset: a fresh day with nothing used.
        now = RESET * 1000
        assertThat(quota.isSpent()).isFalse()
        assertThat(quota.current()).isEqualTo(LosslessAllowance(0, 500, RESET + DAY))
    }

    @Test fun `a missing or junk header pair changes nothing`() {
        quota.onHeaders("10/500", "$RESET")
        quota.onHeaders(null, "$RESET")
        quota.onHeaders("11/500", null)
        quota.onHeaders("11/500", "soon")
        assertThat(quota.current()?.used).isEqualTo(10)
    }

    @Test fun `unlimited is never spent`() {
        quota.onHeaders("9000/unlimited", "$RESET")
        assertThat(quota.current()?.unlimited).isTrue()
        assertThat(quota.isSpent()).isFalse()
    }

    @Test fun `answers landing out of order never move the count back, a later day replaces it`() {
        quota.onHeaders("420/500", "$RESET")
        quota.onHeaders("418/500", "$RESET")
        assertThat(quota.current()?.used).isEqualTo(420)

        quota.onHeaders("500/500", "${RESET - DAY}") // yesterday's answer
        assertThat(quota.current()?.used).isEqualTo(420)

        quota.onHeaders("3/unlimited", "$RESET") // a supporter claim lifts the limit mid-day
        assertThat(quota.current()).isEqualTo(LosslessAllowance(420, null, RESET))

        now = RESET * 1000 + 5_000
        quota.onHeaders("1/500", "${RESET + DAY}")
        assertThat(quota.current()).isEqualTo(LosslessAllowance(1, 500, RESET + DAY))
    }

    @Test fun `a daily_lossless_cap body records spent, any other 429 body does not`() {
        assertThat(quota.onCapBody("""{"error":"network_daily_cap"}""")).isFalse()
        assertThat(quota.onCapBody("<html>")).isFalse()
        assertThat(quota.current()).isNull()

        assertThat(quota.onCapBody("""{"error":"daily_lossless_cap","used":500,"limit":500,"resetsAt":$RESET}""")).isTrue()
        assertThat(quota.isSpent()).isTrue()
        assertThat(quota.current()).isEqualTo(LosslessAllowance(500, 500, RESET))
    }

    @Test fun `a cap body without resetsAt resets at the next UTC midnight`() {
        assertThat(quota.onCapBody("""{"error":"daily_lossless_cap"}""")).isTrue()
        assertThat(quota.current()).isEqualTo(LosslessAllowance(500, 500, RESET))
        assertThat(quota.isSpent()).isTrue()
        // So the relays' later answers for the same day read as the same day.
        quota.onHeaders("500/500", "$RESET")
        assertThat(quota.current()).isEqualTo(LosslessAllowance(500, 500, RESET))
    }

    @Test fun `the day is judged on the clock it is given (the relays'), not the device's`() {
        // The device runs 10 minutes fast: its clock alone would already read the day as reset.
        val deviceMs = RESET * 1000 + 5 * 60_000
        val offsetMs = -10 * 60_000L
        val q = LosslessQuota.inMemory { deviceMs + offsetMs }
        q.onHeaders("500/500", "$RESET")
        assertThat(q.isSpent()).isTrue()
        assertThat(q.nowSec()).isEqualTo(RESET - 5 * 60)
    }

    @Test fun `every spent answer is announced for booking, repeats included`() = runTest {
        val seen = mutableListOf<Long>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { quota.spentAnswers.collect { seen += it } }
        quota.onHeaders("499/500", "$RESET")
        quota.onHeaders("500/500", "$RESET")
        quota.onCapBody("""{"error":"daily_lossless_cap","used":500,"limit":500,"resetsAt":$RESET}""")
        job.cancel()
        assertThat(seen).containsExactly(RESET, RESET)
    }

    @Test fun `a supporter claim lifts the spent day until the next relay answer`() {
        quota.liftSpent() // nothing spent: nothing to lift
        quota.onHeaders("500/500", "$RESET")
        assertThat(quota.isSpent()).isTrue()

        quota.liftSpent()
        assertThat(quota.isSpent()).isFalse()
        assertThat(quota.current()).isEqualTo(LosslessAllowance(500, 500, RESET)) // the count is not touched

        // The relays still count this install as free (a revoked token): spent again.
        quota.onHeaders("500/500", "$RESET")
        assertThat(quota.isSpent()).isTrue()
    }

    @Test fun `a lifted day survives a restart`() = withFileStore { rig ->
        val first = LosslessQuota(rig.store, { now }, rig.quotaScope)
        first.awaitLoaded()
        first.onHeaders("500/500", "$RESET")
        first.liftSpent()
        rig.settle()
        assertThat(rig.storedKeys()).containsAtLeast("used", "limit", "resets_at", "noticed_day", "noticed_level", "lifted_day")

        val second = LosslessQuota(rig.store, { now }, rig.quotaScope)
        second.awaitLoaded()
        assertThat(second.isSpent()).isFalse()
    }

    @Test fun `a quota body reads numbers, the word unlimited and the supporter flag`() {
        assertThat(quota.onQuotaBody("""{"used":12,"limit":500,"resetsAt":$RESET,"supporter":false}""")).isTrue()
        assertThat(quota.current()).isEqualTo(LosslessAllowance(12, 500, RESET))

        val q2 = LosslessQuota.inMemory { now }
        assertThat(q2.onQuotaBody("""{"used":12,"limit":"unlimited","resetsAt":$RESET}""")).isTrue()
        assertThat(q2.current()?.unlimited).isTrue()

        val q3 = LosslessQuota.inMemory { now }
        assertThat(q3.onQuotaBody("""{"used":12,"limit":500,"resetsAt":$RESET,"supporter":true}""")).isTrue()
        assertThat(q3.current()?.unlimited).isTrue()

        val q4 = LosslessQuota.inMemory { now }
        assertThat(q4.onQuotaBody("""{"used":12,"resetsAt":$RESET}""")).isFalse()
        assertThat(q4.onQuotaBody("""{"error":"unauthorized"}""")).isFalse()
        assertThat(q4.current()).isNull()
    }

    @Test fun `needsColdRefresh only when nothing is stored for today`() {
        assertThat(quota.needsColdRefresh()).isTrue()
        quota.onHeaders("1/500", "$RESET")
        assertThat(quota.needsColdRefresh()).isFalse()
        now = RESET * 1000
        assertThat(quota.needsColdRefresh()).isTrue()
    }

    @Test fun `heads-ups fire once a day at 50 left and at the limit`() = runTest {
        suspend fun next() = withTimeoutOrNull(100) { quota.noticeEvents.first() }

        quota.onHeaders("449/500", "$RESET")
        assertThat(next()).isNull()

        quota.onHeaders("450/500", "$RESET")
        assertThat(next()).isEqualTo(LosslessQuotaNotice.RunningLow(50, 500, RESET * 1000))
        quota.onHeaders("470/500", "$RESET")
        assertThat(next()).isNull() // once a day

        quota.onHeaders("500/500", "$RESET")
        assertThat(next()).isEqualTo(LosslessQuotaNotice.Spent(500, RESET * 1000))
        quota.onCapBody("""{"error":"daily_lossless_cap","used":500,"limit":500,"resetsAt":$RESET}""")
        assertThat(next()).isNull()

        // A new day announces again.
        now = RESET * 1000 + 1
        quota.onHeaders("455/500", "${RESET + DAY}")
        assertThat(next()).isEqualTo(LosslessQuotaNotice.RunningLow(45, 500, (RESET + DAY) * 1000))
    }

    @Test fun `a first value already past the limit announces the limit, not running low`() = runTest {
        quota.onHeaders("500/500", "$RESET")
        assertThat(withTimeoutOrNull(100) { quota.noticeEvents.first() }).isEqualTo(LosslessQuotaNotice.Spent(500, RESET * 1000))
    }

    @Test fun `unlimited never announces`() = runTest {
        quota.onHeaders("480/unlimited", "$RESET")
        assertThat(withTimeoutOrNull(100) { quota.noticeEvents.first() }).isNull()
    }

    @Test fun `a heads-up raised before a supporter claim lifted the limit is dropped`() = runTest {
        quota.onHeaders("500/500", "$RESET")
        quota.onQuotaBody("""{"used":500,"limit":null,"resetsAt":$RESET,"supporter":true}""")
        assertThat(quota.isSpent()).isFalse()
        assertThat(withTimeoutOrNull(100) { quota.noticeEvents.first() }).isNull()
    }

    @Test fun `a heads-up raised before a claim lifted the spent day is dropped`() = runTest {
        quota.onHeaders("500/500", "$RESET")
        quota.liftSpent()
        assertThat(withTimeoutOrNull(100) { quota.noticeEvents.first() }).isNull()
    }

    @Test fun `a heads-up whose day has reset before it is shown is dropped`() = runTest {
        quota.onHeaders("500/500", "$RESET")
        now = RESET * 1000
        assertThat(withTimeoutOrNull(100) { quota.noticeEvents.first() }).isNull()
    }

    @Test fun `the allowance and the day's notices survive a restart`() = withFileStore { rig ->
        val first = LosslessQuota(rig.store, { now }, rig.quotaScope)
        first.awaitLoaded()
        first.onHeaders("500/500", "$RESET")
        rig.settle()
        first.noticeEvents.first() // raised before its write, and buffered: here by now
        assertThat(rig.storedKeys()).containsAtLeast("used", "limit", "resets_at", "noticed_day", "noticed_level")

        val second = LosslessQuota(rig.store, { now }, rig.quotaScope)
        second.awaitLoaded()
        assertThat(second.current()).isEqualTo(LosslessAllowance(500, 500, RESET))
        assertThat(second.isSpent()).isTrue()
        // The limit was already announced today: a re-report says nothing.
        second.onHeaders("500/500", "$RESET")
        rig.settle() // anything the re-report started has run, so a notice it raised would be waiting now
        assertThat(withTimeoutOrNull(300) { second.noticeEvents.first() }).isNull()
    }

    /** A real file DataStore and a scope apart from the store's own for the [LosslessQuota]s under test. */
    private class FileStoreRig(val store: DataStore<Preferences>, private val quotaJob: Job, val quotaScope: CoroutineScope) {
        /**
         * Returns once every write the quotas started (allowance, notice, lift) has finished: it joins each coroutine they
         * launched, and a DataStore `edit` returns only once its write is in the file. Ends on the work, never on a clock.
         */
        suspend fun settle() {
            while (true) {
                val running = quotaJob.children.filter { it.isActive }.toList()
                if (running.isEmpty()) return
                running.joinAll()
            }
        }

        /**
         * The names of the keys the store holds. Read once nothing is writing (after [settle]), so it is the last write; a write
         * that failed (LosslessQuota logs and drops it) fails the assertion on it, naming the key, instead of a wait timing out.
         */
        suspend fun storedKeys(): Set<String> = store.data.first().asMap().keys.mapTo(HashSet()) { it.name }
    }

    /**
     * Runs [body] against a real file DataStore, as the app keeps one, so that it ends on the work and not on the machine's speed:
     *  - the store and the quotas run on a thread of their own, not the Dispatchers.IO pool every other test in this JVM shares;
     *  - [FileStoreRig.settle] waits for the writes themselves. Waiting on `store.data.first { <enough keys> }`, as these tests
     *    did, can wait forever: DataStore 1.1's `data`, collected while a write is under way, reads the old file under the new
     *    version, then drops the cache update that carries that same version (DataStoreImpl `readDataOrHandleCorruption`
     *    `preLockVersion`, `dropWhile { it.version <= startState.version }`). When that write was the last one, nothing
     *    wakes the wait: a hang, not a slow disk, which is why raising its timeout from 5 s to 20 s did not stop the CI failures.
     * [CEILING_MS] bounds the whole body only so that a real hang fails this test instead of the CI job.
     */
    private fun withFileStore(body: suspend (FileStoreRig) -> Unit) {
        val dir: File = Files.createTempDirectory("lossless-quota").toFile()
        val io = Executors.newSingleThreadExecutor { r -> Thread(r, "lossless-quota-test").apply { isDaemon = true } }
            .asCoroutineDispatcher()
        val storeScope = CoroutineScope(SupervisorJob() + io)
        val quotaJob = SupervisorJob()
        try {
            val store = PreferenceDataStoreFactory.create(scope = storeScope) { File(dir, "q.preferences_pb") }
            runBlocking { withTimeout(CEILING_MS) { body(FileStoreRig(store, quotaJob, CoroutineScope(quotaJob + io))) } }
        } finally {
            quotaJob.cancel()
            storeScope.cancel()
            io.close()
            dir.deleteRecursively()
        }
    }

    private companion object {
        const val DAY = 86_400L
        /** 2026-10-18 00:00 UTC. */
        const val RESET = 1_792_281_600L

        /** Only a hang reaches it: the work inside is a few small file writes on a thread of their own. */
        const val CEILING_MS = 60_000L
    }
}
