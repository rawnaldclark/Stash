package com.stash.core.media

import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrlCache
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a playable stream URL for the *current* track on the native
 * Bluetooth / Android Auto resumption path
 * ([com.stash.core.media.service.StashPlaybackService.onPlaybackResumption]).
 *
 * The service's ExoPlayer uses the default media-source factory, so a streamed
 * track must carry a real `http(s)` URL before it reaches the player. In normal
 * playback that resolution happens in
 * [PlayerRepositoryImpl] before items reach the controller; on the native
 * resume path the repository isn't in the loop, so without this the current
 * streamed track would have no URI and error/skip — i.e. online-mode resume
 * would play nothing.
 *
 * The gating here intentionally mirrors the streaming arm of
 * [PlayerRepositoryImpl.buildMediaItemForTrack] (streaming-pref / connectivity
 * / cellular) and reuses the same [StreamSourceRegistry] + [StreamUrlCache]
 * primitives. It returns only the URL (no Media3 `MediaItem`), so the decision
 * is unit-testable on the JVM and the service owns item construction. A future
 * refactor could unify this with `buildMediaItemForTrack`.
 */
@Singleton
class ResumeStreamResolver @Inject constructor(
    private val streamingPreference: StreamingPreference,
    private val connectivity: ConnectivityMonitor,
    private val streamUrlCache: StreamUrlCache,
    private val streamResolver: StreamSourceRegistry,
) {
    /**
     * @return an `http(s)` stream URL to play [track] from, or null when the
     *   caller should use the local file (downloaded track) or when the track
     *   can't be streamed right now (streaming off / offline / cellular
     *   refused / no stream available).
     */
    suspend fun resolveStreamUrl(track: TrackEntity): String? {
        // Downloaded tracks play from disk — caller uses the local file path.
        if (track.isDownloaded && !track.filePath.isNullOrBlank()) return null
        if (!streamingPreference.current()) return null
        if (!connectivity.isConnected()) return null
        if (connectivity.isCellular() && !streamingPreference.streamOnCellular.first()) return null

        val cached = streamUrlCache.get(track.id)
        val stream = cached ?: if (connectivity.isCellular()) {
            streamResolver.resolve(track, allowYouTube = true, preferFastStartup = true)
        } else {
            streamResolver.resolve(track, allowYouTube = true)
        }?.also {
            streamUrlCache.put(track.id, it)
        }
        return stream?.url
    }
}
