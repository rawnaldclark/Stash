package com.stash.core.data.sync

/** Account-scoped IDs keep a reconnect or another account from adopting the wrong library lists. */
object SoundCloudPlaylistIds {
    fun playlist(userId: String, remoteId: String) = "soundcloud_playlist_${userId}_$remoteId"
    fun mix(userId: String, remoteId: String) = "soundcloud_mix_${userId}_$remoteId"
    fun belongsTo(sourceId: String, userId: String): Boolean =
        sourceId == "soundcloud_liked_$userId" || sourceId.startsWith("soundcloud_playlist_${userId}_") ||
            sourceId.startsWith("soundcloud_mix_${userId}_")
}
