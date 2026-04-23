package com.stash.data.ytmusic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extracts the `playbackTracking.videostatsPlaybackUrl.baseUrl` from a
 * YouTube Music InnerTube `/youtubei/v1/player` response.
 *
 * **Important:** this is the URL that `ytmusicapi.add_history_item` and
 * SimpMusic use to register a play in the user's Watch History. It is
 * NOT the same as `videostatsWatchtimeUrl` (which is the in-app
 * progress-ping channel; hitting it does not register a history entry
 * on its own).
 */
class PlaybackTrackingParser {
    fun extract(playerResponse: JsonObject): String? {
        val tracking = playerResponse["playbackTracking"]?.jsonObject ?: return null
        val playbackUrl = tracking["videostatsPlaybackUrl"]?.jsonObject ?: return null
        return playbackUrl["baseUrl"]?.jsonPrimitive?.content
    }
}
