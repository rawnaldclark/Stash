package com.stash.data.lyrics.source

import android.util.Log
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * DNS for the Apple lyrics hosts: DNS-over-HTTPS (Cloudflare) first, the system resolver as the
 * fallback, IPv4 addresses first.
 *
 * Why: on some networks plain port-53 answers for lyrics.paxsenix.org are rewritten (CGNAT and
 * NAT64-looking addresses, even when asking 8.8.8.8), and the TLS handshake then fails with
 * "Unable to parse TLS packet header"; DoH returns the real addresses. IPv6 to the host failed
 * outright on the same networks, hence IPv4 first.
 *
 * Bounded on purpose: the DoH lookup runs on its own client, outside the lyrics call's
 * callTimeout (4 s interactive), so it has its own short timeouts, and after a DoH failure it is
 * skipped for [DOH_PAUSE_MS]. A network that blocks Cloudflare DoH then costs one short wait per
 * pause window, not one per lookup, and lookups fall straight to the system resolver.
 */
internal class LyricsDns(
    private val doh: Dns = cloudflareDoh(),
    private val system: Dns = Dns.SYSTEM,
    private val now: () -> Long = System::currentTimeMillis,
) : Dns {

    @Volatile private var dohPausedUntil = 0L

    override fun lookup(hostname: String): List<InetAddress> {
        if (now() >= dohPausedUntil) {
            val viaDoh = runCatching { doh.lookup(hostname) }
                .onFailure {
                    dohPausedUntil = now() + DOH_PAUSE_MS
                    Log.d(TAG, "DoH failed for $hostname (${it.javaClass.simpleName}); system DNS for ${DOH_PAUSE_MS / 60_000} min")
                }
                .getOrNull()
            if (!viaDoh.isNullOrEmpty()) {
                Log.d(TAG, "DNS doh    $hostname -> ${viaDoh.joinToString { it.hostAddress ?: "?" }}")
                return viaDoh.sortedBy { it is Inet6Address }
            }
        }
        // Throws UnknownHostException when the system resolver has nothing either.
        val viaSystem = system.lookup(hostname)
        Log.d(TAG, "DNS system $hostname -> ${viaSystem.joinToString { it.hostAddress ?: "?" }}")
        return viaSystem.sortedBy { it is Inet6Address }
    }

    companion object {
        private const val TAG = "LyricsDns"
        internal const val DOH_PAUSE_MS = 10 * 60_000L

        /**
         * A plain client (not the app's shared one: its interceptors don't belong on DNS lookups)
         * with timeouts well under the interactive lyrics timeout.
         */
        private fun cloudflareDoh(): Dns = DnsOverHttps.Builder()
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(1500, TimeUnit.MILLISECONDS)
                    .readTimeout(1500, TimeUnit.MILLISECONDS)
                    .callTimeout(2, TimeUnit.SECONDS)
                    .build(),
            )
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
            .includeIPv6(false)
            .build()
    }
}
