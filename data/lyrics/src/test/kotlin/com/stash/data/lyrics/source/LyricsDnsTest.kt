package com.stash.data.lyrics.source

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class LyricsDnsTest {

    private val v4 = InetAddress.getByName("104.21.0.1")
    private val v4b = InetAddress.getByName("172.67.0.1")
    private val v6 = InetAddress.getByName("2606:4700::1")
    private val rewritten = InetAddress.getByName("100.88.0.1")

    private class Fake(var answer: () -> List<InetAddress>) : Dns {
        var calls = 0
        override fun lookup(hostname: String): List<InetAddress> { calls++; return answer() }
    }

    @Test fun `DoH answers first and the system resolver is not asked`() {
        val doh = Fake { listOf(v6, v4) }
        val system = Fake { listOf(rewritten) }
        assertEquals(listOf(v4, v6), LyricsDns(doh, system).lookup("lyrics.paxsenix.org"))
        assertEquals(0, system.calls)
    }

    @Test fun `a DoH failure falls back to the system resolver, IPv4 first`() {
        val doh = Fake { throw UnknownHostException("blocked") }
        val system = Fake { listOf(v6, v4b) }
        assertEquals(listOf(v4b, v6), LyricsDns(doh, system).lookup("lyrics.paxsenix.org"))
    }

    @Test fun `an empty DoH answer falls back to the system resolver`() {
        val dns = LyricsDns(Fake { emptyList() }, Fake { listOf(v4) })
        assertEquals(listOf(v4), dns.lookup("lyrics.paxsenix.org"))
    }

    @Test fun `after a DoH failure DoH is paused, then tried again`() {
        var now = 0L
        val doh = Fake { throw UnknownHostException("blocked") }
        val dns = LyricsDns(doh, Fake { listOf(v4) }) { now }
        dns.lookup("a"); dns.lookup("b"); dns.lookup("c")
        assertEquals("one DoH attempt per pause window", 1, doh.calls)
        now += LyricsDns.DOH_PAUSE_MS
        doh.answer = { listOf(v4b) }
        assertEquals(listOf(v4b), dns.lookup("d"))
        assertEquals(2, doh.calls)
    }

    @Test fun `nothing anywhere is an UnknownHostException`() {
        val dns = LyricsDns(Fake { throw UnknownHostException("doh") }, Fake { throw UnknownHostException("system") })
        assertThrows(UnknownHostException::class.java) { dns.lookup("nowhere.invalid") }
    }
}
