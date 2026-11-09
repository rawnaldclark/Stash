package com.stash.data.download.lossless.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.stash.core.data.diagnostics.DiagnosticsContributor
import com.stash.core.data.prefs.StreamingPreference
import com.stash.data.download.lossless.LosslessAvailability
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.RoutingRow
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.prefs.StreamingQualityPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The diagnostics bundle's "Lossless" section — every fact a "FLAC never works
 * for me" report needs, so nobody has to be asked what the Settings › Audio &
 * Quality rows say or whether the Tidal relay is even reachable from their network:
 *
 *  - the mode and the quality settings (Download / Stream only, Lossless + tier,
 *    Data saver, stream on cellular)
 *  - the routing rows exactly as Settings renders them ([LosslessAvailability]),
 *    minus the account email
 *  - the applied signed config (`updated_at`, whether it carries a Tidal relay)
 *  - the network the phone is on (transport, metered, validated, VPN)
 *  - a live reachability probe of the config host, Stash's Tidal relay and the
 *    Qobuz CDN (the user's own account streams from it) — which is what separates
 *    "my network blocks Stash's hosts" from "this track has no lossless version",
 *    the two causes a VPN report can hide.
 *
 * Hosts only — never a full base or endpoint URL, which can carry a private
 * literal. Every block is fault-isolated so one failing read costs one line.
 */
@Singleton
class LosslessDiagnosticsContributor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val availability: LosslessAvailability,
    private val config: LosslessConfigFetcher,
    private val httpClient: OkHttpClient,
    private val losslessPrefs: LosslessSourcePreferences,
    private val streamingQuality: StreamingQualityPreferences,
    private val streamingPreference: StreamingPreference,
) : DiagnosticsContributor {

    override val title: String = "Lossless"

    /** Test seam: one reachability probe, answered as a display line. */
    internal var probe: suspend (url: String) -> String = ::probeUrl

    /** Test seam for the clock. */
    internal var nowMs: () -> Long = System::currentTimeMillis

    override suspend fun section(): String = buildString {
        appendLine(block("settings") { settingsBlock() })
        appendLine(block("routing") { routingBlock() })
        appendLine(block("config") { configBlock() })
        appendLine(block("network") { networkBlock() })
        append(block("reachability") { reachabilityBlock() })
    }

    private inline fun block(name: String, body: () -> String): String =
        runCatching(body).getOrElse { "[$name unavailable: ${it.message}]" }

    private suspend fun settingsBlock(): String {
        val download = !streamingPreference.current()
        val lossless = losslessPrefs.enabledNow()
        return buildString {
            // The mode's on-screen names since 2026-11-01 (Download / Stream only before).
            appendLine("Mode:               " + if (download) "Offline (sync writes files; only downloads play)" else "Online (sync writes nothing; everything plays)")
            appendLine("Lossless streaming: " + onOff(lossless))
            appendLine("Download quality:   " + when (losslessPrefs.downloadModeNow()) {
                com.stash.core.model.DownloadMode.LOSSLESS -> "Lossless · ${losslessPrefs.qualityTierNow().displayLabel}"
                com.stash.core.model.DownloadMode.AAC -> "AAC 320"
                com.stash.core.model.DownloadMode.OPUS -> "Opus"
            } + " · lossy fallback " + onOff(losslessPrefs.youtubeFallbackEnabledNow()))
            appendLine("Data saver:         " + onOff(streamingQuality.saveDataNow()) + " (metered networks only)")
            append("Stream on cellular: " + onOff(streamingPreference.streamOnCellular.first()))
        }
    }

    private suspend fun routingBlock(): String =
        "Routing (as Settings › Audio & Quality shows it):\n" +
            availability.routingRows.first().joinToString("\n") { row -> "  ${row.label}: ${safeDetail(row)}" }

    /** The Qobuz row's detail is the account email when known — print the state instead. */
    private fun safeDetail(row: RoutingRow): String =
        if (row.id == "qobuz") row.state.name.lowercase().replace('_', ' ') else row.detail

    private fun configBlock(): String {
        val updatedAt = config.updatedAt.value
        val tidal = config.tidalRelay.value
        return buildString {
            appendLine("Lossless config:    " + if (updatedAt > 0) "updated_at=${iso(updatedAt * 1000)}" else "none loaded")
            append("Tidal relay:        " + if (tidal == null) "not in the config" else "configured (${host(tidal.base)})")
        }
    }

    private fun networkBlock(): String {
        val caps = activeCapabilities() ?: return "Network:            none (offline)"
        val transports = listOf(
            NetworkCapabilities.TRANSPORT_WIFI to "wifi",
            NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
            NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
            NetworkCapabilities.TRANSPORT_VPN to "vpn",
        ).filter { caps.hasTransport(it.first) }.map { it.second }
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return "Network:            ${transports.ifEmpty { listOf("unknown") }.joinToString("+")}" +
            " · ${if (metered) "metered" else "unmetered"} · ${if (validated) "validated" else "not validated"}"
    }

    private suspend fun reachabilityBlock(): String {
        if (activeCapabilities() == null) return "Reachability:       offline — probes skipped"
        val targets = buildList {
            if (config.enabled) add("config host" to config.configUrl)
            // The base is never printed: it can carry the relay's access token as a path prefix.
            config.tidalRelay.value?.let { add("Tidal relay ${host(it.base)}" to "${it.base}/health") }
            add("Qobuz CDN" to QOBUZ_CDN)
        }
        if (targets.isEmpty()) return "Reachability:       nothing configured to probe"
        val results = coroutineScope {
            targets.map { (label, url) ->
                async { label to (withTimeoutOrNull(PROBE_BUDGET_MS) { probe(url) } ?: "no answer within ${PROBE_BUDGET_MS / 1000}s") }
            }.map { it.await() }
        }
        return "Reachability (any HTTP answer counts, ${PROBE_TIMEOUT_S}s each):\n" +
            results.joinToString("\n") { (label, line) -> "  $label: $line" }
    }

    /**
     * One GET on a short-timeout client; the status line alone answers "can this
     * phone reach it" and the body is never read. GET rather than HEAD because
     * the Workers answer HEAD with 405 — still "reachable", but a 405 reads like
     * a failure to whoever is triaging the report.
     */
    private suspend fun probeUrl(url: String): String = withContext(Dispatchers.IO) {
        val parsed = url.toHttpUrlOrNull() ?: return@withContext "invalid url"
        val client = httpClient.newBuilder()
            .connectTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
        val started = nowMs()
        try {
            client.newCall(Request.Builder().url(parsed).get().build()).execute().use { r ->
                "HTTP ${r.code} in ${nowMs() - started} ms"
            }
        } catch (e: IOException) {
            "unreachable (${e.javaClass.simpleName}) after ${nowMs() - started} ms"
        }
    }

    private fun activeCapabilities(): NetworkCapabilities? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        return cm.getNetworkCapabilities(network)
    }

    private fun host(base: String): String = base.toHttpUrlOrNull()?.host ?: "?"
    private fun onOff(v: Boolean) = if (v) "on" else "off"
    private fun iso(ms: Long): String = runCatching { Instant.ofEpochMilli(ms).toString() }.getOrDefault("$ms")

    private companion object {
        const val PROBE_TIMEOUT_S = 3L
        const val PROBE_BUDGET_MS = 4_000L
        /** The CDN Qobuz file URLs point at; a HEAD on the root answers reachability, whatever the status. */
        const val QOBUZ_CDN = "https://streaming-qobuz-std.akamaized.net/"
    }
}
