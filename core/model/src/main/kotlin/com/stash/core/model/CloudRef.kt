package com.stash.core.model

import java.net.URLDecoder
import java.net.URLEncoder

/** The scheme of a cloud song's stream address. */
const val STASH_CLOUD_SCHEME = "stash-cloud"

/** Cloud storage providers Stash can read from. */
enum class CloudProvider(val id: String, val displayName: String) {
    ONEDRIVE("onedrive", "OneDrive"),
    DROPBOX("dropbox", "Dropbox");

    companion object {
        fun fromId(id: String?): CloudProvider? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Where a [MusicSource.CLOUD] song lives:
 * `stash-cloud://<provider>/<accountId>/<itemId>?v=<version>`.
 *
 * [accountId] is Stash's own `cloud_accounts` row id (not the provider's user id), [itemId] the
 * provider's file id (OneDrive item id, Dropbox `id:…`), and [version] the file's content version
 * (OneDrive cTag, Dropbox rev), so a stream cache key
 * built from it ([cacheKey]) changes when the file's bytes do. Pure Kotlin (no android.net.Uri) so
 * the scanner, the player and their unit tests share one parser.
 */
data class CloudRef(
    val provider: CloudProvider,
    val accountId: Long,
    val itemId: String,
    val version: String? = null,
) {
    init {
        require(accountId > 0) { "accountId must be positive" }
        require(itemId.isNotBlank()) { "itemId must not be blank" }
    }

    /** The URI stored in `tracks.cloud_uri` and played by the player. */
    fun toUri(): String = buildString {
        append(STASH_CLOUD_SCHEME).append("://").append(provider.id)
        append('/').append(accountId)
        append('/').append(encode(itemId))
        version?.takeIf { it.isNotEmpty() }?.let { append("?v=").append(encode(it)) }
    }

    /**
     * The stream-cache key: provider + item + content version, never the (expiring, signed)
     * download URL, so bytes cached from one link are found again through the next one.
     */
    val cacheKey: String get() = "${provider.id}:$itemId:${version.orEmpty()}"

    companion object {
        /** Parses a [toUri] string; null for anything else (wrong scheme, bad ids). */
        fun parse(uri: String?): CloudRef? {
            if (uri == null) return null
            val prefix = "$STASH_CLOUD_SCHEME://"
            if (!uri.startsWith(prefix, ignoreCase = true)) return null
            val rest = uri.substring(prefix.length)
            val path = rest.substringBefore('?')
            val query = rest.substringAfter('?', "")
            val parts = path.split('/')
            if (parts.size != 3) return null
            val provider = CloudProvider.fromId(parts[0].lowercase()) ?: return null
            val accountId = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val itemId = decode(parts[2])?.takeIf { it.isNotBlank() } ?: return null
            val version = query.split('&')
                .firstOrNull { it.startsWith("v=") }
                ?.let { decode(it.removePrefix("v=")) }
                ?.takeIf { it.isNotEmpty() }
            return CloudRef(provider, accountId, itemId, version)
        }

        private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        private fun decode(s: String): String? = try {
            URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/** True for a song that streams from the user's own cloud storage. */
val Track.isCloudSong: Boolean get() = source == MusicSource.CLOUD
