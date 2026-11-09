package com.stash.data.download.lossless.qbdlx

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * The ONE seam that turns a Qobuz track id into a signed FLAC URL: the user's own
 * connected Qobuz account, signed locally under its own app_id. Returns null when
 * no account is connected or it is cooling after a rejection (the source then
 * falls to the next rung, Tidal).
 *
 * A rejected login is surfaced as [QbdlxResolveResult.TokenDead] and handed to
 * [QbdlxCredentialStore.rejectLogin] — a real account cools for its dead-cooldown
 * (the next rung takes over, then it is re-probed), while a migrated pasted token,
 * which cannot be re-minted, is disconnected outright.
 * A non-auth failure on the connected account (network, 5xx) is not a dead
 * login: that resolve falls through to the next rung and the login is retried
 * next time.
 */
@Singleton
class QbdlxFileUrlRouter @Inject constructor(
    private val apiClient: QbdlxApiClient,
    private val credentialStore: QbdlxCredentialStore,
) {
    suspend fun getFileUrl(trackId: Long, formatId: Int): QbdlxResolveResult? {
        val login = credentialStore.loginCredential() ?: return null
        if (!credentialStore.loginLive()) return null
        val result: QbdlxResolveResult = try {
            apiClient.getFileUrl(trackId, formatId, login.token)
        } catch (e: QbdlxAuthException) {
            QbdlxResolveResult.TokenDead
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not a credential problem (5xx / network / bad body): don't mark dead, don't burn the
            // resolve — the next rung takes this one. The login is re-tried on the next resolve.
            // Name the status too: a 503, a 500 and a malformed body read identically otherwise.
            val detail = (e as? QbdlxApiException)?.status?.let { "${e.javaClass.simpleName} $it" }
                ?: e.javaClass.simpleName
            Log.w(TAG, "connected account call failed ($detail) — falling through to the next source")
            return null
        }
        if (result is QbdlxResolveResult.TokenDead) {
            Log.w(TAG, "connected account rejected")
            credentialStore.rejectLogin(login.token)
        } else {
            credentialStore.recordAlive(login.token)
        }
        return result
    }

    private companion object { const val TAG = "QbdlxFileUrlRouter" }
}
