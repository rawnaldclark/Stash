package com.stash.core.auth.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Which Spotify account a sign-in belongs to, as Spotify itself reports it.
 *
 * @property userId The account's Spotify user id (`username` in Spotify's answer). It never
 *   changes, so it is what tells two accounts apart. Often an unreadable string for accounts
 *   made through Google or Facebook, so it is never shown when [displayName] is there.
 * @property displayName The account's display name, for messages ("signed in as Rawn"). Falls
 *   back to [userId] when Spotify gives none.
 */
data class SpotifyProfile(
    val userId: String,
    val displayName: String,
) {
    /**
     * True when [other] is the same Spotify account. Compares user ids only: two accounts can
     * share a display name. Ids are compared without the `spotify:user:` prefix and ignoring
     * case, so the same account read through two shapes of Spotify's answer still matches.
     */
    fun isSameAccount(other: SpotifyProfile): Boolean =
        accountKeyOf(userId) == accountKeyOf(other.userId)

    /**
     * This account's id in the one form Stash stores it in the database: `spotify_accounts.user_id`,
     * `playlists.spotify_account` and `remote_playlist_snapshots.spotify_account` (see [accountKeyOf]).
     */
    val accountKey: String get() = accountKeyOf(userId)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * A Spotify user id in the form [isSameAccount] compares: trimmed, without the
         * `spotify:user:` prefix, lower case. The database keys accounts by it, so the same
         * account read through two shapes of Spotify's answer is one row and one tag.
         */
        fun accountKeyOf(id: String): String = id.trim().removePrefix(USER_URI_PREFIX).lowercase()

        private const val USER_URI_PREFIX = "spotify:user:"

        /**
         * The account in a `profileAttributes` answer (`data.me.profile`), or null when the body
         * names none: an error body, a persisted-query miss, an anonymous session, or a shape
         * change. Reads `username`, else the id at the end of `uri`. Soft casts throughout, so an
         * unexpected shape costs the lookup, never a crash. Pure, for JVM tests.
         */
        fun parseProfileAttributes(body: String): SpotifyProfile? {
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            val profile = ((root["data"] as? JsonObject)?.get("me") as? JsonObject)
                ?.get("profile") as? JsonObject ?: return null
            val userId = profile.text("username")
                ?: profile.text("uri")?.takeIf { it.startsWith(USER_URI_PREFIX) }
                    ?.removePrefix(USER_URI_PREFIX)
                    ?.takeIf { it.isNotBlank() && ':' !in it }
                ?: return null
            return SpotifyProfile(userId = userId, displayName = profile.text("name") ?: userId)
        }

        /** A non-blank string field, or null (missing, JsonNull, blank or not a primitive). */
        private fun JsonObject.text(key: String): String? =
            (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }
}
