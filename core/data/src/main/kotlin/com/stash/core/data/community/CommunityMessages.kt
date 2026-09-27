package com.stash.core.data.community

/** The sentence for a Community action that didn't go through (spec 2026-09-26 §3 Errors, §4). */
fun communityMessage(result: CommunityResult<*>): String = when (result) {
    is CommunityResult.Ok -> ""
    is CommunityResult.Failed -> "Couldn't reach Community. Try again."
    is CommunityResult.Rejected -> when (result.code) {
        "daily_limit" -> "You've posted twice today. Try again tomorrow."
        "live_limit" -> "You have 5 posts up. Take one down to post again."
        "network_limit" -> "Too many posts from your internet connection today. Try again tomorrow."
        "blocked" -> "You can't post or vote in Community."
        "rate_limited" -> "Slow down a moment."
        "too_large" -> "This playlist is too big to post."
        "gone" -> "This post is no longer available."
        "own_post" -> "You can't vote on your own post."
        "off" -> "Community is turned off."
        else -> "Something went wrong. Try again."
    }
}
