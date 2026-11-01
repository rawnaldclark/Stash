package com.stash.core.data.sync.workers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The cold-start gate for the full Stash Mix refresh: at most about once a day, never holding back a mix that needs
 * building. The clock is injected; the store is the real SharedPreferences file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class StashMixRefreshStampTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences(StashMixRefreshStamp.PREFS_NAME, Context.MODE_PRIVATE)
    private var now = START

    private fun stamp() = StashMixRefreshStamp(prefs) { now }

    @Before fun clear() {
        prefs.edit().clear().commit()
    }

    @Test fun `with no refresh on record a cold start refreshes`() {
        assertTrue(stamp().coldStartRefreshDue(mixNeedsBuilding = false))
    }

    @Test fun `a full refresh records the clock's time`() {
        stamp().markFullRefresh()
        assertEquals(START, stamp().lastFullRefreshAt())
    }

    @Test fun `a cold start soon after a full refresh skips it`() {
        stamp().markFullRefresh()
        now = START + TimeUnit.HOURS.toMillis(1)
        assertFalse(stamp().coldStartRefreshDue(mixNeedsBuilding = false))
        now = START + StashMixRefreshStamp.COLD_START_MIN_GAP_MS - 1
        assertFalse(stamp().coldStartRefreshDue(mixNeedsBuilding = false))
    }

    @Test fun `a cold start a day later refreshes again`() {
        stamp().markFullRefresh()
        now = START + StashMixRefreshStamp.COLD_START_MIN_GAP_MS
        assertTrue(stamp().coldStartRefreshDue(mixNeedsBuilding = false))
        now = START + TimeUnit.DAYS.toMillis(3)
        assertTrue(stamp().coldStartRefreshDue(mixNeedsBuilding = false))
    }

    @Test fun `a new UTC day is due a refresh, since the mixes' order rotates per UTC day`() {
        now = START + TimeUnit.HOURS.toMillis(23) // 23:00Z
        stamp().markFullRefresh()
        now = START + TimeUnit.HOURS.toMillis(23) + TimeUnit.MINUTES.toMillis(30) // 23:30Z, same day
        assertFalse(stamp().coldStartRefreshDue(mixNeedsBuilding = false))
        now = START + TimeUnit.HOURS.toMillis(25) // 01:00Z the next day, only 2 h later
        assertTrue(stamp().coldStartRefreshDue(mixNeedsBuilding = false))
    }

    @Test fun `a mix that needs building is built at once, even right after a refresh`() {
        stamp().markFullRefresh()
        now = START + 1
        assertTrue(stamp().coldStartRefreshDue(mixNeedsBuilding = true))
    }

    @Test fun `a stamp from the future (clock set back) never holds a refresh back`() {
        stamp().markFullRefresh()
        now = START - TimeUnit.MINUTES.toMillis(5)
        assertTrue(stamp().coldStartRefreshDue(mixNeedsBuilding = false))
    }

    @Test fun `the stamp survives a new instance, as across process restarts`() {
        stamp().markFullRefresh()
        now = START + TimeUnit.HOURS.toMillis(2)
        val afterRestart = StashMixRefreshStamp(
            context.getSharedPreferences(StashMixRefreshStamp.PREFS_NAME, Context.MODE_PRIVATE),
        ) { now }
        assertFalse(afterRestart.coldStartRefreshDue(mixNeedsBuilding = false))
    }

    private companion object {
        const val START = 1_761_955_200_000L // 2025-11-01T00:00Z, any fixed wall time
    }
}
