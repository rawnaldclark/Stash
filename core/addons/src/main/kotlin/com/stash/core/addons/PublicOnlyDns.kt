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
 */
class PublicOnlyDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val allowLoopback: Boolean = false,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = delegate.lookup(hostname)
        val kept = all.filter { isPublic(it) || (allowLoopback && it.isLoopbackAddress) }
        // Never the name in the message: a log of it would be harmless, but nothing here needs it.
        if (kept.isEmpty()) throw UnknownHostException("no public address for an add-on host")
        return kept
    }

    companion object {
        /**
         * True for an address on the public internet. False for: unspecified, loopback,
         * link-local, RFC 1918 and IPv6 site-local, CGNAT (100.64/10, Tailscale's range), ULA
         * (fc00::/7), multicast, `0.0.0.0/8`, the IETF block 192.0.0.0/24, benchmarking
         * 198.18/15, reserved 240/4 and broadcast; and the IPv4 inside an IPv4-mapped or NAT64
         * (64:ff9b::/96) IPv6 address is checked as itself.
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
                o0 == 198 && (o1 == 18 || o1 == 19) -> false // benchmarking
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
            // ::/96 (IPv4-compatible, deprecated) and anything else in ::/8 is not a real host.
            if (first == 0) return false
            return true
        }

        /**
         * A network interceptor for a client that follows redirects on its own (the player's
         * add-on media client): the connection's peer must be public. Catches what the [Dns]
         * never sees, an IP literal in a redirect's `Location`. Only for direct connections: behind
         * a proxy the peer is the proxy, which the user chose.
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

/** An add-on's media was on the user's own network: refused (add-ons plan phase 4 §2.6). Never names the address. */
class PrivateAddressException : IOException("an add-on stream on a private address was refused")
