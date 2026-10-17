package com.stash.core.data.supporter

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SupporterRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val scopes = mutableListOf<CoroutineScope>()
    private lateinit var repo: SupporterRepository

    @Before fun setUp() {
        server.start()
        // The tip-jar URL as BuildConfig has it: the bare Worker, sometimes with a trailing path.
        repo = SupporterRepository(null, OkHttpClient(), server.url("/supporters.json").toString())
    }

    @After fun tearDown() {
        server.shutdown()
        scopes.forEach { it.cancel() }
    }

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test fun `start posts the trimmed, lower-cased email to the tip jar's root route`() = runBlocking {
        server.enqueue(json(202, """{"ok":true}"""))

        assertThat(repo.claimStart("  Fan@Example.COM ")).isEqualTo(ClaimResult.CodeSent)

        val req = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.path).isEqualTo("/claim/email/start")
        assertThat(req.getHeader("Content-Type")).startsWith("application/json")
        assertThat(req.body.readUtf8()).isEqualTo("""{"email":"fan@example.com"}""")
    }

    @Test fun `start sorts the tip jar's refusals`() = runBlocking {
        server.enqueue(json(400, """{"error":"bad_email"}"""))
        server.enqueue(json(429, """{"error":"rate_limited"}""").setHeader("Retry-After", "120"))
        server.enqueue(json(503, """{"error":"not_configured"}"""))
        server.enqueue(json(500, "oops"))

        assertThat(repo.claimStart("a@b.co")).isEqualTo(ClaimResult.BadEmail)
        assertThat(repo.claimStart("a@b.co")).isEqualTo(ClaimResult.RateLimited(120))
        assertThat(repo.claimStart("a@b.co")).isEqualTo(ClaimResult.NotConfigured)
        assertThat(repo.claimStart("a@b.co")).isEqualTo(ClaimResult.Unreachable)
    }

    @Test fun `an email that cannot be one is refused without a request`() = runBlocking {
        for (bad in listOf("", "fan", "fan@", "@x.com", "a@b@c.com", "fan@localhost", "fan @x.com")) {
            assertThat(repo.claimStart(bad)).isEqualTo(ClaimResult.BadEmail)
        }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `a right code stores the token`() = runBlocking {
        server.enqueue(json(200, """{"token":"eyJ0aWVyIjoic3VwcG9ydGVyIn0.c2ln"}"""))

        assertThat(repo.claimVerify("fan@example.com", "123 456")).isEqualTo(ClaimResult.Unlocked)
        assertThat(repo.tokenNow()).isEqualTo("eyJ0aWVyIjoic3VwcG9ydGVyIn0.c2ln")
        assertThat(repo.token.value).isEqualTo("eyJ0aWVyIjoic3VwcG9ydGVyIn0.c2ln")

        val req = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertThat(req.path).isEqualTo("/claim/email/verify")
        assertThat(req.body.readUtf8()).isEqualTo("""{"email":"fan@example.com","code":"123456"}""")
    }

    @Test fun `verify sorts wrong, used-up, expired and unconfigured answers and stores nothing`() = runBlocking {
        server.enqueue(json(401, """{"error":"bad_code"}"""))
        server.enqueue(json(429, """{"error":"too_many_tries"}"""))
        server.enqueue(json(410, """{"error":"expired"}"""))
        server.enqueue(json(503, """{"error":"not_configured"}"""))

        assertThat(repo.claimVerify("a@b.co", "111111")).isEqualTo(ClaimResult.BadCode)
        assertThat(repo.claimVerify("a@b.co", "111111")).isEqualTo(ClaimResult.StartOver)
        assertThat(repo.claimVerify("a@b.co", "111111")).isEqualTo(ClaimResult.StartOver)
        assertThat(repo.claimVerify("a@b.co", "111111")).isEqualTo(ClaimResult.NotConfigured)
        assertThat(repo.tokenNow()).isNull()
    }

    @Test fun `a code that is not six digits is refused without a request`() = runBlocking {
        assertThat(repo.claimVerify("a@b.co", "12345")).isEqualTo(ClaimResult.BadCode)
        assertThat(repo.claimVerify("a@b.co", "1234567")).isEqualTo(ClaimResult.BadCode)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `a 200 without a header-safe token is not stored`() = runBlocking {
        server.enqueue(json(200, """{"token":"bad\r\nX-Evil: 1"}"""))
        server.enqueue(json(200, """{"nope":1}"""))

        assertThat(repo.claimVerify("a@b.co", "123456")).isEqualTo(ClaimResult.Unreachable)
        assertThat(repo.claimVerify("a@b.co", "123456")).isEqualTo(ClaimResult.Unreachable)
        assertThat(repo.tokenNow()).isNull()
    }

    @Test fun `no tip-jar URL in the build is not configured, with no request`() = runBlocking {
        val bare = SupporterRepository(null, OkHttpClient(), "")
        assertThat(bare.claimStart("a@b.co")).isEqualTo(ClaimResult.NotConfigured)
        assertThat(bare.claimVerify("a@b.co", "123456")).isEqualTo(ClaimResult.NotConfigured)
    }

    @Test fun `an unreachable tip jar says so`() = runBlocking {
        server.shutdown()
        assertThat(repo.claimStart("a@b.co")).isEqualTo(ClaimResult.Unreachable)
    }

    @Test fun `the token survives a restart and clear removes it`() = runBlocking {
        val file = File(tmp.root, "supporter.preferences_pb")
        fun open(): SupporterRepository {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            return SupporterRepository(store, OkHttpClient(), server.url("/").toString(), scope)
        }
        val first = open()
        server.enqueue(json(200, """{"token":"tok.en"}"""))
        assertThat(first.claimVerify("a@b.co", "123456")).isEqualTo(ClaimResult.Unlocked)
        scopes.removeAt(0).coroutineContext[Job]!!.cancelAndJoin() // the "process" ends; one DataStore per file

        val second = open()
        assertThat(second.tokenNow()).isEqualTo("tok.en")
        second.clear()
        assertThat(second.tokenNow()).isNull()
        scopes.removeAt(0).coroutineContext[Job]!!.cancelAndJoin()

        assertThat(open().tokenNow()).isNull()
    }
}
