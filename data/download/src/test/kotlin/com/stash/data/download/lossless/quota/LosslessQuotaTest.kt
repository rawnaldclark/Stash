package com.stash.data.download.lossless.quota

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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

    @Test fun `a lifted day survives a restart`() = runBlocking {
        val dir: File = Files.createTempDirectory("quota-lift").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "q.preferences_pb") }
            val first = LosslessQuota(store, { now }, scope)
            first.awaitLoaded()
            first.onHeaders("500/500", "$RESET")
            first.liftSpent()
            withTimeout(5_000) { store.data.first { it.asMap().size >= 6 } }

            val second = LosslessQuota(store, { now }, scope)
            second.awaitLoaded()
            assertThat(second.isSpent()).isFalse()
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
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

    @Test fun `the allowance and the day's notices survive a restart`() = runBlocking {
        val dir: File = Files.createTempDirectory("quota").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "q.preferences_pb") }
            val first = LosslessQuota(store, { now }, scope)
            first.awaitLoaded()
            first.onHeaders("500/500", "$RESET")
            withTimeout(5_000) { first.noticeEvents.first() }
            withTimeout(5_000) { store.data.first { it.asMap().size >= 5 } }

            val second = LosslessQuota(store, { now }, scope)
            second.awaitLoaded()
            assertThat(second.current()).isEqualTo(LosslessAllowance(500, 500, RESET))
            assertThat(second.isSpent()).isTrue()
            // The limit was already announced today: a re-report says nothing.
            second.onHeaders("500/500", "$RESET")
            assertThat(withTimeoutOrNull(300) { second.noticeEvents.first() }).isNull()
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    private companion object {
        const val DAY = 86_400L
        /** 2026-10-18 00:00 UTC. */
        const val RESET = 1_792_281_600L
    }
}
