package com.stash.core.media.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The library service is exported: any installed app can bind it. Transport-like custom commands
 * stay open to all; the ones that write Stash's own state (a like that can mirror to the user's
 * Spotify or YouTube account, the shuffle order, leaving a Listen Together room) go only to the
 * app, the system, trusted controllers and Android Auto / Automotive.
 */
class GrantedCustomActionsTest {

    @Test fun `any controller gets the transport-like commands`() {
        assertThat(grantedCustomActions(privileged = false)).containsExactly(
            StashPlaybackService.COMMAND_TOGGLE_SHUFFLE,
            StashPlaybackService.COMMAND_CYCLE_REPEAT,
            StashPlaybackService.COMMAND_STOP_SLEEP_TIMER,
        )
    }

    @Test fun `only a privileged controller can like, set the shuffle order or leave a session`() {
        assertThat(grantedCustomActions(privileged = true)).containsAtLeast(
            StashPlaybackService.COMMAND_TOGGLE_LIKE,
            StashPlaybackService.COMMAND_SET_SHUFFLE_ORDER,
            StashPlaybackService.COMMAND_LEAVE_SESSION,
        )
        assertThat(grantedCustomActions(privileged = false)).containsNoneOf(
            StashPlaybackService.COMMAND_TOGGLE_LIKE,
            StashPlaybackService.COMMAND_SET_SHUFFLE_ORDER,
            StashPlaybackService.COMMAND_LEAVE_SESSION,
        )
    }

    /** It puts a MediaItem, URI and all, straight onto the player, past resolveMediaItem's local-URI check. */
    @Test fun `only the app itself can queue a song with Play next under shuffle`() {
        assertThat(grantedCustomActions(privileged = true, ownApp = true))
            .contains(StashPlaybackService.COMMAND_PLAY_NEXT_SHUFFLED)
        assertThat(grantedCustomActions(privileged = true, ownApp = false))
            .doesNotContain(StashPlaybackService.COMMAND_PLAY_NEXT_SHUFFLED)
        assertThat(grantedCustomActions(privileged = false))
            .doesNotContain(StashPlaybackService.COMMAND_PLAY_NEXT_SHUFFLED)
    }

    /** It keeps the player's shuffle order as the timeline: only the app's own big queue may ask. */
    @Test fun `only the app itself can ask to keep the timeline order`() {
        assertThat(grantedCustomActions(privileged = true, ownApp = true))
            .contains(StashPlaybackService.COMMAND_KEEP_TIMELINE_ORDER)
        assertThat(grantedCustomActions(privileged = true, ownApp = false))
            .doesNotContain(StashPlaybackService.COMMAND_KEEP_TIMELINE_ORDER)
    }
}
