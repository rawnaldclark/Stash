package com.stash.core.auth.store

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.auth.crypto.TinkEncryptionManager
import com.stash.core.auth.model.ServiceToken
import com.stash.core.auth.model.UserInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Single DataStore instance scoped to the application process. */
private val Context.authDataStore by preferencesDataStore(name = "auth_tokens")

/**
 * Internal serializable representation that combines token data and basic user profile
 * into a single encrypted blob per service.
 */
@Serializable
private data class StoredToken(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpoch: Long,
    val scope: String = "",
    val userId: String = "",
    val displayName: String = "",
    val imageUrl: String = "",
)

/**
 * Encrypted token store backed by Preferences DataStore and Tink AES-256-GCM.
 *
 * Each streaming service (Spotify, YouTube Music) stores its token + user info as a single
 * encrypted JSON blob. Encryption keys are managed by [TinkEncryptionManager] and rooted in
 * Android Keystore.
 */
@Singleton
class EncryptedTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryption: TinkEncryptionManager,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val SPOTIFY = stringPreferencesKey("spotify_token")
        val YOUTUBE = stringPreferencesKey("youtube_token")
    }

    // ── Spotify ──────────────────────────────────────────────────────────

    /** Emits the current Spotify [ServiceToken], or null if not stored / decryption fails. */
    val spotifyToken: Flow<ServiceToken?> = context.authDataStore.data.map { prefs ->
        prefs[Keys.SPOTIFY]?.let { decryptToken(it) }
    }

    /** Emits the current Spotify [UserInfo], or null if not stored / decryption fails. */
    val spotifyUser: Flow<UserInfo?> = context.authDataStore.data.map { prefs ->
        prefs[Keys.SPOTIFY]?.let { decryptUser(it) }
    }

    /** Persists the Spotify token (and optionally user profile) as an encrypted blob. */
    suspend fun saveSpotifyToken(token: ServiceToken, user: UserInfo? = null) {
        val stored = token.toStoredToken(user)
        context.authDataStore.edit { it[Keys.SPOTIFY] = encryptStored(stored) }
    }

    /** Removes the stored Spotify credentials. */
    suspend fun clearSpotify() {
        context.authDataStore.edit { it.remove(Keys.SPOTIFY) }
    }

    // ── YouTube Music ────────────────────────────────────────────────────

    /** Emits the current YouTube Music [ServiceToken], or null if not stored / decryption fails. */
    val youTubeToken: Flow<ServiceToken?> = context.authDataStore.data.map { prefs ->
        prefs[Keys.YOUTUBE]?.let { decryptToken(it) }
    }

    /** Emits the current YouTube Music [UserInfo], or null if not stored / decryption fails. */
    val youTubeUser: Flow<UserInfo?> = context.authDataStore.data.map { prefs ->
        prefs[Keys.YOUTUBE]?.let { decryptUser(it) }
    }

    /** Persists the YouTube Music token (and optionally user profile) as an encrypted blob. */
    suspend fun saveYouTubeToken(token: ServiceToken, user: UserInfo? = null) {
        val stored = token.toStoredToken(user)
        context.authDataStore.edit { it[Keys.YOUTUBE] = encryptStored(stored) }
    }

    /** Removes the stored YouTube Music credentials. */
    suspend fun clearYouTube() {
        context.authDataStore.edit { it.remove(Keys.YOUTUBE) }
    }

    // ── Encryption helpers ───────────────────────────────────────────────

    /**
     * Serialises and encrypts a [StoredToken] into a Base64-encoded string suitable
     * for storage in Preferences DataStore.
     */
    private fun encryptStored(stored: StoredToken): String {
        val plaintext = json.encodeToString(StoredToken.serializer(), stored).toByteArray()
        val encrypted = encryption.encrypt(plaintext)
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    /**
     * Decrypts a Base64-encoded ciphertext back into a [ServiceToken].
     * Returns null when the refresh token is blank (considered invalid) or on any decryption error.
     */
    private fun decryptToken(encrypted: String): ServiceToken? = try {
        val stored = decryptStored(encrypted)
        if (stored.refreshToken.isEmpty()) null
        else ServiceToken(stored.accessToken, stored.refreshToken, stored.expiresAtEpoch, stored.scope)
    } catch (_: Exception) {
        null
    }

    /**
     * Decrypts a Base64-encoded ciphertext back into [UserInfo].
     * Returns null when no user ID is present or on any decryption error.
     */
    private fun decryptUser(encrypted: String): UserInfo? = try {
        val stored = decryptStored(encrypted)
        if (stored.userId.isEmpty()) null
        else UserInfo(stored.userId, stored.displayName, stored.imageUrl.ifEmpty { null })
    } catch (_: Exception) {
        null
    }

    /** Decrypts and deserialises a Base64-encoded ciphertext into a [StoredToken]. */
    private fun decryptStored(encrypted: String): StoredToken {
        val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
        val plaintext = encryption.decrypt(bytes)
        return json.decodeFromString(StoredToken.serializer(), String(plaintext))
    }

    /** Maps a [ServiceToken] + optional [UserInfo] into the internal [StoredToken] format. */
    private fun ServiceToken.toStoredToken(user: UserInfo?): StoredToken = StoredToken(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtEpoch = expiresAtEpoch,
        scope = scope,
        userId = user?.id ?: "",
        displayName = user?.displayName ?: "",
        imageUrl = user?.imageUrl ?: "",
    )
}
