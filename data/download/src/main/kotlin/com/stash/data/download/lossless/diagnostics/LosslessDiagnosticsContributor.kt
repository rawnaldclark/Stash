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
import com.stash.data.download.lossless.relay.LosslessRelayClient
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
import okhttp3.Request

/**
 * The diagnostics bundle's "Lossless" section — every fact a "FLAC never works
 * for me" report needs, so nobody has to be asked what the Settings › Audio &
 * Quality rows say or whether the relay is even reachable from their network:
 *
 *  - the mode and the quality settings (Download / Stream only, Lossless + tier,
 *    Save Data, stream on cellular)
 *  - the routing rows exactly as Settings renders them ([LosslessAvailability]),
 *    minus the account email
 *  - the applied relay config (how many relays, `updated_at`) and, per relay,
 *    whether it is cooled right now
 *  - the last relay answers this process got ([LosslessRelayClient.recentOutcomes])
 *  - the network the phone is on (transport, metered, validated, VPN)
 *  - a live reachability probe of the config host, each relay and the Qobuz CDN —
 *    which is what separates "my network blocks Stash's hosts" from "Qobuz
 *    refused this track", the two causes a VPN report can hide.
 *
 * Hosts only — never a full base or endpoint URL, which can carry a private
 * literal. Every block is fault-isolated so one failing read costs one line.
 */
@Singleton
class LosslessDiagnosticsContributor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val availability: LosslessAvailability,
    private val config: LosslessConfigFetcher,
    private val relayClient: LosslessRelayClient,
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
        appendLine(block("relays") { relaysBlock() })
        appendLine(block("recent relay answers") { recentAnswersBlock() })
        appendLine(block("network") { networkBlock() })
        append(block("reachability") { reachabilityBlock() })
    }

    private inline fun block(name: String, body: () -> String): String =
        runCatching(body).getOrElse { "[$name unavailable: ${it.message}]" }

    private suspend fun settingsBlock(): String {
        val download = !streamingPreference.current()
        val lossless = losslessPrefs.enabledNow()
        return buildString {
            appendLine("Mode:               " + if (download) "Download (sync writes files)" else "Stream only (sync writes nothing)")
            appendLine("Lossless streaming: " + onOff(lossless))
            appendLine("Download quality:   " + when (losslessPrefs.downloadModeNow()) {
                com.stash.core.model.DownloadMode.LOSSLESS -> "Lossless · ${losslessPrefs.qualityTierNow().displayLabel}"
                com.stash.core.model.DownloadMode.AAC -> "AAC 320"
                com.stash.core.model.DownloadMode.OPUS -> "Opus"
            } + " · lossy fallback " + onOff(losslessPrefs.youtubeFallbackEnabledNow()))
            appendLine("Save Data:          " + onOff(streamingQuality.saveDataNow()))
            append("Stream on cellular: " + onOff(streamingPreference.streamOnCellular.first()))
        }
    }

    private suspend fun routingBlock(): String =
        "Routing (as Settings › Audio & Quality shows it):\n" +
            availability.routingRows.first().joinToString("\n") { row -> "  ${row.label}: ${safeDetail(row)}" }

    /** The Qobuz row's detail is the account email when known — print the state instead. */
    private fun safeDetail(row: RoutingRow): String =
        if (row.id == "qobuz") row.state.name.lowercase().replace('_', ' ') else row.detail

    private suspend fun relaysBlock(): String {
        val relays = config.relays.value
        val updatedAt = config.updatedAt.value
        val custom = losslessPrefs.customLosslessEndpointNow()
        return buildString {
            appendLine(
                "Relay config:       ${relays.size} relay(s)" +
                    if (updatedAt > 0) ", updated_at=${iso(updatedAt * 1000)}" else ", none loaded",
            )
            relays.forEach { r ->
                appendLine("  ${host(r.base)}: " + if (relayClient.isCooled(r.base)) "cooled (recent failure)" else "ok")
            }
            append("Custom endpoint:    " + if (custom == null) "not set" else if (relayClient.isCooled(custom)) "set, cooled" else "set, ok")
        }
    }

    private fun recentAnswersBlock(): String {
        val lines = relayClient.recentOutcomes()
        return if (lines.isEmpty()) "Recent relay answers: none this session"
        else "Recent relay answers (oldest first):\n" + lines.joinToString("\n") { "  $it" }
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
            config.relays.value.forEach { add("relay ${host(it.base)}" to "${it.base}/v1/status") }
            losslessPrefs.customLosslessEndpointNow()?.let { add("custom endpoint" to "$it/v1/status") }
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
        val client = relayClient.httpClient.newBuilder()
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
