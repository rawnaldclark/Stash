package com.stash.core.data.social.ytmusic

import com.stash.data.ytmusic.InnerTubeClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A like or unlike YouTube Music didn't take. [status] is the HTTP status, or
 * [InnerTubeClient.RequestOutcome.STATUS_NETWORK_ERROR] when nothing came back: like mirroring
 * tells a dropped connection (wait for the network) and an expired sign-in (say so) from a
 * refusal by it ([com.stash.core.data.social.LikeMirrorError.of]).
 */
class YtMusicLibraryException(message: String, val status: Int = 0) : Exception(message)

/**
 * v0.9.13: Thin wrapper around [InnerTubeClient.setLiked]. Turns a failed answer into an
 * exception so the dispatcher's `runCatching` lifts it into `Result.failure`. InnerTubeClient
 * handles SAPISID-hash auth, cookies and the picked profile on its existing request path.
 */
@Singleton
class YtMusicLibraryApiClient @Inject constructor(
    private val innerTubeClient: InnerTubeClient,
) {
    suspend fun likeVideo(videoId: String) = write(videoId, liked = true)

    suspend fun removeLike(videoId: String) = write(videoId, liked = false)

    private suspend fun write(videoId: String, liked: Boolean) {
        val outcome = innerTubeClient.setLiked(videoId, liked)
        if (outcome.body == null || outcome.statusCode !in 200..299) {
            val verb = if (liked) "likeVideo" else "removeLike"
            throw YtMusicLibraryException("InnerTube $verb($videoId) failed: HTTP ${outcome.statusCode}", outcome.statusCode)
        }
    }
}
