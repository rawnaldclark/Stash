package com.stash.data.cloud.auth

import android.content.Context
import android.util.Log
import com.stash.core.data.db.dao.CloudDao
import com.stash.core.data.db.entity.CloudAccountEntity
import com.stash.core.model.CloudProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** How a sign-in ended, for Settings to show. */
sealed interface CloudAuthEvent {
    data class Connected(val provider: CloudProvider, val accountId: Long, val displayName: String, val isNew: Boolean) : CloudAuthEvent
    data class Failed(val provider: CloudProvider?, val message: String) : CloudAuthEvent
}

/**
 * Runs a cloud sign-in end to end: [begin] makes the PKCE verifier and state and returns the
 * browser URL; the redirect comes back to [CloudAuthRedirectActivity], which hands it to
 * [handleRedirect]; that checks the state, exchanges the code, looks up who signed in, creates
 * (or re-links) the `cloud_accounts` row and stores the tokens encrypted.
 *
 * The pending attempt (provider, state, verifier) is kept in private app preferences, not only
 * in memory: Android may kill Stash while the browser is in front. It is single-use and
 * cleared the moment a redirect arrives, whether it matches or not.
 */
@Singleton
class CloudAuthCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auths: Map<CloudProvider, @JvmSuppressWildcards ProviderAuth>,
    private val tokens: CloudTokenManager,
    private val cloudDao: CloudDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val _events = MutableSharedFlow<CloudAuthEvent>(extraBufferCapacity = 4)

    /** Sign-in results, for the Settings screen that started the sign-in (its ViewModel outlives the browser). */
    val events: SharedFlow<CloudAuthEvent> = _events.asSharedFlow()

    /** Starts a sign-in with [provider]; returns the URL to open in a Custom Tab. */
    fun begin(provider: CloudProvider): String {
        val auth = auths[provider] ?: error("${provider.displayName} isn't available in this build")
        val verifier = Pkce.newVerifier()
        val state = Pkce.newState()
        prefs.edit()
            .putString(KEY_PROVIDER, provider.id)
            .putString(KEY_STATE, state)
            .putString(KEY_VERIFIER, verifier)
            .putLong(KEY_STARTED, System.currentTimeMillis())
            .apply()
        return auth.authorizeUrl(state, Pkce.challenge(verifier))
    }

    /** True when [uri] is one of our providers' redirects (the activity ignores anything else). */
    fun isRedirect(uri: String): Boolean = auths.values.any { it.parseCallback(uri) != CallbackResult.NotMine }

    /** Finishes the sign-in [uri] belongs to, off the main thread; the result goes to [events]. */
    fun handleRedirect(uri: String) {
        scope.launch { _events.emit(complete(uri)) }
    }

    internal suspend fun complete(uri: String): CloudAuthEvent {
        val pendingProvider = CloudProvider.fromId(prefs.getString(KEY_PROVIDER, null))
        val pendingState = prefs.getString(KEY_STATE, null)
        val verifier = prefs.getString(KEY_VERIFIER, null)
        val started = prefs.getLong(KEY_STARTED, 0)

        val auth = pendingProvider?.let { auths[it] }
        if (auth == null || pendingState == null || verifier == null || System.currentTimeMillis() - started > MAX_AGE_MS) {
            prefs.edit().clear().apply()
            return CloudAuthEvent.Failed(pendingProvider, "That sign-in has expired. Try connecting again.")
        }
        // Only a redirect carrying this sign-in's state ends it. Any app can open the redirect
        // activity with a made-up URI; that must not cancel (or complete) the user's sign-in.
        val result = auth.parseCallback(uri)
        val state = when (result) {
            is CallbackResult.Code -> result.state
            is CallbackResult.Denied -> result.state
            CallbackResult.NotMine -> null
        }
        if (state != pendingState) {
            return CloudAuthEvent.Failed(pendingProvider, "Sign-in didn't match. Try connecting again.")
        }
        prefs.edit().clear().apply()
        return when (result) {
            is CallbackResult.Code -> connect(pendingProvider, auth, result.code, verifier)
            is CallbackResult.Denied -> CloudAuthEvent.Failed(pendingProvider, result.message)
            CallbackResult.NotMine -> CloudAuthEvent.Failed(pendingProvider, "Unexpected sign-in response.")
        }
    }

    private suspend fun connect(provider: CloudProvider, auth: ProviderAuth, code: String, verifier: String): CloudAuthEvent =
        try {
            val token = auth.exchangeCode(code, verifier)
            val who = auth.identity(token.accessToken)
            val existing = cloudDao.findAccount(provider.id, who.remoteId)
            val accountId = if (existing != null) {
                cloudDao.updateAccount(existing.copy(displayName = who.displayName, email = who.email))
                existing.id
            } else {
                cloudDao.insertAccount(
                    CloudAccountEntity(
                        provider = provider.id, remoteId = who.remoteId, displayName = who.displayName,
                        email = who.email, connectedAt = System.currentTimeMillis(),
                    ),
                )
            }
            tokens.saveNew(provider, accountId, token)
            CloudAuthEvent.Connected(provider, accountId, who.displayName, isNew = existing == null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never log the exception message: it may echo a token endpoint response.
            Log.w(TAG, "${provider.id} sign-in failed: ${e.javaClass.simpleName}")
            CloudAuthEvent.Failed(provider, "Couldn't connect ${provider.displayName}. Check your connection and try again.")
        }

    private companion object {
        const val TAG = "CloudAuth"
        const val PREFS = "cloud_auth_pending"
        const val KEY_PROVIDER = "provider"
        const val KEY_STATE = "state"
        const val KEY_VERIFIER = "verifier"
        const val KEY_STARTED = "started"

        /** A sign-in left in the browser longer than this is stale. */
        const val MAX_AGE_MS = 15 * 60_000L
    }
}
