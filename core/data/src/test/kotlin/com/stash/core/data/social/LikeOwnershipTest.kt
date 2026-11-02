package com.stash.core.data.social

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Whose a like is, on disk: the stamp epoch is written once and kept across process restarts, and so is the record. */
class LikeOwnershipTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `the stamp epoch is written once, before anything is stamped, and kept for good`() = runBlocking<Unit> {
        val file = File(tmp.root, "like_mirror.preferences_pb")
        val scopes = mutableListOf<CoroutineScope>()
        fun open(): LikeOwnership {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
            return LikeOwnership(PreferenceDataStoreFactory.create(scope = scope) { file })
        }

        val first = open()
        assertThat(first.epoch()).isNull() // nothing stamped by this build: every stamp there is is older
        assertThat(first.stampEpoch(1_000L)).isEqualTo(1_000L)
        assertThat(first.stampEpoch(5_000L)).isEqualTo(1_000L) // a later stamp never moves it
        first.markLikedByService(Destination.SPOTIFY, 7L)
        scopes.removeAt(0).coroutineContext[Job]!!.cancelAndJoin() // the process ends

        val second = open()
        assertThat(second.epoch()).isEqualTo(1_000L)
        assertThat(second.stampEpoch(9_000L)).isEqualTo(1_000L)
        assertThat(second.isLikedByService(Destination.SPOTIFY, 7L)).isTrue()
        assertThat(second.isLikedByService(Destination.YT_MUSIC, 7L)).isFalse()
        scopes.removeAt(0).coroutineContext[Job]!!.cancelAndJoin()
    }
}
