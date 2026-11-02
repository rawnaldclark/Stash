package com.stash.core.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A dropout (no active network) keeps the last metered answer, so Data saver doesn't read a tunnel
 * on mobile data as Wi-Fi. The object is process-wide; each test sets what it starts from.
 */
class MeteredNetworkTest {

    @Test
    fun `a dropout on mobile data stays metered, and on Wi-Fi stays unmetered`() {
        assertTrue(MeteredNetwork.read(true))
        assertTrue("no network: the last answer", MeteredNetwork.read(null))
        assertTrue(MeteredNetwork.read(null))

        assertFalse(MeteredNetwork.read(false))
        assertFalse("no network: the last answer", MeteredNetwork.read(null))
    }

    @Test
    fun `a real answer always wins over the remembered one`() {
        MeteredNetwork.read(false)
        assertTrue(MeteredNetwork.read(true))
        assertFalse(MeteredNetwork.read(false))
    }
}
