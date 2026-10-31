package com.stash.core.media.streaming

import com.stash.core.auth.TokenManager
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.soundcloud.SoundCloudClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class SoundCloudStreamResolver @Inject constructor(
    private val client: SoundCloudClient,
    private val tokens: TokenManager,
    private val tracks: TrackDao,
) {
    suspend fun resolve(track: TrackEntity): StreamUrl? = withContext(Dispatchers.IO) {
        val id = track.soundCloudId ?: return@withContext null
        kotlinx.coroutines.withTimeoutOrNull(30_000) {
            try {
                val stream = client.stream(id, tokens.getSoundCloudToken()) ?: return@withTimeoutOrNull null
                if (track.id > 0) tracks.updateSoundCloudPreview(track.id, stream.preview)
                StreamUrl(
                    url = stream.url, expiresAtMs = System.currentTimeMillis() + 5 * 60_000,
                    codec = when {
                        "mp4" in stream.mimeType || "aac" in stream.mimeType -> "aac"
                        "opus" in stream.mimeType -> "opus"
                        "mpeg" in stream.mimeType -> "mp3"
                        else -> null
                    },
                    origin = "soundcloud", isHls = stream.hls, isPreview = stream.preview,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("SoundCloudStream", "Upload resolve failed; HTTP=" +
                    ((e as? com.stash.data.soundcloud.SoundCloudException)?.status?.toString() ?: "unavailable"))
                // A missing or blocked upload never falls through to title-based matching.
                null
            }
        }
    }
}
