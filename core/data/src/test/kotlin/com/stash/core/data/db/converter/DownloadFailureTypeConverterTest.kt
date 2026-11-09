package com.stash.core.data.db.converter

import com.stash.core.model.DownloadFailureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The failure type column read back leniently: a name this build doesn't know (written by a newer
 * build, then the app downgraded) reads as UNKNOWN instead of throwing from every query that
 * touches the row, which took the Downloads screen and every download worker down with it.
 */
class DownloadFailureTypeConverterTest {

    private val converters = Converters()

    @Test fun `every known type survives the round trip`() {
        DownloadFailureType.entries.forEach {
            assertEquals(it, converters.stringToDownloadFailureType(converters.downloadFailureTypeToString(it)))
        }
    }

    @Test fun `a name this build doesn't know reads as UNKNOWN`() {
        assertEquals(DownloadFailureType.UNKNOWN, converters.stringToDownloadFailureType("QUOTA_EXCEEDED_SOMEDAY"))
        assertEquals(DownloadFailureType.UNKNOWN, converters.stringToDownloadFailureType(""))
    }

    @Test fun `no value stays no value`() {
        assertNull(converters.stringToDownloadFailureType(null))
        assertNull(converters.downloadFailureTypeToString(null))
    }
}
