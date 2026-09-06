package com.stash.data.ytmusic.potoken

/**
 * The two proof-of-origin tokens one BotGuard minting cycle yields.
 *
 * - [playerToken] is bound to a video id and travels in the player request's
 *   `serviceIntegrityDimensions.poToken` (web-family clients only).
 * - [sessionToken] is bound to the visitor id and travels as `pot=` on the
 *   googlevideo stream URL — the GVS wall: without it a direct URL serves
 *   ~1 MB and then answers 403.
 */
data class PoTokenPair(
    val playerToken: String,
    val sessionToken: String,
)

/**
 * Mints YouTube proof-of-origin tokens for [mint]'s video under a visitor
 * session. `null` means "no token this time": callers carry on without one
 * and let the tail probe judge the URL, exactly as before tokens existed.
 */
interface PoTokenMinter {
    suspend fun mint(videoId: String, sessionId: String): PoTokenPair?

    /** Boot whatever is slow (a WebView) ahead of the first [mint]. */
    suspend fun preWarm(sessionId: String) {}

    /** Drop held resources while the app is in the background. */
    suspend fun release() {}

    companion object {
        /** No minting at all (tests, and processes without a usable WebView). */
        val None: PoTokenMinter = PoTokenMinter { _, _ -> null }
    }
}

/** Lambda form: `PoTokenMinter { videoId, sessionId -> … }`. */
@Suppress("FunctionName")
fun PoTokenMinter(block: suspend (videoId: String, sessionId: String) -> PoTokenPair?): PoTokenMinter =
    object : PoTokenMinter {
        override suspend fun mint(videoId: String, sessionId: String): PoTokenPair? = block(videoId, sessionId)
    }
