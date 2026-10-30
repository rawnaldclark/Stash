package com.stash.core.auth.soundcloud

import com.stash.core.auth.store.EncryptedTokenStore
import com.stash.data.soundcloud.SoundCloudClient
import com.stash.data.soundcloud.SoundCloudLink
import com.stash.data.soundcloud.SoundCloudLinks
import com.stash.data.soundcloud.SoundCloudUser
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Latest connection intent wins; disconnect also invalidates pending network validation. */
@Singleton
class SoundCloudAccountManager @Inject constructor(
    private val store: EncryptedTokenStore,
    private val client: SoundCloudClient,
) {
    private val lock = Mutex()
    private var generation = 0L
    val session = store.soundCloudSession.flowOn(Dispatchers.IO)

    suspend fun connect(token: String): Boolean = connectValidated(token) { client.validateToken(token) }

    suspend fun connectPublicProfile(url: String): Boolean = connectValidated(null) {
        val link = SoundCloudLinks.parse(url) ?: error("Invalid SoundCloud profile")
        val expanded = if (link.kind == SoundCloudLink.Kind.SHORT) client.expand(link.url) else link
        require(expanded.kind in setOf(SoundCloudLink.Kind.PROFILE, SoundCloudLink.Kind.LIKES))
        client.user(client.resolve(expanded))
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        lock.withLock {
            generation++
            store.clearSoundCloud()
        }
    }

    /** A preflight check only: snapshot commit must serialize with account changes as well. */
    suspend fun isCurrent(connectionId: String): Boolean = session.first()?.connectionId == connectionId

    /** Serialize a short database commit with connect/disconnect. Fetch outside this block. */
    suspend fun <T> withCurrentSession(connectionId: String, commit: suspend () -> T): T? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (session.first()?.connectionId != connectionId) return@withLock null
            commit()
        }
    }

    private suspend fun connectValidated(token: String?, validate: suspend () -> SoundCloudUser): Boolean =
        withContext(Dispatchers.IO) {
            val intent = lock.withLock { ++generation }
            val user = try {
                validate()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Preserve a working account on validation/network failure. Never log the token.
                return@withContext false
            }
            lock.withLock {
                if (intent != generation) return@withLock false
                store.saveSoundCloudSession(SoundCloudSession(
                    userId = user.id, displayName = user.name, avatarUrl = user.avatarUrl,
                    oauthToken = token, connectionId = UUID.randomUUID().toString(),
                ))
                true
            }
        }
}
