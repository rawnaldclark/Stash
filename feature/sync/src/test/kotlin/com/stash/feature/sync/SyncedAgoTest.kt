package com.stash.feature.sync

import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Sync tab's "synced N ago": the words for a finish time read at a given moment (Android's own
 * DateUtils words, under Robolectric), and the clock that reads them again at each whole minute.
 * The clock is injected; delays run on the test's virtual time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SyncedAgoTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    private lateinit var savedLocale: Locale
    private lateinit var savedZone: TimeZone

    @Before fun pinLocale() {
        savedLocale = Locale.getDefault()
        savedZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After fun restoreLocale() {
        Locale.setDefault(savedLocale)
        TimeZone.setDefault(savedZone)
    }

    private fun text(sinceFinish: Long) = syncedAgoText(FINISHED, FINISHED + sinceFinish, JUST_NOW)

    @Test fun `under a minute reads just now`() {
        assertEquals(JUST_NOW, text(0))
        assertEquals(JUST_NOW, text(59_999))
    }

    @Test fun `from a minute on it reads how long ago the sync finished`() {
        assertEquals("1 minute ago", text(minute))
        assertEquals("3 minutes ago", text(3 * minute + 30_000))
        assertEquals("59 minutes ago", text(hour - 1))
        assertEquals("1 hour ago", text(hour))
        assertEquals("2 hours ago", text(2 * hour + 5 * minute))
        assertEquals("Yesterday", text(day))
    }

    @Test fun `a clock behind the finish reads just now, never in N minutes`() {
        assertEquals(JUST_NOW, text(-5 * minute))
    }

    @Test fun `no sync reads nothing`() {
        assertEquals("", syncedAgoText(null, FINISHED, JUST_NOW))
    }

    @Test fun `the clock reads again at each whole minute after the finish`() = runTest {
        // The tab opens 40 s after the sync finished.
        val opened = FINISHED + 40_000
        val readings = syncedAgoClock(FINISHED) { opened + testScheduler.currentTime }.take(4).toList()

        assertEquals(listOf(opened, FINISHED + minute, FINISHED + 2 * minute, FINISHED + 3 * minute), readings)
        assertEquals(
            listOf(JUST_NOW, "1 minute ago", "2 minutes ago", "3 minutes ago"),
            readings.map { syncedAgoText(FINISHED, it, JUST_NOW) },
        )
    }

    @Test fun `opened hours later it reads the time then, and ticks from there`() = runTest {
        // Back on the tab 3 h 20 min 15 s after the sync: no stale "1 minute ago".
        val back = FINISHED + 3 * hour + 20 * minute + 15_000
        val readings = syncedAgoClock(FINISHED) { back + testScheduler.currentTime }.take(2).toList()

        assertEquals(listOf(back, FINISHED + 3 * hour + 21 * minute), readings)
        assertEquals("3 hours ago", syncedAgoText(FINISHED, readings.first(), JUST_NOW))
    }

    @Test fun `with the clock behind the finish the next reading is at the finish`() = runTest {
        val opened = FINISHED - 10_000
        val readings = syncedAgoClock(FINISHED) { opened + testScheduler.currentTime }.take(3).toList()

        assertEquals(listOf(opened, FINISHED, FINISHED + minute), readings)
    }

    @Test fun `with no sync there is one reading and no ticking`() = runTest {
        assertEquals(listOf(42L), syncedAgoClock(null) { 42L }.toList())
    }

    private companion object {
        const val JUST_NOW = "just now"
        val FINISHED: Long = Instant.parse("2026-11-02T08:46:12Z").toEpochMilli()
    }
}
