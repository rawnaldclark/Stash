package com.stash.core.auth.soundcloud

import kotlinx.serialization.Serializable

/** One atomic encrypted record. Null OAuth means public-profile mode, not an invented token. */
@Serializable
data class SoundCloudSession(
    val userId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val oauthToken: String? = null,
    /** Changes on every connection, even reconnecting the same account. */
    val connectionId: String,
    val refreshToken: String? = null,
) {
    override fun toString(): String = "SoundCloudSession(publicOnly=${oauthToken == null})"
}
