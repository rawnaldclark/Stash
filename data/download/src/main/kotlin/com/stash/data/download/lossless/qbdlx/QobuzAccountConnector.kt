package com.stash.data.download.lossless.qbdlx

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connects a user's own Qobuz account to qbdlx: log in, and on success persist
 * the token together with the app_id/secret it was minted under so every later
 * request signs correctly. This is the "bring your own account" path — the one
 * credential guaranteed to serve real FLAC because it's the user's own paid
 * subscription, not a shared pool token that rotates and dies.
 *
 * Thin orchestration over [QobuzLoginClient] + [QbdlxCredentialStore]; the login
 * math and signing live in those, so this stays trivial.
 */
@Singleton
class QobuzAccountConnector @Inject constructor(
    private val loginClient: QobuzLoginClient,
    private val credentialStore: QbdlxCredentialStore,
) {
    /** Log in and, on success, persist the connected account. Returns the raw result for the UI. */
    suspend fun connect(email: String, password: String): QobuzLoginResult {
        val result = loginClient.login(email, password)
        if (result is QobuzLoginResult.Success) {
            credentialStore.setUserCredential(
                token = result.token,
                appId = result.appId,
                appSecret = result.appSecret,
                email = email.trim(),
            )
        }
        return result
    }

    /** Forget the connected account. */
    suspend fun disconnect() = credentialStore.clearUserCredential()

    /** The connected account's email, or null when none is connected. */
    suspend fun connectedEmail(): String? = credentialStore.connectedEmail()
}
