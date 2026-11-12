package com.stash.core.addons

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import okhttp3.Dns
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/** The base rules' DNS half (add-ons plan phase 4 §2.6): private answers are dropped. */
class PublicOnlyDnsTest {

    private fun ip(s: String): InetAddress = InetAddress.getByName(s)

    private fun answering(vararg addresses: String) = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = addresses.map(::ip)
    }

    @Test fun `private answers are dropped and public ones kept`() {
        val dns = PublicOnlyDns(answering("192.168.1.1", "93.184.216.34", "10.0.0.2", "2606:2800:220:1::1"))
        assertThat(dns.lookup("mixed.example").map { it.hostAddress }).containsExactly("93.184.216.34", "2606:2800:220:1:0:0:0:1")
    }

    @Test fun `a name with only private answers fails as unknown`() {
        // 127.0.0.1.nip.io and friends: a public-looking name for the user's own network.
        val dns = PublicOnlyDns(answering("127.0.0.1"))
        val e = assertThrows(UnknownHostException::class.java) { dns.lookup("127.0.0.1.nip.io") }
        assertThat(e.message).doesNotContain("nip.io")
    }

    @Test fun `debug builds may reach loopback, nothing else private`() {
        val dns = PublicOnlyDns(answering("127.0.0.1", "192.168.1.1"), allowLoopback = true)
        assertThat(dns.lookup("localhost").map { it.hostAddress }).containsExactly("127.0.0.1")
    }

    @Test fun `what counts as private`() {
        val private = listOf(
            "0.0.0.0", "0.1.2.3", "10.1.2.3", "100.64.0.1", "100.127.255.254", "127.0.0.1", "169.254.169.254",
            "172.16.0.1", "172.31.255.255", "192.168.0.1", "192.0.0.8", "198.18.0.1", "224.0.0.1", "240.0.0.1",
            "255.255.255.255", "::", "::1", "fe80::1", "fc00::1", "fd12:3456::1", "::ffff:192.168.1.1", "64:ff9b::a00:1", "ff02::1",
            // review-882 M2: 6to4 of a private IPv4, and the documentation blocks.
            "2002:c0a8:101::1", "2002:a00:5::1", "192.0.2.7", "198.51.100.7", "203.0.113.7", "2001:db8::1",
        )
        for (a in private) assertWithMessage(a).that(PublicOnlyDns.isPublic(ip(a))).isFalse()
        val public = listOf(
            "1.1.1.1", "8.8.8.8", "100.63.255.255", "100.128.0.1", "172.32.0.1", "93.184.216.34", "2606:4700::1111", "64:ff9b::808:808",
            // 6to4 of a public IPv4 stays public; the blocks beside the documentation ones are ordinary.
            "2002:808:808::1", "192.0.3.1", "198.51.101.1", "203.0.114.1", "2001:db9::1",
        )
        for (a in public) assertWithMessage(a).that(PublicOnlyDns.isPublic(ip(a))).isTrue()
    }
}
