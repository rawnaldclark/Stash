package com.stash.feature.record.lastfm

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.lastfm.LastFmApiException
import com.stash.core.data.lastfm.LastFmHttpException
import com.stash.core.data.lastfm.LastFmRateLimitedException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/** The reader's answers to what Last.fm (and the network) can say: the Worker's and the web reader's, plus a private profile's own read. */
class LastfmPagesTest {
    private val nowS = 1_800_000_000L
    private val uts = 1_714_564_800L

    private fun page(vararg titles: String): JsonObject = Json.parseToJsonElement(
        """{"recenttracks":{"track":[${titles.joinToString(",") { """{"name":"$it","artist":{"#text":"Band"},"date":{"uts":"$uts"}}""" }}],"@attr":{"page":"1","totalPages":"1","total":"${titles.size}"}}}""",
    ).jsonObject

    /** Last.fm as the app's client answers: [unsigned] and [signed] are what each read does (a page, or a throw). */
    private class Remote(var unsigned: () -> JsonObject, var signed: () -> JsonObject = { error("not signed") }) : LastfmRemote {
        val asked = mutableListOf<String>()
        override suspend fun page(user: String, from: Long?, to: Long, page: Int): JsonObject {
            asked += "unsigned $user $page"
            return unsigned()
        }

        override suspend fun signedPage(sessionKey: String, user: String, from: Long?, to: Long, page: Int): JsonObject {
            asked += "signed $user $page $sessionKey"
            return signed()
        }
    }

    private fun reader(remote: Remote, account: LastfmAccount? = LastfmAccount("me", "sk")) =
        LastfmPages(remote, "me", account = { account }, nowS = { nowS })

    private suspend fun failure(block: suspend () -> Unit): LastfmFailure? = try {
        block()
        null
    } catch (e: LastfmError) {
        e.kind
    }

    @Test fun `a page is read by the web's rules`() = runTest {
        val p = reader(Remote({ page("One", "Two") })).read(1, nowS, null)
        assertThat(p.plays.map { it.t }).containsExactly("One", "Two").inOrder()
        assertThat(p.pages).isEqualTo(1)
    }

    @Test fun `a private profile reads itself signed, and every page after it goes signed straight away`() = runTest {
        val remote = Remote({ throw LastFmApiException(17, "Login required") }, { page("Hidden") })
        val r = reader(remote)
        assertThat(r.read(1, nowS, null).plays.map { it.t }).containsExactly("Hidden")
        assertThat(r.read(2, nowS, null).plays.map { it.t }).containsExactly("Hidden")
        assertThat(remote.asked).containsExactly("unsigned me 1", "signed me 1 sk", "signed me 2 sk").inOrder()
    }

    @Test fun `a private profile with no session to sign with is an empty last page on page 1, busy after it`() = runTest {
        val remote = Remote({ throw LastFmApiException(17, "Login required") })
        assertThat(reader(remote, LastfmAccount("me", null)).read(1, nowS, null)).isEqualTo(HistoryPage(emptyList(), 1, 0, 0))
        assertThat(failure { reader(remote, LastfmAccount("me", null)).read(3, nowS, null) }).isEqualTo(LastfmFailure.BUSY)
        assertThat(remote.asked).containsExactly("unsigned me 1", "unsigned me 3").inOrder()
    }

    @Test fun `no such user is an empty last page on page 1, past it the history was there a page ago - busy`() = runTest {
        assertThat(reader(Remote({ throw LastFmApiException(6, "User not found") })).read(1, nowS, null)).isEqualTo(HistoryPage(emptyList(), 1, 0, 0))
        assertThat(failure { reader(Remote({ throw LastFmApiException(6, "User not found") })).read(2, nowS, null) }).isEqualTo(LastfmFailure.BUSY)
        // The same answered in the body, not thrown
        val body = Json.parseToJsonElement("""{"error":6,"message":"User not found"}""").jsonObject
        assertThat(reader(Remote({ body })).read(1, nowS, null).pages).isEqualTo(0)
        assertThat(failure { reader(Remote({ body })).read(4, nowS, null) }).isEqualTo(LastfmFailure.BUSY)
    }

