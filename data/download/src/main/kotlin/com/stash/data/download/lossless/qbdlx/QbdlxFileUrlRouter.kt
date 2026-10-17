package com.stash.data.download.lossless.qbdlx

import android.util.Log
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.LosslessRelayClient
import com.stash.data.download.lossless.relay.RelayMint
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * The ONE seam that turns a Qobuz track id into a signed FLAC URL. Tries, in
 * order: the user's own connected account (signed locally — never spends relay
 * budget), the user's custom endpoint, then the relays from runtime config by
 * priority. Returns null when no path is available right now (the source then
 * falls to the next rung).
 *
 * BYO outcomes do not fall through on the resolve that discovers them: a
 * rejected login is surfaced as [QbdlxResolveResult.TokenDead] and handed to
 * [QbdlxCredentialStore.rejectLogin] — a real account cools for its
 * dead-cooldown (the relays take over, then it is re-probed), while a migrated
 * pasted token, which cannot be re-minted, is disconnected outright.
 * A non-auth failure on the connected account (network, 5xx) is not a dead
 * login: that resolve falls through to the relays and the login is retried
 * next time.
 */
@Singleton
class QbdlxFileUrlRouter @Inject constructor(
    private val apiClient: QbdlxApiClient,
    private val credentialStore: QbdlxCredentialStore,
    private val relayClient: LosslessRelayClient,
    private val config: LosslessConfigFetcher,
    private val prefs: LosslessSourcePreferences,
    private val quota: LosslessQuota,
) {
    suspend fun getFileUrl(trackId: Long, formatId: Int): QbdlxResolveResult? {
        val login = credentialStore.loginCredential()
        if (login != null && credentialStore.loginLive()) {
            val result: QbdlxResolveResult? = try {
                apiClient.getFileUrl(trackId, formatId, login.token)
            } catch (e: QbdlxAuthException) {
                QbdlxResolveResult.TokenDead
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not a credential problem (5xx / network / bad body): don't mark dead, don't burn the
                // resolve — the relays take this one. The login is re-tried on the next resolve.
                // Name the status too: a 503, a 500 and a malformed body read identically otherwise.
                val detail = (e as? QbdlxApiException)?.status?.let { "${e.javaClass.simpleName} $it" }
                    ?: e.javaClass.simpleName
                Log.w(TAG, "connected account call failed ($detail) — trying the relays")
                null
            }
            if (result != null) {
                if (result is QbdlxResolveResult.TokenDead) {
                    Log.w(TAG, "connected account rejected")
                    credentialStore.rejectLogin(login.token)
                } else {
                    credentialStore.recordAlive(login.token)
                }
                return result
            }
        }

        // The day's lossless allowance spent: the config relays (which keep the shared count)
        // are not asked at all until it resets. A user's own endpoint keeps no such count.
        val bases = buildList {
            prefs.customLosslessEndpointNow()?.let { add(it) }
            if (!quota.isSpent()) config.relays.value.forEach { add(it.base) }
        }.distinct()
        for (base in bases) {
            if (relayClient.isCooled(base)) continue
            when (val m = relayClient.mint(base, trackId, formatId)) {
                is RelayMint.Ok -> {
                    // Same downgrade rule as the direct path: a lossy format_id (<6) is not a match.
                    return if (m.formatId < 6) {
                        QbdlxResolveResult.RegionLocked
                    } else {
                        QbdlxResolveResult.Ok(m.url, "flac", m.bitDepth, m.sampleRateHz)
                    }
                }
                RelayMint.NoMatch -> return QbdlxResolveResult.RegionLocked
                RelayMint.Unavailable -> Unit // cooled by the client; try the next base
                // A download the relay asked to wait. The client noted it on the caller's
                // LosslessDownloadPurpose, which is how DownloadManager knows to defer rather
                // than go lossy; another base (a user's own endpoint) may still serve it.
                is RelayMint.Paced -> Unit
                // Every config relay shares the count: none of them would mint today.
                RelayMint.Spent -> return null
            }
        }
        return null
    }

    private companion object { const val TAG = "QbdlxFileUrlRouter" }
}
