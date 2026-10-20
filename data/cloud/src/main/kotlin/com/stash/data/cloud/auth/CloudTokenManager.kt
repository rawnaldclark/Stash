package com.stash.data.cloud.auth

import com.stash.core.auth.model.ServiceToken
import com.stash.core.auth.store.EncryptedTokenStore
import com.stash.core.model.CloudProvider
import com.stash.data.cloud.provider.CloudAuthRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Where cloud tokens are kept. Production: the Tink-encrypted [EncryptedTokenStore]. */
interface CloudTokenStore {
    suspend fun get(key: String): ServiceToken?
    suspend fun save(key: String, token: ServiceToken)
    suspend fun clear(key: String)
}

class EncryptedCloudTokenStore @Inject constructor(private val store: EncryptedTokenStore) : CloudTokenStore {
    override suspend fun get(key: String) = store.getCloudToken(key)
    override suspend fun save(key: String, token: ServiceToken) = store.saveCloudToken(key, token)
    override suspend fun clear(key: String) = store.clearCloudToken(key)
}

/**
 * Access tokens for connected cloud accounts, refreshed single-flight.
 *
 * One mutex per account: when the player, a download and the scan all find the token expired at
 * once, one of them refreshes and the others reuse its result. A caller that got a 401 passes
 * the token it used as `rejected`; the refresh only runs if the stored token is still that one
 * (someone else may have refreshed meanwhile).
 *
 * Google Drive has no refresh token on Android: its [ServiceToken.refreshToken] slot holds the
 * Google account name, and "refresh" is a silent re-authorization through Play services
 * ([com.stash.data.cloud.provider.gdrive.GoogleDriveAuth]).
 *
 * Dropbox and OneDrive rotate refresh tokens on some refreshes, so every refresh saves the whole
 * result. A refresh the provider refuses ([RefreshRejectedException]) forgets the dead sign-in
 * and surfaces as [CloudAuthRequiredException]: the account shows "Reconnect" (Settings reads
 * [hasToken]) and nothing retries in a loop. A refresh that merely failed (no network, provider
 * busy) throws its IOException and keeps the sign-in.
 */
@Singleton
class CloudTokenManager @Inject constructor(
    private val store: CloudTokenStore,
    private val auths: Map<CloudProvider, @JvmSuppressWildcards ProviderAuth>,
) {
    /** Testing seam: seconds since the epoch. */
    internal var nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }

    private val locks = ConcurrentHashMap<String, Mutex>()

    private val _changes = MutableStateFlow(0L)

    /**
     * Bumps when a sign-in is stored or forgotten. The account row is inserted before its first
     * token is saved (the key needs the row id), so anything showing "signed in" re-checks on this.
     */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    /** A usable access token for [accountId]; refreshes when it is expired, expiring or [rejected]. */
    suspend fun accessToken(provider: CloudProvider, accountId: Long, rejected: String? = null): String {
        val key = keyFor(provider, accountId)
        return locks.getOrPut(key) { Mutex() }.withLock {
            val current = store.get(key) ?: throw CloudAuthRequiredException(accountId, "not signed in")
            val stale = rejected != null && rejected == current.accessToken
            if (!stale && nowSeconds() < current.expiresAtEpoch - EXPIRY_MARGIN_SECONDS) {
                return@withLock current.accessToken
            }
            val auth = auths[provider] ?: throw CloudAuthRequiredException(accountId, "provider unavailable")
            val result = try {
                auth.refresh(current.refreshToken, current.accessToken)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RefreshRejectedException) {
                // Dead for good (revoked, expired, Google wants the user): forget it so the account
                // card offers "Reconnect" instead of looking signed in while every request fails.
                store.clear(key)
                _changes.value++
                throw CloudAuthRequiredException(accountId, e.message ?: "sign-in expired")
            }
            val token = ServiceToken(
                accessToken = result.accessToken,
                refreshToken = result.refreshToken ?: current.refreshToken,
                expiresAtEpoch = nowSeconds() + result.expiresInSeconds,
                scope = result.scope.ifEmpty { current.scope },
            )
            store.save(key, token)
            token.accessToken
        }
    }

    /** Stores the first tokens of a new sign-in. */
    suspend fun saveNew(provider: CloudProvider, accountId: Long, result: TokenResult) {
        val refresh = result.refreshToken
            ?: throw IllegalStateException("${provider.displayName} gave no refresh token; offline access was not granted")
        store.save(
            keyFor(provider, accountId),
            ServiceToken(result.accessToken, refresh, nowSeconds() + result.expiresInSeconds, result.scope),
        )
        _changes.value++
    }

    /**
     * When [accessToken] stops being handed out (its expiry minus [EXPIRY_MARGIN_SECONDS]), in
     * epoch milliseconds, for a link that carries it. Null when it is no longer the stored token
     * (refreshed or forgotten meanwhile): the caller must not cache it.
     */
    suspend fun usableUntilMs(provider: CloudProvider, accountId: Long, accessToken: String): Long? =
        store.get(keyFor(provider, accountId))
            ?.takeIf { it.accessToken == accessToken }
            ?.let { (it.expiresAtEpoch - EXPIRY_MARGIN_SECONDS) * 1000 }

    suspend fun hasToken(provider: CloudProvider, accountId: Long): Boolean = store.get(keyFor(provider, accountId)) != null

    /**
     * Disconnect: the token is forgotten on this phone. Under the account's lock, so a refresh
     * already in flight finishes first and can't save a token back after this. Returns the access
     * token it forgot (null when there was none), for a provider that caches it elsewhere too.
     */
    suspend fun forget(provider: CloudProvider, accountId: Long): String? {
        val key = keyFor(provider, accountId)
        val forgotten = locks.getOrPut(key) { Mutex() }.withLock {
            store.get(key)?.accessToken.also { store.clear(key) }
        }
        _changes.value++
        return forgotten
    }

    companion object {
        /** Refresh this long before expiry so a token never dies mid-request. */
        const val EXPIRY_MARGIN_SECONDS = 120L

        fun keyFor(provider: CloudProvider, accountId: Long): String = "${provider.id}_$accountId"
    }
}
