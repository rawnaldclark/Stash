package com.stash.core.data.community

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CommunityMessagesTest {
    @Test fun `each refusal says which (spec 2026-09-26 §3 Errors)`() {
        mapOf(
            "daily_limit" to "You've posted twice today. Try again tomorrow.",
            "live_limit" to "You have 5 posts up. Take one down to post again.",
            "network_limit" to "Too many posts from your internet connection today. Try again tomorrow.",
            "blocked" to "You can't post or vote in Community.",
            "rate_limited" to "Slow down a moment.",
            "too_large" to "This playlist is too big to post.",
            "gone" to "This post is no longer available.",
            "own_post" to "You can't vote on your own post.",
            "off" to "Community is turned off.",
            "bad_request" to "Something went wrong. Try again.",
        ).forEach { (code, message) -> assertThat(communityMessage(CommunityResult.Rejected(code))).isEqualTo(message) }
        assertThat(communityMessage(CommunityResult.Failed("timeout"))).isEqualTo("Couldn't reach Community. Try again.")
    }
}