    @Test fun `a signed page that is 17 past page 1 is busy, not a second signed ask`() = runTest {
        val remote = Remote({ throw LastFmApiException(17, "Login required") }, { page("Hidden") })
        val r = reader(remote)
        r.read(1, nowS, null)
        remote.signed = { throw LastFmApiException(17, "Login required") }
        assertThat(failure { r.read(2, nowS, null) }).isEqualTo(LastfmFailure.BUSY)
        assertThat(remote.asked).containsExactly("unsigned me 1", "signed me 1 sk", "signed me 2 sk").inOrder()
    }

    @Test fun `a page before the last that lists nothing is a stumble - busy`() = runTest {
        val empty = Json.parseToJsonElement("""{"recenttracks":{"track":[],"@attr":{"page":"2","totalPages":"5","total":"900"}}}""").jsonObject
        assertThat(failure { reader(Remote({ empty })).read(2, nowS, null) }).isEqualTo(LastfmFailure.BUSY)
        // ...told apart from an outage: counted, since one that stays so is a hole the import skips
        val hole = try {
            reader(Remote({ empty })).read(2, nowS, null)
            null
        } catch (e: LastfmError) {
            e
        }
        assertThat(hole!!.empty).isTrue()
        val outage = try {
            reader(Remote({ throw LastFmApiException(29, "Rate limit") })).read(2, nowS, null)
            null
        } catch (e: LastfmError) {
            e
        }
        assertThat(outage!!.empty).isFalse()
        val last = Json.parseToJsonElement("""{"recenttracks":{"track":[],"@attr":{"page":"5","totalPages":"5","total":"900"}}}""").jsonObject
        assertThat(reader(Remote({ last })).read(5, nowS, null)).isEqualTo(HistoryPage(emptyList(), 5, 5, 900))
    }

    @Test fun `a session Last fm no longer takes is a reconnect`() = runTest {
        val remote = Remote({ throw LastFmApiException(17, "Login required") }, { throw LastFmApiException(9, "Invalid session key") })
        assertThat(failure { reader(remote).read(1, nowS, null) }).isEqualTo(LastfmFailure.RECONNECT)
        // In the body, as text, on a later page: the same
        val r = reader(Remote({ throw LastFmApiException(17, "Login required") }, { page("Hidden") }))
        r.read(1, nowS, null)
        val body = Json.parseToJsonElement("""{"error":"9","message":"Invalid session key"}""").jsonObject
        assertThat(failure { reader(Remote({ throw LastFmApiException(17, "x") }, { body })).read(3, nowS, null) }).isEqualTo(LastfmFailure.RECONNECT)
    }

    @Test fun `error 9 on an unsigned read is busy, as the Worker reads it without a session`() = runTest {
        assertThat(failure { reader(Remote({ throw LastFmApiException(9, "Invalid session key") })).read(1, nowS, null) }).isEqualTo(LastfmFailure.BUSY)
    }

    @Test fun `busy, rate limited, an outage, no connection, a body that isn't a page - all busy`() = runTest {
        for (thrown in listOf(
            LastFmApiException(29, "Rate limit"),
            LastFmApiException(8, "Operation failed"),
            LastFmApiException(11, "Service offline"),
            LastFmRateLimitedException("HTTP 429"),
            LastFmHttpException(503, "HTTP 503"),
            IOException("no route"),
            SerializationException("not json"),
            IllegalStateException("Empty Last.fm response"),
        )) {
            assertThat(failure { reader(Remote({ throw thrown })).read(1, nowS, null) }).isEqualTo(LastfmFailure.BUSY)
        }
        assertThat(failure { reader(Remote({ Json.parseToJsonElement("""{"nothing":1}""").jsonObject })).read(1, nowS, null) })
            .isEqualTo(LastfmFailure.BUSY)
    }

    @Test fun `a fault of this code isn't Last fm being busy`() = runTest {
        assertThrows(NullPointerException::class.java) {
            kotlinx.coroutines.runBlocking { reader(Remote({ throw NullPointerException("bug") })).read(1, nowS, null) }
        }
    }

    @Test fun `the account gone or changed under the run is a reconnect, and nothing is asked`() = runTest {
        val remote = Remote({ page("One") })
        assertThat(failure { reader(remote, account = null).read(1, nowS, null) }).isEqualTo(LastfmFailure.RECONNECT)
        assertThat(failure { reader(remote, account = LastfmAccount("someone else", "sk2")).read(1, nowS, null) }).isEqualTo(LastfmFailure.RECONNECT)
        assertThat(remote.asked).isEmpty()
    }
}
