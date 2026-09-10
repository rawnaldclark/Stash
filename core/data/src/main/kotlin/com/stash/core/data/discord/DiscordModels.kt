package com.stash.core.data.discord

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DiscordActivity(
    @SerialName("application_id") val applicationId: String? = null,
    val name: String? = null,
    val platform: String? = null,
    val type: Int? = null,
    @SerialName("status_display_type") val statusDisplayType: Int? = null,
    val details: String? = null,
    val state: String? = null,
    val assets: Assets? = null,
    val timestamps: Timestamps? = null,
    val buttons: List<Button>? = null,
) {
    @Serializable
    data class Assets(
        @SerialName("large_text") val largeText: String? = null,
        @SerialName("large_image") val largeImage: String? = null,
        @SerialName("large_url") val largeUrl: String? = null,
        @SerialName("small_image") val smallImage: String? = null,
        @SerialName("small_url") val smallUrl: String? = null,
        @SerialName("small_text") val smallText: String? = null,
    )

    @Serializable
    data class Button(val label: String? = null, val url: String? = null)

    @Serializable
    data class Timestamps(val start: Long? = null, val end: Long? = null)
}

@Serializable
data class DiscordSession(
    val activities: List<DiscordActivity>? = null,
    val token: String? = null,
)

enum class DiscordActivityType(val value: Int) {
    Playing(0), Listening(2), Watching(3),
}