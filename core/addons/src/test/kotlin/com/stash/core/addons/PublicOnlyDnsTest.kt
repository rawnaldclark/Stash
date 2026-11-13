package com.stash.core.addons

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
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

    /** review-882 round 2, F1: the user's own proxy is not an add-on's host. */
    @Test fun `a proxy's own name is resolved as it is, for the call that goes through it and nothing else`() {
        val asked = mutableListOf<String>()
        val dns = PublicOnlyDns(
            object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    asked += hostname
                    return listOf(ip("10.0.0.8"))
                }
            },
        )
        // Outside a call through it, the proxy's name is a name like any other.
        assertThrows(NoPublicAddressException::class.java) { dns.lookup("proxy.corp.example") }

        val through = dns.throughProxies(setOf("proxy.corp.example")) {
            // Any other name is still filtered inside it, and so is the target, always.
            assertThrows(NoPublicAddressException::class.java) { dns.lookup("media.attacker.example") }
            assertThrows(NoPublicAddressException::class.java) { dns.lookupPublic("proxy.corp.example") }
            dns.lookup("Proxy.Corp.Example")
        }
        assertThat(through.map { it.hostAddress }).containsExactly("10.0.0.8")

        // Over when the call is, and never on another thread.
        assertThrows(NoPublicAddressException::class.java) { dns.lookup("proxy.corp.example") }
        var other: Throwable? = null
        dns.throughProxies(setOf("proxy.corp.example")) {
            Thread { other = runCatching { dns.lookup("proxy.corp.example") }.exceptionOrNull() }.apply { start(); join() }
        }
        assertThat(other).isInstanceOf(NoPublicAddressException::class.java)
        // A failed call leaves nothing behind either.
        runCatching { dns.throughProxies(setOf("proxy.corp.example")) { error("the call failed") } }
        assertThrows(NoPublicAddressException::class.java) { dns.lookup("proxy.corp.example") }
    }

    @Test fun `the proxies of a call are its HTTP proxies by name, never the call's own host`() {
        val url = "https://media.public.example/a.flac".toHttpUrl()
        val named = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("Proxy.Corp.Example", 8080))
        val socks = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("socks.corp.example", 1080))
        val selector = object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> = listOf(named, socks, Proxy.NO_PROXY)
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        }
        assertThat(AddonMediaGuard.proxyHosts(url, named, null)).containsExactly("proxy.corp.example")
        assertThat(AddonMediaGuard.proxyHosts(url, null, selector)).containsExactly("proxy.corp.example")
        assertThat(AddonMediaGuard.proxyHosts(url, Proxy.NO_PROXY, selector)).isEmpty()
        assertThat(AddonMediaGuard.proxyHosts(url, null, null)).isEmpty()
        // An add-on that names the proxy's host as its own: that name stays filtered for the call.
        assertThat(AddonMediaGuard.proxyHosts("https://proxy.corp.example/a.flac".toHttpUrl(), named, null)).isEmpty()
    }

    @Test fun `what counts as private`() {
        val private = listOf(
            "0.0.0.0", "0.1.2.3", "10.1.2.3", "100.64.0.1", "100.127.255.254", "127.0.0.1", "169.254.169.254",
            "172.16.0.1", "172.31.255.255", "192.168.0.1", "192.0.0.8", "198.18.0.1", "224.0.0.1", "240.0.0.1",
            "255.255.255.255", "::", "::1", "fe80::1", "fc00::1", "fd12:3456::1", "::ffff:192.168.1.1", "64:ff9b::a00:1", "ff02::1",
            // review-882 M2: 6to4 of a private IPv4, and the documentation blocks.
            "2002:c0a8:101::1", "2002:a00:5::1", "192.0.2.7", "198.51.100.7", "203.0.113.7", "2001:db8::1",
            // review-882 round 2, n7: Teredo (2001::/32), a tunnel to a host behind a NAT.
            "2001:0:4136:e378:8000:63bf:3fff:fdd2", "2001::1",
        )
        for (a in private) assertWithMessage(a).that(PublicOnlyDns.isPublic(ip(a))).isFalse()
        val public = listOf(
            "1.1.1.1", "8.8.8.8", "100.63.255.255", "100.128.0.1", "172.32.0.1", "93.184.216.34", "2606:4700::1111", "64:ff9b::808:808",
            // 6to4 of a public IPv4 stays public; the blocks beside the documentation ones are ordinary.
            "2002:808:808::1", "192.0.3.1", "198.51.101.1", "203.0.114.1", "2001:db9::1",
            // The Teredo rule is its /32 only: the rest of 2001::/16 is where much of the internet lives.
            "2001:4860:4860::8888", "2001:200::1",
        )
        for (a in public) assertWithMessage(a).that(PublicOnlyDns.isPublic(ip(a))).isTrue()
    }
}
