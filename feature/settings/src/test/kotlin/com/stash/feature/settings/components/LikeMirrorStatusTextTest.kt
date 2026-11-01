package com.stash.feature.settings.components

import android.content.res.Resources
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.social.LikeMirrorError
import com.stash.core.data.social.LikeMirrorStatus
import com.stash.core.data.social.LikeMirrorStatus.State
import com.stash.feature.settings.R
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

/**
 * The line under each "Like on … too" switch, as the owner asked for it: "Up to date", "3 waiting · retrying", "Last error: …
 * · tap to retry". Each state reads its own string, with the count or the reason in it.
 */
class LikeMirrorStatusTextTest {

    /** English, as the resources say it, so a wrong id or argument shows up as wrong words. */
    private val res = mockk<Resources> {
        every { getString(R.string.settings_like_status_up_to_date) } returns "Up to date"
        every { getString(R.string.settings_like_status_waiting, any()) } answers { "${(args[1] as Array<*>)[0]} waiting" }
        every { getString(R.string.settings_like_status_offline, any()) } answers { "${(args[1] as Array<*>)[0]} waiting for a connection" }
        every { getString(R.string.settings_like_status_retrying, any()) } answers { "${(args[1] as Array<*>)[0]} waiting · retrying" }
        every { getString(R.string.settings_like_status_needs_you, any()) } answers { "Last error: ${(args[1] as Array<*>)[0]} · tap to retry" }
        every { getString(R.string.settings_like_error_signed_out) } returns "sign-in expired"
        every { getString(R.string.settings_like_error_rate_limited) } returns "too many requests"
        every { getString(R.string.settings_like_error_refused) } returns "not accepted"
        every { getString(R.string.settings_like_error_offline) } returns "no connection"
    }

    private fun text(state: State, waiting: Int = 0, error: LikeMirrorError? = null) =
        LikeMirrorStatusText.of(LikeMirrorStatus(state, waiting, error), res)

    @Test fun `each state says what it is`() {
        assertThat(text(State.UP_TO_DATE)).isEqualTo("Up to date")
        assertThat(text(State.WAITING, 1)).isEqualTo("1 waiting")
        assertThat(text(State.OFFLINE, 2, LikeMirrorError.OFFLINE)).isEqualTo("2 waiting for a connection")
        assertThat(text(State.RETRYING, 3, LikeMirrorError.RATE_LIMITED)).isEqualTo("3 waiting · retrying")
    }

    @Test fun `when the listener has to act, it says why and offers the retry`() {
        assertThat(text(State.NEEDS_YOU, 3, LikeMirrorError.SIGNED_OUT)).isEqualTo("Last error: sign-in expired · tap to retry")
        assertThat(text(State.NEEDS_YOU, 1, LikeMirrorError.RATE_LIMITED)).isEqualTo("Last error: too many requests · tap to retry")
        assertThat(text(State.NEEDS_YOU, 1, LikeMirrorError.REFUSED)).isEqualTo("Last error: not accepted · tap to retry")
        assertThat(text(State.NEEDS_YOU, 1, null)).isEqualTo("Last error: not accepted · tap to retry")
    }
}
