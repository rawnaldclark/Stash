package com.stash.core.addons

import okhttp3.Dns
import okhttp3.Interceptor
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException

/**
 * The base rules' DNS half (add-ons plan phase 4 §2.6, review N7): a name that resolves to a
 * private, loopback, link-local or CGNAT address is refused for add-ons Stash doesn't vouch for.
 * [AddonValidator.isPublicHost] refuses such addresses written into a URL; this refuses them
 * reached through a name (`127.0.0.1.nip.io`, a name that rebinds between two lookups). OkHttp
 * connects only to the addresses a lookup returns, so a filtered answer can't be bypassed later.
 *
 * Private answers are dropped and the public ones kept; a name with only private answers fails as
 * unknown, so the call is a plain network failure. [allowLoopback]: debug builds, where an add-on
 * under `wrangler dev` on `localhost` is reached over `adb reverse`.
 *
 * The one name that is not an add-on's: the user's own HTTP proxy ([throughProxies]).
 */
class PublicOnlyDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val allowLoopback: Boolean = false,
) : Dns {
    /** The proxies the call running on this thread goes through, by name ([throughProxies]). */
    private val proxyHosts = ThreadLocal<Set<String>>()

    /**
     * What OkHttp asks. Behind an HTTP proxy that is named by host (Java's and Android's proxy
     * selectors hand out unresolved addresses) that is the PROXY's name, never the target's, and
     * a work proxy on 10.x or a relay on localhost has only private answers: filtered, every call
     * of an add-on the user added failed there (review-882 round 2, F1). The proxy is the user's
     * own choice, so its name is resolved as it is; every other name is filtered ([lookupPublic]).
     */
    override fun lookup(hostname: String): List<InetAddress> {
        if (proxyHosts.get()?.contains(hostname.lowercase()) == true) return delegate.lookup(hostname)
        return lookupPublic(hostname)
    }

    /**
     * Runs [block], one call's `chain.proceed`, with [hosts] as the names of the HTTP proxies that
     * call may go through: a [lookup] of one of them inside it is not filtered. Only the caller
     * that has already checked the call's target says so ([AddonMediaGuard]); for the thread only,
     * which is where OkHttp 4 selects a call's route and resolves its names.
     */
    fun <T> throughProxies(hosts: Set<String>, block: () -> T): T {
        if (hosts.isEmpty()) return block()
        val before = proxyHosts.get()
        proxyHosts.set(hosts)
        try {
            return block()
        } finally {
            if (before == null) proxyHosts.remove() else proxyHosts.set(before)
        }
    }

    /** [hostname]'s public addresses, whatever proxy is in use: the filter itself. */
    fun lookupPublic(hostname: String): List<InetAddress> {
        val all = delegate.lookup(hostname)
        val kept = all.filter { isPublic(it) || (allowLoopback && it.isLoopbackAddress) }
        // Never the name in the message: a log of it would be harmless, but nothing here needs it.
        if (kept.isEmpty()) {
            android.util.Log.w(TAG, "an add-on host resolved to no public address (${all.size} private): refused")
            throw NoPublicAddressException()
        }
        return kept
    }

    companion object {
        private const val TAG = "AddonDns"

        /**
         * True for an address on the public internet. False for: unspecified, loopback,
         * link-local, RFC 1918 and IPv6 site-local, CGNAT (100.64/10, Tailscale's range), ULA
         * (fc00::/7), multicast, `0.0.0.0/8`, the IETF block 192.0.0.0/24, benchmarking
         * 198.18/15, the documentation blocks (192.0.2/24, 198.51.100/24, 203.0.113/24,
         * 2001:db8::/32), Teredo (2001::/32), reserved 240/4 and broadcast; and the IPv4 inside
         * an IPv4-mapped, NAT64 (64:ff9b::/96) or 6to4 (2002::/16) IPv6 address is checked as itself.
         *
         * What no address rule can tell: a device on the user's own network that has a global
         * IPv6 address is "public" by every one of these.
         */
        fun isPublic(a: InetAddress): Boolean {
            if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) {
                return false
            }
            val b = a.address
            return when (a) {
                is Inet4Address -> isPublicV4(b)
                is Inet6Address -> isPublicV6(b)
                else -> false
            }
        }

        private fun isPublicV4(b: ByteArray): Boolean {
            val o0 = b[0].toInt() and 0xff
            val o1 = b[1].toInt() and 0xff
            val o2 = b[2].toInt() and 0xff
            return when {
                o0 == 0 || o0 == 10 || o0 == 127 -> false
                o0 == 100 && o1 in 64..127 -> false // CGNAT, RFC 6598
                o0 == 169 && o1 == 254 -> false
                o0 == 172 && o1 in 16..31 -> false
                o0 == 192 && o1 == 168 -> false
                o0 == 192 && o1 == 0 && o2 == 0 -> false // IETF protocol assignments
                o0 == 192 && o1 == 0 && o2 == 2 -> false // documentation (TEST-NET-1)
                o0 == 198 && (o1 == 18 || o1 == 19) -> false // benchmarking
                o0 == 198 && o1 == 51 && o2 == 100 -> false // documentation (TEST-NET-2)
                o0 == 203 && o1 == 0 && o2 == 113 -> false // documentation (TEST-NET-3)
                o0 >= 224 -> false // multicast, reserved, broadcast
                else -> true
            }
        }

        private fun isPublicV6(b: ByteArray): Boolean {
            val first = b[0].toInt() and 0xff
            if (first and 0xfe == 0xfc) return false // ULA fc00::/7
            if (first == 0xfe && (b[1].toInt() and 0xc0) == 0x80) return false // fe80::/10
            // IPv4-mapped (::ffff:a.b.c.d) and NAT64 (64:ff9b::a.b.c.d): judge the IPv4 inside.
            val mapped = (0..9).all { b[it].toInt() == 0 } && (b[10].toInt() and 0xff) == 0xff && (b[11].toInt() and 0xff) == 0xff
            val nat64 = (b[0].toInt() and 0xff) == 0x00 && (b[1].toInt() and 0xff) == 0x64 &&
                (b[2].toInt() and 0xff) == 0xff && (b[3].toInt() and 0xff) == 0x9b && (4..11).all { b[it].toInt() == 0 }
            if (mapped || nat64) return isPublicV4(b.copyOfRange(12, 16))
            // 6to4 (2002:AABB:CCDD::/48) carries the IPv4 AA.BB.CC.DD: a private one inside is private.
            if (first == 0x20 && (b[1].toInt() and 0xff) == 0x02) return isPublicV4(b.copyOfRange(2, 6))
            // 2001:db8::/32, documentation.
            if (first == 0x20 && (b[1].toInt() and 0xff) == 0x01 && (b[2].toInt() and 0xff) == 0x0d && (b[3].toInt() and 0xff) == 0xb8) return false
            // 2001::/32, Teredo: a tunnel address, of a host behind a NAT. No media host lives there.
            if (first == 0x20 && (b[1].toInt() and 0xff) == 0x01 && b[2].toInt() == 0 && b[3].toInt() == 0) return false
            // ::/96 (IPv4-compatible, deprecated) and anything else in ::/8 is not a real host.
            if (first == 0) return false
            return true
        }

        /**
         * A network interceptor for the add-on media client: the connection's peer must be
         * public. The last of three checks, behind [AddonMediaGuard] (the URL rule on every URL
         * and redirect hop, before any connection) and this class's own DNS filter: whatever got
         * past both still never has a request sent to a private peer. Only for direct
         * connections: behind a proxy the peer is the proxy, which the user chose.
         */
        fun connectionGuard(allowLoopback: Boolean): Interceptor = Interceptor { chain ->
            val route = chain.connection()?.route()
            val peer = route?.socketAddress?.address
            if (route != null && route.proxy.type() == Proxy.Type.DIRECT && peer != null &&
                !isPublic(peer) && !(allowLoopback && peer.isLoopbackAddress)
            ) {
                throw PrivateAddressException()
            }
            chain.proceed(chain.request())
        }
    }
}

/**
 * A name an add-on handed over resolved, and to the user's own network only: this filter's own
 * refusal, as an [UnknownHostException] so a caller sees a plain network failure. Its own class
 * so the refusal can be told from a name that simply does not resolve. Never names the host.
 */
class NoPublicAddressException : UnknownHostException("no public address for an add-on host")

/** An add-on's media was on the user's own network: refused (add-ons plan phase 4 §2.6). Never names the address. */
class PrivateAddressException : IOException("an add-on stream on a private address was refused")
