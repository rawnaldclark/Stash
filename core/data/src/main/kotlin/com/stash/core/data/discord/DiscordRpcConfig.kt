package com.stash.core.data.discord

object DiscordRpcConfig {
    const val CLIENT_ID = "934292861724270632"
    const val REDIRECT_URI = "https://paraliyzed.net/blank.html"
    const val SCOPES = "sdk.social_layer_presence"

    fun consentUrl(): String =
        "https://discord.com/oauth2/authorize?client_id=$CLIENT_ID&response_type=code" +
            "&redirect_uri=${android.net.Uri.encode(REDIRECT_URI)}" +
            "&scope=${android.net.Uri.encode(SCOPES)}"
}