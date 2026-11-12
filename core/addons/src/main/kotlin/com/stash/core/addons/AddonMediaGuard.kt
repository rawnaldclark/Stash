package com.stash.core.addons

import com.stash.addons.contract.Auth
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.ProtocolException
import java.net.Proxy
import java.net.ProxySelector

/**
 * The guard on every URL an add-on hands over that Stash then fetches: a stream, a file to keep,
 * a DASH manifest and each of its segments (add-ons plan phase 4 §2.6; review-882 B1 and M2). It is
 * the application interceptor of [HttpAddonClient.mediaClient], the one client those fetches go
 * through: the player's add-on streams, the downloader's add-on files and the DASH downloader's
 * segments.
 *
 * For each request, and for each redirect hop, which it follows itself (the client's own redirect
 * following is off):
 *  1. **The URL rule** ([allowed]): https, a public DNS name, no credentials. An address on the
 *     user's own network written into a URL (`https://192.168.1.1/`, `https://nas.lan/`) is
 *     refused here, BEFORE any connection is opened, so not even a TCP or TLS hello reaches it.
 *     That goes for the first URL, a `Location`, and a segment URL an add-on's MPD names.
 *  2. **Behind a proxy, the name is resolved here first** ([viaProxy]). With a proxy OkHttp
 *     resolves the proxy's name, never the target's, so the client's [PublicOnlyDns] is not asked.
 *     A name whose own answers are all private is refused. What this cannot see: a proxy that
 *     resolves the name differently inside its own network. That one case is the proxy's.
 *  3. Then the request goes on, with `User-Agent: Stash/<version>` on every hop.
 *
 * A name that resolves privately on a direct connection is [PublicOnlyDns]'s to refuse (the
 * client's DNS); [PublicOnlyDns.connectionGuard] stays behind both as the last check on the peer.
 */
class AddonMediaGuard(
    /** The URL rule for this client: [AddonValidator.isAllowedUrl] with the base rules. */
    private val allowed: (HttpUrl) -> Boolean,
    /** [AddonValidator.isLocalAddressUrl]: the refusal was for an address on the user's own network. */
    private val isLocalAddress: (HttpUrl) -> Boolean,
    private val publicDns: Dns,
    private val proxy: Proxy?,
    private val proxySelector: ProxySelector?,
    private val userAgent: String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request().newBuilder().header("User-Agent", userAgent).build()
        var hops = 0
        while (true) {
            val url = request.url
            if (!allowed(url)) throw if (isLocalAddress(url)) PrivateAddressException() else RefusedAddonUrlException()
            // Throws UnknownHostException when the name has no public address.
            if (viaProxy(url, proxy, proxySelector)) publicDns.lookup(url.host)
            val response = chain.proceed(request)
            if (!response.isRedirect || (request.method != "GET" && request.method != "HEAD")) return response
            val next = response.header("Location")?.let { url.resolve(it) } ?: return response
            response.close()
            // Never the URL in a message: it can carry a signature or a token.
            if (++hops > MAX_REDIRECTS) throw ProtocolException("too many redirects from an add-on's media host")
            request = request.newBuilder().url(next).apply {
                if (next.scheme != url.scheme || next.host != url.host || next.port != url.port) {
                    removeHeader("Authorization")
                    removeHeader("Cookie")
                    removeHeader(Auth.HEADER_NAME)
                }
            }.build()
        }
    }

    companion object {
        /** As many hops as a CDN needs; OkHttp's own limit is 20. */
        const val MAX_REDIRECTS = 5

        /** True when a request for [url] would be handed to a proxy rather than connected directly. */
        fun viaProxy(url: HttpUrl, proxy: Proxy?, proxySelector: ProxySelector?): Boolean {
            if (proxy != null) return proxy.type() != Proxy.Type.DIRECT
            val selector = proxySelector ?: return false
            return try {
                selector.select(url.toUri()).any { it.type() != Proxy.Type.DIRECT }
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Step 2 alone, for a client that already checks every URL and hop itself (the add-on API
         * client): behind a proxy the target's name is resolved through [publicDns] first.
         */
        fun proxyGuard(publicDns: Dns, proxy: Proxy?, proxySelector: ProxySelector?): Interceptor = Interceptor { chain ->
            val url = chain.request().url
            if (viaProxy(url, proxy, proxySelector)) publicDns.lookup(url.host)
            chain.proceed(chain.request())
        }
    }
}

/** A URL an add-on handed over broke the URL rule (not https, credentials, an odd host): refused. Never names the URL. */
class RefusedAddonUrlException : IOException("a URL from an add-on was refused")
