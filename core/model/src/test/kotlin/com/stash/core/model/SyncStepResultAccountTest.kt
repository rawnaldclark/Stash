package com.stash.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

/** [SyncStepResult.account]: an extra Spotify account's label on its steps (plan 2026-11-04 §3.7). */
class SyncStepResultAccountTest {

    @Test
    fun `a main-account step writes no account, exactly as before`() {
        val json = Json.encodeToString(SyncStepResult("SPOTIFY", "getUserPlaylists", StepStatus.SUCCESS, itemCount = 3))

        assertThat(json).doesNotContain("account")
    }

    @Test
    fun `an extra account's step carries its label and reads back`() {
        val step = SyncStepResult("SPOTIFY", "getLikedSongs", StepStatus.SUCCESS, account = "extra 2")

        val back = Json.decodeFromString<SyncStepResult>(Json.encodeToString(step))

        assertThat(back.account).isEqualTo("extra 2")
        assertThat(back).isEqualTo(step)
    }

    @Test
    fun `a step recorded before the field reads back as the main account's`() {
        val old = """{"service":"SPOTIFY","step":"getUserPlaylists","status":"SUCCESS","itemCount":15}"""

        assertThat(Json.decodeFromString<SyncStepResult>(old).account).isNull()
    }
}
