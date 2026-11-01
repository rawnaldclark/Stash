package com.stash.core.data.social

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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

/**
 * The outbox: what's waiting survives the process (a heart made offline, then the app killed, still gets there), one op per
 * song and service with the last intent winning, a new identity for each intent (so a send already under way can't settle a
 * newer one), and tries counted only when the service, not the connection, said no.
 */
class LikeOutboxTest {

    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_800_000_000_000L
    private val both = setOf(Destination.SPOTIFY, Destination.LAST_FM)

    // -- On disk, across a process restart ---------------------------------------------------------------------------------

    @Test fun `what is waiting survives the process being killed`() = runBlocking<Unit> {
        val file = File(tmp.root, "like_mirror.preferences_pb")
        val scopes = mutableListOf<CoroutineScope>()
        fun open(): LikeOutbox {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
            return LikeOutbox(DataStoreLikeOutboxStorage(PreferenceDataStoreFactory.create(scope = scope) { file }))
        }

        val first = open()
        first.put(7L, liked = true, destinations = both, now = now)
        val sent = first.current().first { it.destination == Destination.LAST_FM }
        first.recordFailure(first.current().first { it.destination == Destination.SPOTIFY }, LikeMirrorError.SIGNED_OUT, now + 5)
        first.settle(sent)
        scopes.removeAt(0).coroutineContext[Job]!!.cancelAndJoin() // the process ends; one DataStore per file

        val second = open()
        val left = second.current()
        assertThat(left).hasSize(1)
        assertThat(left.single().trackId).isEqualTo(7L)
        assertThat(left.single().destination).isEqualTo(Destination.SPOTIFY)
        assertThat(left.single().liked).isTrue()
        assertThat(left.single().attempts).isEqualTo(1)
        assertThat(left.single().lastError).isEqualTo(LikeMirrorError.SIGNED_OUT)
        // and new ops after the restart never reuse an identity
        second.put(8L, liked = true, destinations = setOf(Destination.LAST_FM), now = now)
        assertThat(second.current().map { it.seq }.toSet()).hasSize(2)
        scopes.removeAt(0).coroutineContext[Job]!!.cancelAndJoin()
    }

