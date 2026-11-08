package com.stash.core.auth.store

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.auth.crypto.TinkEncryptionManager
import com.stash.core.auth.model.ServiceToken
import com.stash.core.auth.model.UserInfo
import com.stash.core.auth.spotify.SpotifyProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Single DataStore instance scoped to the application process. */
private val Context.authDataStore by preferencesDataStore(
    name = "auth_tokens",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Internal serializable representation that combines token data and basic user profile
 * into a single encrypted blob per service.
 */
@Serializable
internal data class StoredToken(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpoch: Long,
    val scope: String = "",
    val userId: String = "",
    val displayName: String = "",
    val imageUrl: String = "",
) {
    /**
     * This stored sign-in with [refreshed]'s new access token, keeping its user, or null when it
     * isn't the sign-in that was refreshed (its cookie isn't [spDcCookie]). A token endpoint can
     * take its time: a Disconnect or a new sign-in landing meanwhile has to win.
     */
    fun afterRefresh(spDcCookie: String, refreshed: ServiceToken): StoredToken? =
        if (refreshToken != spDcCookie) {
            null
        } else {
            copy(
                accessToken = refreshed.accessToken,
                refreshToken = refreshed.refreshToken,
                expiresAtEpoch = refreshed.expiresAtEpoch,
                scope = refreshed.scope,
            )
        }
}

/** Stored form of [SpotifyProfile]: which account the stored Spotify sign-in belongs to. */
@Serializable
private data class StoredSpotifyAccount(
    val userId: String,
    val displayName: String = "",
)

/** The stored Spotify sign-in and whose account it is, both from one read of the store. */
data class StoredSpotifySignIn(
    /** Null when signed out. */
    val token: ServiceToken?,
    /** Null when it isn't known: signed out, or signed in before Stash recorded it. */
    val account: SpotifyProfile?,
)

/**
 * Encrypted token store backed by Preferences DataStore and Tink AES-256-GCM.
 *
 * Each streaming service (Spotify, YouTube Music) stores its token + user info as a single
 * encrypted JSON blob. Encryption keys are managed by [TinkEncryptionManager] and rooted in
 * Android Keystore.
 *
 * Accounts that can be several have one blob per account, keyed by their database row id: cloud
 * storage accounts (`cloud_token_<provider>_<rowId>`) and extra Spotify accounts
 * (`spotify_extra_<rowId>`). The main Spotify account keeps its one `spotify_token` blob.
 */
@Singleton
class EncryptedTokenStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val encryption: TinkEncryptionManager,
) {
    /** The app's one `auth_tokens` store. The internal constructor takes any store, for JVM tests. */
    @Inject constructor(
        @ApplicationContext context: Context,
        encryption: TinkEncryptionManager,
    ) : this(context.authDataStore, encryption)

    private val json = Json { ignoreUnknownKeys = true }

    internal companion object {
        const val CLOUD_PREFIX = "cloud_token_"
        val CLOUD_ACCOUNT_KEY = Regex("[a-z]+_[0-9]+")

        /** One blob per extra Spotify account: `spotify_extra_<rowId>` (plan 2026-11-04 §2.2). */
        const val SPOTIFY_EXTRA_PREFIX = "spotify_extra_"

        /**
         * Whether [name] is a per-account blob's key, which a keyset reset drops along with the
         * fixed keys: every cloud account's and every extra Spotify account's. A new kind of
         * per-account key must be added here, or its blobs outlive the keyset that sealed them.
         */
        fun isPerAccountKey(name: String): Boolean =
            name.startsWith(CLOUD_PREFIX) || name.startsWith(SPOTIFY_EXTRA_PREFIX)
    }

    private object Keys {
        val SPOTIFY = stringPreferencesKey("spotify_token")
        /** Whose the [SPOTIFY] sign-in is; written with it, cleared with it. */
        val SPOTIFY_ACCOUNT = stringPreferencesKey("spotify_account")
        val YOUTUBE = stringPreferencesKey("youtube_token")
        val DISCORD = stringPreferencesKey("discord_token")
        val SOUNDCLOUD = stringPreferencesKey("soundcloud_session")
    }

    // ── Spotify ──────────────────────────────────────────────────────────

    /** Emits the current Spotify [ServiceToken], or null if not stored / decryption fails. */
    val spotifyToken: Flow<ServiceToken?> = dataStore.data.map { prefs ->
        prefs[Keys.SPOTIFY]?.let { decryptToken(it) }
    }

    /** Emits the current Spotify [UserInfo], or null if not stored / decryption fails. */
    val spotifyUser: Flow<UserInfo?> = dataStore.data.map { prefs ->
        prefs[Keys.SPOTIFY]?.let { decryptUser(it) }
    }

    /**
     * Saves a refreshed token, keeping the stored user, in ONE write and only while the stored
     * sign-in is still [spDcCookie], the cookie it was refreshed from. Otherwise a Disconnect or a
     * new sign-in made during the refresh would be undone, and the old account's token put back
     * under the new account's record. Returns true when it was written.
     */
    suspend fun saveRefreshedSpotifyToken(spDcCookie: String, refreshed: ServiceToken): Boolean {
        var written = false
        editSweepingAfterKeysetReset { prefs ->
            val current = prefs[Keys.SPOTIFY]?.let { runCatching { decryptStored(it) }.getOrNull() }
            current?.afterRefresh(spDcCookie, refreshed)?.let {
                prefs[Keys.SPOTIFY] = encryptStored(it)
                written = true
            }
        }
        return written
    }

    /** The stored Spotify sign-in and whose account it is, read together. */
    suspend fun readSpotifySignIn(): StoredSpotifySignIn {
        val prefs = dataStore.data.first()
        return StoredSpotifySignIn(
            token = prefs[Keys.SPOTIFY]?.let { decryptToken(it) },
            account = prefs[Keys.SPOTIFY_ACCOUNT]?.let { decryptAccount(it) },
        )
    }

    /**
     * Saves a new Spotify sign-in: the token, its profile, and whose account it is, in ONE write,
     * so the recorded account can never belong to a different sign-in than the stored token.
     * A null [account] (Spotify didn't say) removes any recorded one: it belonged to the
     * sign-in this replaces.
     */
    suspend fun saveSpotifyConnection(token: ServiceToken, user: UserInfo, account: SpotifyProfile?) {
        val tokenBlob = encryptStored(token.toStoredToken(user))
        val accountBlob = account?.let { encryptAccount(it) }
        editSweepingAfterKeysetReset { prefs ->
            prefs[Keys.SPOTIFY] = tokenBlob
            if (accountBlob != null) prefs[Keys.SPOTIFY_ACCOUNT] = accountBlob else prefs.remove(Keys.SPOTIFY_ACCOUNT)
        }
    }

    /**
     * Records whose the stored Spotify sign-in is, but only while that sign-in is still
     * [spDcCookie] (its long-lived cookie): a sign-out or a new sign-in that landed while the
     * account was being looked up wins, and nothing is written. Returns true when it was written.
     */
    suspend fun saveSpotifyAccountFor(spDcCookie: String, account: SpotifyProfile): Boolean {
        val accountBlob = encryptAccount(account)
        var written = false
        editSweepingAfterKeysetReset { prefs ->
            val current = prefs[Keys.SPOTIFY]?.let { decryptToken(it) }
            if (current != null && current.refreshToken == spDcCookie) {
                prefs[Keys.SPOTIFY_ACCOUNT] = accountBlob
                written = true
            }
        }
        return written
    }

    /** Removes the stored Spotify credentials, and with them whose account they were. Extra accounts' stay. */
    suspend fun clearSpotify() {
        dataStore.edit {
            it.remove(Keys.SPOTIFY)
            it.remove(Keys.SPOTIFY_ACCOUNT)
        }
    }

    // ── Extra Spotify accounts (plan 2026-11-04 §2.2) ────────────────────

    /**
     * The extra Spotify account [rowId]'s stored sign-in and whose account it is, from one read
     * of its one blob (`spotify_extra_<rowId>`, the same [StoredToken] shape as every other
     * blob). Both null when there is none: never added, cleared, or unreadable after a keyset
     * reset. Never falls back to the main account's blob.
     *
     * @param rowId The account's `spotify_accounts.id`; positive.
     */
    suspend fun readSpotifyExtraSignIn(rowId: Long): StoredSpotifySignIn {
        val stored = dataStore.data.first()[spotifyExtraKey(rowId)]
            ?.let { runCatching { decryptStored(it) }.getOrNull() }
            ?.takeIf { it.refreshToken.isNotEmpty() }
            ?: return StoredSpotifySignIn(token = null, account = null)
        return StoredSpotifySignIn(
            token = ServiceToken(stored.accessToken, stored.refreshToken, stored.expiresAtEpoch, stored.scope),
            account = stored.userId.takeIf { it.isNotBlank() }
                ?.let { SpotifyProfile(userId = it, displayName = stored.displayName.ifBlank { it }) },
        )
    }

    /**
     * Saves the extra Spotify account [rowId]'s sign-in: its token and whose account it is, in
     * one blob, so the account can never belong to a different sign-in than the token.
     */
    suspend fun saveSpotifyExtra(rowId: Long, token: ServiceToken, account: SpotifyProfile) {
        val user = UserInfo(id = account.userId, displayName = account.displayName)
        saveBlob(spotifyExtraKey(rowId), encryptStored(token.toStoredToken(user)))
    }

    /**
     * Saves a refreshed token of the extra account [rowId], keeping its account, but only while
     * its stored sign-in is still [spDcCookie], the cookie it was refreshed from: a removal or a
     * new sign-in made during the refresh wins (as [saveRefreshedSpotifyToken] for the main).
     * Returns true when it was written.
     */
    suspend fun saveRefreshedSpotifyExtraToken(rowId: Long, spDcCookie: String, refreshed: ServiceToken): Boolean {
        val key = spotifyExtraKey(rowId)
        var written = false
        editSweepingAfterKeysetReset { prefs ->
            val current = prefs[key]?.let { runCatching { decryptStored(it) }.getOrNull() }
            current?.afterRefresh(spDcCookie, refreshed)?.let {
                prefs[key] = encryptStored(it)
                written = true
            }
        }
        return written
    }

    /** Forgets the extra Spotify account [rowId]'s sign-in. The main account's and other extras' stay. */
    suspend fun clearSpotifyExtra(rowId: Long) {
        dataStore.edit { it.remove(spotifyExtraKey(rowId)) }
    }

    // ── YouTube Music ────────────────────────────────────────────────────

    /** Emits the current YouTube Music [ServiceToken], or null if not stored / decryption fails. */
    val youTubeToken: Flow<ServiceToken?> = dataStore.data.map { prefs ->
        prefs[Keys.YOUTUBE]?.let { decryptToken(it) }
    }

    /** Emits the current YouTube Music [UserInfo], or null if not stored / decryption fails. */
    val youTubeUser: Flow<UserInfo?> = dataStore.data.map { prefs ->
        prefs[Keys.YOUTUBE]?.let { decryptUser(it) }
    }

    /** Persists the YouTube Music token (and optionally user profile) as an encrypted blob. */
    suspend fun saveYouTubeToken(token: ServiceToken, user: UserInfo? = null) {
        val stored = token.toStoredToken(user)
        saveBlob(Keys.YOUTUBE, encryptStored(stored))
    }

    /** Removes the stored YouTube Music credentials. */
    suspend fun clearYouTube() {
        dataStore.edit { it.remove(Keys.YOUTUBE) }
    }

    // ── Discord ──────────────────────────────────────────────────────────

    /** Emits the current Discord [ServiceToken], or null if not stored / decryption fails. */
    val discordToken: Flow<ServiceToken?> = dataStore.data.map { prefs ->
        prefs[Keys.DISCORD]?.let { decryptToken(it) }
    }

    /** Emits the current Discord [UserInfo], or null if not stored / decryption fails. */
    val discordUser: Flow<UserInfo?> = dataStore.data.map { prefs ->
        prefs[Keys.DISCORD]?.let { decryptUser(it) }
    }

    /** Persists the Discord token (and optionally user profile) as an encrypted blob. */
    suspend fun saveDiscordToken(token: ServiceToken, user: UserInfo? = null) {
        val stored = token.toStoredToken(user)
        saveBlob(Keys.DISCORD, encryptStored(stored))
    }

    /** Removes the stored Discord credentials. */
    suspend fun clearDiscord() {
        dataStore.edit { it.remove(Keys.DISCORD) }
    }

    // ── Cloud storage accounts (OneDrive, …) ─────────────────────────────

    /**
     * The stored OAuth token of one cloud storage account, or null when there is none (never
     * connected, disconnected, or unreadable after a keyset reset). [accountKey] is the
     * provider plus Stash's account row id ("onedrive_12"): one blob per account, so two
     * connected accounts never share a token.
     */
    suspend fun getCloudToken(accountKey: String): ServiceToken? =
        dataStore.data.first()[cloudKey(accountKey)]?.let { decryptToken(it) }

    /** Persists a cloud account's token (refresh tokens rotate, so every refresh saves again). */
    suspend fun saveCloudToken(accountKey: String, token: ServiceToken) {
        saveBlob(cloudKey(accountKey), encryptStored(token.toStoredToken(null)))
    }

    /** Forgets a cloud account's token (disconnect). */
    suspend fun clearCloudToken(accountKey: String) {
        dataStore.edit { it.remove(cloudKey(accountKey)) }
    }

    val soundCloudSession: Flow<com.stash.core.auth.soundcloud.SoundCloudSession?> = dataStore.data.map { prefs ->
        prefs[Keys.SOUNDCLOUD]?.let { encrypted ->
            try {
                val bytes = encryption.decrypt(Base64.decode(encrypted, Base64.NO_WRAP))
                json.decodeFromString<com.stash.core.auth.soundcloud.SoundCloudSession>(String(bytes, Charsets.UTF_8))
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun saveSoundCloudSession(session: com.stash.core.auth.soundcloud.SoundCloudSession) {
        val bytes = json.encodeToString(com.stash.core.auth.soundcloud.SoundCloudSession.serializer(), session).toByteArray(Charsets.UTF_8)
        saveBlob(Keys.SOUNDCLOUD, Base64.encodeToString(encryption.encrypt(bytes), Base64.NO_WRAP))
    }

    suspend fun clearSoundCloud() {
        dataStore.edit { it.remove(Keys.SOUNDCLOUD) }
    }

    private fun cloudKey(accountKey: String): Preferences.Key<String> {
        require(accountKey.matches(CLOUD_ACCOUNT_KEY)) { "bad cloud account key" }
        return stringPreferencesKey(CLOUD_PREFIX + accountKey)
    }

    private fun spotifyExtraKey(rowId: Long): Preferences.Key<String> {
        require(rowId > 0) { "bad extra Spotify account row id" }
        return stringPreferencesKey(SPOTIFY_EXTRA_PREFIX + rowId)
    }

    /**
     * Writes one service's blob. If the keyset was just replaced because the
     * old one could no longer be opened (see [TinkEncryptionManager]), every
     * other stored blob is sealed under a key that no longer exists: they are
     * dropped here rather than left on disk, unreadable, for ever.
     */
    private suspend fun saveBlob(key: Preferences.Key<String>, blob: String) {
        editSweepingAfterKeysetReset { prefs -> prefs[key] = blob }
    }

    /** One DataStore edit that first drops every blob sealed under a lost keyset (see [saveBlob]). */
    private suspend fun editSweepingAfterKeysetReset(write: (MutablePreferences) -> Unit) {
        dataStore.edit { prefs ->
            if (encryption.consumeKeysetReset()) {
                prefs.remove(Keys.SPOTIFY)
                prefs.remove(Keys.SPOTIFY_ACCOUNT)
                prefs.remove(Keys.YOUTUBE)
                prefs.remove(Keys.DISCORD)
                prefs.remove(Keys.SOUNDCLOUD)
                prefs.asMap().keys.map { it.name }.filter(::isPerAccountKey)
                    .forEach { prefs.remove(stringPreferencesKey(it)) }
            }
            write(prefs)
        }
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

    private fun encryptAccount(account: SpotifyProfile): String {
        val stored = StoredSpotifyAccount(userId = account.userId, displayName = account.displayName)
        val plaintext = json.encodeToString(StoredSpotifyAccount.serializer(), stored).toByteArray()
        return Base64.encodeToString(encryption.encrypt(plaintext), Base64.NO_WRAP)
    }

    /** The recorded Spotify account, or null when it has no user id or can't be decrypted. */
    private fun decryptAccount(encrypted: String): SpotifyProfile? = try {
        val plaintext = encryption.decrypt(Base64.decode(encrypted, Base64.NO_WRAP))
        val stored = json.decodeFromString(StoredSpotifyAccount.serializer(), String(plaintext))
        stored.userId.takeIf { it.isNotBlank() }
            ?.let { SpotifyProfile(userId = it, displayName = stored.displayName.ifBlank { it }) }
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
