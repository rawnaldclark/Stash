package com.stash.core.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class DayOfWeekSetTest {

    @Test fun `bit 0 is Monday`() {
        assertEquals(0, DayOfWeek.MONDAY.value - 1)
        assertTrue(DayOfWeekSet(0b0000001).contains(DayOfWeek.MONDAY))
        assertFalse(DayOfWeekSet(0b0000001).contains(DayOfWeek.TUESDAY))
    }

    @Test fun `bit 6 is Sunday`() {
        assertEquals(6, DayOfWeek.SUNDAY.value - 1)
        assertTrue(DayOfWeekSet(0b1000000).contains(DayOfWeek.SUNDAY))
        assertFalse(DayOfWeekSet(0b1000000).contains(DayOfWeek.SATURDAY))
    }

    @Test fun `with toggles a single bit and leaves others alone`() {
        val mwf = DayOfWeekSet(0b0010101) // Mon, Wed, Fri
        val withTue = mwf.with(DayOfWeek.TUESDAY, on = true)
        assertEquals(DayOfWeekSet(0b0010111), withTue)
        val withoutMon = mwf.with(DayOfWeek.MONDAY, on = false)
        assertEquals(DayOfWeekSet(0b0010100), withoutMon)
    }

    @Test fun `isDaily true on 127`() {
        assertTrue(DayOfWeekSet(0b1111111).isDaily)
        assertFalse(DayOfWeekSet(0b1111110).isDaily)
    }

    @Test fun `isWeekdays true on 31 only`() {
        assertTrue(DayOfWeekSet(0b0011111).isWeekdays)
        assertFalse(DayOfWeekSet(0b0111111).isWeekdays)
        assertFalse(DayOfWeekSet(0b0010111).isWeekdays)
    }

    @Test fun `isWeekends true on 96 only`() {
        assertTrue(DayOfWeekSet(0b1100000).isWeekends)
        assertFalse(DayOfWeekSet(0b1100001).isWeekends)
        assertFalse(DayOfWeekSet(0b0100000).isWeekends)
    }

    @Test fun `presetLabel returns daily weekdays weekends or compact`() {
        assertEquals("daily", DayOfWeekSet(0b1111111).presetLabel())
        assertEquals("weekdays", DayOfWeekSet(0b0011111).presetLabel())
        assertEquals("weekends", DayOfWeekSet(0b1100000).presetLabel())
        assertEquals("Mon · Wed · Fri", DayOfWeekSet(0b0010101).presetLabel())
        assertEquals("Tue", DayOfWeekSet(0b0000010).presetLabel())
        assertEquals("none", DayOfWeekSet(0).presetLabel())
    }

    @Test fun `isEmpty true only on 0`() {
        assertTrue(DayOfWeekSet(0).isEmpty)
        assertFalse(DayOfWeekSet(1).isEmpty)
        assertFalse(DayOfWeekSet(127).isEmpty)
    }

    @Test fun `count returns number of set bits`() {
        assertEquals(0, DayOfWeekSet(0).count)
        assertEquals(7, DayOfWeekSet(127).count)
        assertEquals(3, DayOfWeekSet(0b0010101).count)
    }
}