    @Test fun `an unreadable or newer-build file never crashes a heart`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "bad.preferences_pb") }
        val key = stringPreferencesKey("outbox_v1")
        val outbox = LikeOutbox(DataStoreLikeOutboxStorage(store))

        store.edit { it[key] = "{not json" }
        assertThat(outbox.current()).isEmpty()

        // A service this build doesn't know (written by a newer one) drops that op alone
        store.edit {
            it[key] = """{"nextSeq":3,"ops":[
                {"seq":1,"trackId":1,"destination":"TIDAL","liked":true,"queuedAt":1},
                {"seq":2,"trackId":2,"destination":"LAST_FM","liked":false,"queuedAt":1,"lastError":"SOMETHING_NEW"}]}"""
        }
        val ops = outbox.current()
        assertThat(ops.map { it.trackId }).containsExactly(2L)
        assertThat(ops.single().lastError).isNull()
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }

    // -- Coalescing -------------------------------------------------------------------------------------------------------

    @Test fun `like then unlike on the same song leaves one op per service, the unlike`() {
        var s = LikeOutbox.put(LikeOutboxState(), 7L, liked = true, destinations = both, now = now)
        s = LikeOutbox.put(s, 7L, liked = false, destinations = both, now = now + 1)
        assertThat(s.ops).hasSize(2)
        assertThat(s.ops.map { it.liked }.toSet()).containsExactly(false)
        assertThat(s.ops.map { it.destination }.toSet()).isEqualTo(both)
    }

    @Test fun `a new intent gets a new identity, so the send already under way can't settle it`() {
        var s = LikeOutbox.put(LikeOutboxState(), 7L, liked = true, destinations = setOf(Destination.SPOTIFY), now = now)
        val inFlight = s.ops.single()
        s = LikeOutbox.put(s, 7L, liked = false, destinations = setOf(Destination.SPOTIFY), now = now + 1)
        // The like finishes and settles: the unlike that replaced it must stay
        s = LikeOutbox.settle(s, inFlight)
        assertThat(s.ops).hasSize(1)
        assertThat(s.ops.single().liked).isFalse()
        assertThat(s.ops.single().seq).isGreaterThan(inFlight.seq)
    }

    @Test fun `hearting again resets the tries, and other songs and services are left alone`() {
        var s = LikeOutbox.put(LikeOutboxState(), 7L, liked = true, destinations = both, now = now)
        s = LikeOutbox.put(s, 8L, liked = true, destinations = both, now = now)
        val spotify7 = s.ops.first { it.trackId == 7L && it.destination == Destination.SPOTIFY }
        repeat(3) { s = LikeOutbox.recordFailure(s, s.ops.first { it.seq == spotify7.seq }, LikeMirrorError.REFUSED, now) }
        assertThat(s.ops.first { it.seq == spotify7.seq }.attempts).isEqualTo(3)

        s = LikeOutbox.put(s, 7L, liked = true, destinations = setOf(Destination.SPOTIFY), now = now + 9)
        val again = s.ops.first { it.trackId == 7L && it.destination == Destination.SPOTIFY }
        assertThat(again.attempts).isEqualTo(0)
        assertThat(again.lastError).isNull()
        assertThat(s.ops).hasSize(4)
    }

    @Test fun `past the ceiling the oldest go first`() {
        var s = LikeOutboxState()
        for (id in 1..(LikeOutbox.MAX_OPS + 2).toLong()) s = LikeOutbox.put(s, id, true, setOf(Destination.LAST_FM), now)
        assertThat(s.ops).hasSize(LikeOutbox.MAX_OPS)
        assertThat(s.ops.minOf { it.trackId }).isEqualTo(3L)
    }

    // -- Failures ---------------------------------------------------------------------------------------------------------

    @Test fun `no connection never counts as a try, a refusal does, and enough of them park the op`() {
        var s = LikeOutbox.put(LikeOutboxState(), 7L, true, setOf(Destination.SPOTIFY), now)
        val op = s.ops.single()
        s = LikeOutbox.recordFailure(s, op, LikeMirrorError.OFFLINE, now + 1)
        assertThat(s.ops.single().attempts).isEqualTo(0)
        assertThat(s.ops.single().lastError).isEqualTo(LikeMirrorError.OFFLINE)

        repeat(LikeOutbox.MAX_ATTEMPTS) { s = LikeOutbox.recordFailure(s, op, LikeMirrorError.REFUSED, now + 2) }
        assertThat(s.ops.single().parked).isTrue()

        // "Tap to retry" puts it back to new
        s = LikeOutbox.retry(s, Destination.SPOTIFY)
        assertThat(s.ops.single().parked).isFalse()
        assertThat(s.ops.single().attempts).isEqualTo(0)
    }

    @Test fun `a success frees what a sign-in held back, and gives a parked refusal one more try, last in line`() {
        var s = LikeOutbox.put(LikeOutboxState(), 1L, true, setOf(Destination.SPOTIFY), now)
        s = LikeOutbox.put(s, 2L, true, setOf(Destination.SPOTIFY), now)
        s = LikeOutbox.put(s, 3L, true, setOf(Destination.LAST_FM), now)
        val (signedOut, refused) = s.ops.filter { it.destination == Destination.SPOTIFY }
        val lastFm = s.ops.first { it.destination == Destination.LAST_FM }
        repeat(LikeOutbox.MAX_ATTEMPTS) {
            s = LikeOutbox.recordFailure(s, signedOut, LikeMirrorError.SIGNED_OUT, now)
            s = LikeOutbox.recordFailure(s, refused, LikeMirrorError.REFUSED, now)
            s = LikeOutbox.recordFailure(s, lastFm, LikeMirrorError.SIGNED_OUT, now)
        }

        s = LikeOutbox.freeAfterSuccess(s, Destination.SPOTIFY)

        assertThat(s.ops.first { it.seq == signedOut.seq }.attempts).isEqualTo(0)
        val again = s.ops.first { it.seq == refused.seq }
        assertThat(again.parked).isFalse()
        assertThat(again.attempts).isEqualTo(LikeOutbox.MAX_ATTEMPTS - 1) // one more refusal parks it again
        assertThat(again.lastError).isEqualTo(LikeMirrorError.REFUSED)
        assertThat(s.ops.first { it.seq == lastFm.seq }.parked).isTrue() // another service's success says nothing about it
    }

    @Test fun `failures and settles for an op that was replaced change nothing`() {
        var s = LikeOutbox.put(LikeOutboxState(), 7L, true, setOf(Destination.SPOTIFY), now)
        val old = s.ops.single()
        s = LikeOutbox.put(s, 7L, false, setOf(Destination.SPOTIFY), now + 1)
        val after = LikeOutbox.recordFailure(LikeOutbox.settle(s, old), old, LikeMirrorError.REFUSED, now)
        assertThat(after).isEqualTo(s)
    }
}
