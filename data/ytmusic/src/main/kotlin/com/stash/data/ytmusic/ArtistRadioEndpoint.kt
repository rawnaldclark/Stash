package com.stash.data.ytmusic

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * What an artist's own station needs: the seed video the play button names, the
 * `RDEM…` playlist id that makes the request a station, and the `params` that scopes
 * it (an artist's radio is filtered by them; a song's station has none).
 */
data class ArtistRadioEndpoint(
    val videoId: String,
    val playlistId: String,
    val params: String?,
) {
    internal companion object {
        /**
         * The radio play button out of an artist page answer.
         *
         * The button is a `musicPlayButtonRenderer` in the page header whose
         * `playNavigationEndpoint.watchEndpoint` carries `RDEM<channelId>` as its
         * playlist id. Its exact home differs across the two header renderers YouTube
         * ships (`musicImmersiveHeaderRenderer` and `musicVisualHeaderRenderer`) and has
         * moved before, so this walks the whole answer for the first watch endpoint whose
         * playlist id starts with `RDEM` rather than pinning a path that a redesign would
         * silently break.
         *
         * A page with no such button returns null; the caller then seeds the station from
         * the artist's most popular track instead, which still gives a station.
         */
        fun from(root: JsonObject): ArtistRadioEndpoint? = root.firstRadioWatchEndpoint()

        private fun JsonElement.firstRadioWatchEndpoint(): ArtistRadioEndpoint? {
            val obj = asObject()
            if (obj != null) {
                obj.navigatePath("watchEndpoint")?.let { endpoint ->
                    val watch = endpoint.asObject()
                    val playlistId = watch?.get("playlistId")?.asString()
                    val videoId = watch?.get("videoId")?.asString()
                    if (playlistId != null && videoId != null &&
                        playlistId.startsWith(ARTIST_RADIO_PREFIX) && videoId.isNotBlank()
                    ) {
                        return ArtistRadioEndpoint(
                            videoId = videoId,
                            playlistId = playlistId,
                            params = watch["params"]?.asString()?.takeIf { it.isNotBlank() },
                        )
                    }
                }
                for ((key, value) in obj) {
                    if (key == "watchEndpoint") continue
                    value.firstRadioWatchEndpoint()?.let { return it }
                }
            }
            asArray()?.forEach { child -> child.firstRadioWatchEndpoint()?.let { return it } }
            return null
        }

        /** The prefix of an artist's own station id (`RDEM<channelId>`). */
        const val ARTIST_RADIO_PREFIX = "RDEM"
    }
}
