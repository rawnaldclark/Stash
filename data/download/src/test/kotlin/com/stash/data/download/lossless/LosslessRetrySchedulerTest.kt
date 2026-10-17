package com.stash.data.download.lossless

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.stash.data.download.lossless.qobuz.QobuzSource
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.relay.LosslessRelayClient
import io.mockk.slot
import java.util.concurrent.TimeUnit
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * v0.9.17 strict-FLAC: tests the reactive eager-bound scheduler that
 * enqueues [LosslessRetryWorker] on each of the three signals that
 * could plausibly turn a previously-null lossless resolution into a
 * non-null one:
 *
 *  1. The user's captcha cookie value changes (fresh squid attempt
 *     possible).
 *  2. [QobuzSource.lastKnownBadCookie] clears (squid self-recovered).
 *  3. [AggregatorRateLimiter.circuitResetEvents] emits any source id
 *     (kennyy outage cleared, or user manually reset the breaker).
 *
 * The scheduler exposes [LosslessRetryScheduler.scope] and
 * [LosslessRetryScheduler.workManagerProvider] as test seams so the
 * test can pass `runTest`'s `backgroundScope` (for virtual-time control
 * over the cookie debounce window) and a MockK [WorkManager] (instead
 * of routing through `WorkManager.getInstance(context)`, which trips
 * `WorkManagerImpl`'s "not initialised" guard in JVM unit tests).
 *
 * [UnconfinedTestDispatcher] (rather than the default [StandardTestDispatcher])
 * is required because [MutableStateFlow] conflates updates: with
 * StandardTestDispatcher, the collectors don't actually subscribe until
 * `advanceUntilIdle()`, by which point a `value = X` update has already
 * overwritten the initial null and `drop(1)` consumes the value we want
 * to verify. Unconfined runs the launched collectors synchronously at
 * `launchIn` time, so they subscribe before the test mutates the flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LosslessRetrySchedulerTest {

    private val context: Context = mockk(relaxed = true)
    private val workManager: WorkManager = mockk(relaxed = true)
    private val cookieFlow = MutableStateFlow<String?>(null)
    private val lastBadCookieFlow = MutableStateFlow<String?>(null)
    private val resetEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private val losslessPrefs: LosslessSourcePreferences = mockk {
        every { captchaCookieValue } returns cookieFlow
    }
    private val qobuzSource: QobuzSource = mockk {
        every { lastKnownBadCookie } returns lastBadCookieFlow
    }
    private val rateLimiter: AggregatorRateLimiter = mockk {
        every { circuitResetEvents } returns resetEvents
    }
    private val pacedEvents = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    private val relayClient: LosslessRelayClient = mockk {
        every { pacedEvents } returns this@LosslessRetrySchedulerTest.pacedEvents
    }

    private var now = 1_000_000_000_000L
    private val quota = LosslessQuota.inMemory { now }

    private fun newSubject(scope: kotlinx.coroutines.CoroutineScope): LosslessRetryScheduler {
        val subject = LosslessRetryScheduler(
            context = context,
            losslessPrefs = losslessPrefs,
            qobuzSource = qobuzSource,
            rateLimiter = rateLimiter,
            relayClient = relayClient,
            quota = quota,
        )
        subject.scope = scope
        subject.workManagerProvider = { workManager }
        subject.start()
        return subject
    }

    @Test
    fun `cookie change enqueues retry worker`() = runTest(UnconfinedTestDispatcher()) {
        newSubject(backgroundScope)
        // Unconfined dispatcher subscribes the collectors synchronously
        // at launchIn, so the StateFlow's initial null is consumed by
        // drop(1) before this line runs. The cookie-change emission is
        // the first one drop(1) lets through.
        cookieFlow.value = "fresh-cookie"
        // debounce(1_000) on the cookie flow — advance virtual time past it.
        advanceTimeBy(1_500)
        advanceUntilIdle()

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(
                LosslessRetryWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                any<OneTimeWorkRequest>(),
            )
        }
    }

    @Test
    fun `lastBadCookie clear enqueues retry worker`() = runTest(UnconfinedTestDispatcher()) {
        newSubject(backgroundScope)

        // Transition: null -> "stale". drop(1) discarded the StateFlow's
        // initial null at launchIn time (unconfined collectors subscribe
        // synchronously); the first transition is what we observe — covers
        // both the "cookie went bad" and "cookie cleared" semantics, since
        // the scheduler treats any change as a retry candidate.
        lastBadCookieFlow.value = "stale-cookie"
        advanceUntilIdle()

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(
                LosslessRetryWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                any<OneTimeWorkRequest>(),
            )
        }
    }

    @Test
    fun `circuit-reset event enqueues retry worker`() = runTest(UnconfinedTestDispatcher()) {
        newSubject(backgroundScope)

        // SharedFlow has no replay so drop(1) isn't needed here — but
        // we still need the collector subscribed before emit() lands,
        // otherwise tryEmit-style fire-and-forget semantics drop it.
        // Unconfined dispatcher guarantees that subscription has happened
        // by the time newSubject() returns.
        resetEvents.emit("kennyy_qobuz")
        advanceUntilIdle()

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(
                LosslessRetryWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                any<OneTimeWorkRequest>(),
            )
        }
    }

    @Test
    fun `a spent allowance books one sweep just after the reset, under its own name`() = runTest(UnconfinedTestDispatcher()) {
        newSubject(backgroundScope)
        val request = slot<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(LosslessRetryScheduler.RESET_WORK_NAME, ExistingWorkPolicy.KEEP, capture(request))
        } returns mockk(relaxed = true)

        val resetsAt = now / 1000 + 7200
        quota.onHeaders("499/500", "$resetsAt")
        quota.onHeaders("500/500", "$resetsAt")
        quota.onCapBody("""{"error":"daily_lossless_cap","used":500,"limit":500,"resetsAt":$resetsAt}""", null)
        advanceUntilIdle()

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(LosslessRetryScheduler.RESET_WORK_NAME, ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>())
        }
        org.junit.Assert.assertEquals(
            TimeUnit.SECONDS.toMillis(7200 + LosslessRetryScheduler.RESET_SLACK_SEC),
            request.captured.workSpec.initialDelay,
        )
    }

    @Test
    fun `a paced download enqueues the sweep delayed by the relay's wait`() = runTest(UnconfinedTestDispatcher()) {
        newSubject(backgroundScope)
        val request = slot<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(LosslessRetryWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, capture(request))
        } returns mockk(relaxed = true)

        pacedEvents.emit(7980)
        advanceUntilIdle()

        org.junit.Assert.assertEquals(TimeUnit.SECONDS.toMillis(7980), request.captured.workSpec.initialDelay)
    }
}
