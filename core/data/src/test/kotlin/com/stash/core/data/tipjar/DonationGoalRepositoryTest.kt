package com.stash.core.data.tipjar

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Support card's goal: reading /goal.json, one good answer a UTC day, an hour's wait after a failed ask, only
 * this month's total, and never holding the card back while the site is slow.
 */
class DonationGoalRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val scopes = mutableListOf<CoroutineScope>()
    @Volatile private var now = ms("2026-11-01T10:00:00Z")

    @Before fun setUp() = server.start()

    @After fun tearDown() {
        server.shutdown()
        scopes.forEach { it.cancel() }
    }

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val hour = DonationGoals.RETRY_AFTER_FAILURE_MS

    private fun goalJson(raised: Long, month: String = "2026-11") =
        MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"raised_cents":$raised,"goal_cents":20000,"month":"$month"}""")

    private fun repo(file: File? = null): DonationGoalRepository {
        val store = file?.let {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { s -> scopes += s }
            PreferenceDataStoreFactory.create(scope = scope) { it }
        }
        return DonationGoalRepository(store, OkHttpClient(), server.url("/goal.json").toString(), clock = { now })
    }

    // ── Parsing ─────────────────────────────────────────────────────────────

    @Test fun `the site's answer reads as a goal`() {
        assertThat(DonationGoals.parse("""{"raised_cents":16800,"goal_cents":20000,"month":"2026-11"}"""))
            .isEqualTo(DonationGoal(16800, 20000, "2026-11"))
        // Fields the site may add later don't matter.
        assertThat(DonationGoals.parse("""{"raised_cents":0,"goal_cents":20000,"month":"2026-12","currency":"USD"}"""))
            .isEqualTo(DonationGoal(0, 20000, "2026-12"))
    }

    @Test fun `anything that isn't a whole goal reads as none`() {
        for (bad in listOf(
            "",
            "not json",
            "[]",
            """{"error":"unavailable"}""",
            """{"goal_cents":20000,"month":"2026-11"}""",
            """{"raised_cents":100,"month":"2026-11"}""",
            """{"raised_cents":100,"goal_cents":20000}""",
            """{"raised_cents":-1,"goal_cents":20000,"month":"2026-11"}""",
            """{"raised_cents":100,"goal_cents":0,"month":"2026-11"}""",
            """{"raised_cents":100,"goal_cents":20000,"month":"2026-13"}""",
            """{"raised_cents":100,"goal_cents":20000,"month":"November"}""",
            """{"raised_cents":"lots","goal_cents":20000,"month":"2026-11"}""",
            """{"raised_cents":12.5,"goal_cents":20000,"month":"2026-11"}""",
        )) {
            assertThat(DonationGoals.parse(bad)).isNull()
        }
    }

    // ── When to ask ─────────────────────────────────────────────────────────

    @Test fun `one good answer a UTC day`() {
        assertThat(DonationGoals.fetchDue(0L, 0L, now)).isTrue()
        assertThat(DonationGoals.fetchDue(ms("2026-11-01T00:00:00Z"), 0L, ms("2026-11-01T23:59:59Z"))).isFalse()
        // Twenty minutes later, but a new day: and a new day can be a new month.
        assertThat(DonationGoals.fetchDue(ms("2026-10-31T23:50:00Z"), 0L, ms("2026-11-01T00:10:00Z"))).isTrue()
        // A clock set back to an earlier day asks again rather than never.
        assertThat(DonationGoals.fetchDue(ms("2026-11-05T10:00:00Z"), 0L, ms("2026-11-01T10:00:00Z"))).isTrue()
    }

    @Test fun `a failed ask waits an hour, not a day`() {
        val failed = ms("2026-11-01T09:00:00Z")
        assertThat(DonationGoals.fetchDue(0L, failed, failed + 59 * 60_000L)).isFalse()
        assertThat(DonationGoals.fetchDue(0L, failed, failed + hour)).isTrue()
        // A good answer yesterday doesn't stop today's ask, but this morning's failure still spaces it.
        assertThat(DonationGoals.fetchDue(ms("2026-10-31T09:00:00Z"), failed, failed + 30 * 60_000L)).isFalse()
        // A failure stamped in the future (the clock went back) doesn't block.
        assertThat(DonationGoals.fetchDue(0L, failed, failed - hour)).isTrue()
    }

    @Test fun `only this UTC month's goal is shown`() {
        val nov = DonationGoal(16800, 20000, "2026-11")
        assertThat(DonationGoals.forMonth(nov, ms("2026-11-30T23:59:59Z"))).isEqualTo(nov)
        assertThat(DonationGoals.forMonth(nov, ms("2026-12-01T00:00:00Z"))).isNull()
        assertThat(DonationGoals.forMonth(null, now)).isNull()
        assertThat(DonationGoals.utcMonth(ms("2026-01-05T00:00:00Z"))).isEqualTo("2026-01")
    }

    @Test fun `the month is written in ISO digits whatever the phone's language`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertThat(DonationGoals.utcMonth(now)).isEqualTo("2026-11")
            assertThat(DonationGoals.forMonth(DonationGoal(1, 20000, "2026-11"), now)).isNotNull()
        } finally {
            Locale.setDefault(before)
        }
    }

    // ── The repository ──────────────────────────────────────────────────────

    @Test fun `a good answer is the day's, the next one comes tomorrow`() = runBlocking {
        server.enqueue(goalJson(16800))
        val repo = repo()
        assertThat(repo.goal.first()).isNull()

        assertThat(repo.refreshIfDue()).isTrue()
        assertThat(repo.goal.first()).isEqualTo(DonationGoal(16800, 20000, "2026-11"))
        assertThat(server.takeRequest().path).isEqualTo("/goal.json")

        now += 6 * hour
        assertThat(repo.refreshIfDue()).isFalse()
        assertThat(server.requestCount).isEqualTo(1)

        server.enqueue(goalJson(18000))
        now = ms("2026-11-02T00:05:00Z")
        assertThat(repo.refreshIfDue()).isTrue()
        assertThat(repo.goal.first()?.raisedCents).isEqualTo(18000)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `a failed ask keeps the last good goal and is tried again an hour later`() = runBlocking {
        server.enqueue(goalJson(16800))
        val repo = repo()
        repo.refreshIfDue()

        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"unavailable"}"""))
        now = ms("2026-11-02T09:00:00Z")
        assertThat(repo.refreshIfDue()).isTrue()
        assertThat(repo.goal.first()?.raisedCents).isEqualTo(16800)

        now += 30 * 60_000L
        assertThat(repo.refreshIfDue()).isFalse()
        assertThat(server.requestCount).isEqualTo(2)

        server.enqueue(goalJson(17500))
        now = ms("2026-11-02T10:00:00Z")
        assertThat(repo.refreshIfDue()).isTrue()
        assertThat(repo.goal.first()?.raisedCents).isEqualTo(17500)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun `a reply that isn't a goal changes nothing and is tried again an hour later`() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>gate</html>"))
        val repo = repo()
        assertThat(repo.refreshIfDue()).isTrue()
        assertThat(repo.goal.first()).isNull()

        server.enqueue(goalJson(500))
        now += hour
        assertThat(repo.refreshIfDue()).isTrue()
        assertThat(repo.goal.first()?.raisedCents).isEqualTo(500)
    }

    @Test fun `last month's goal from the site is no answer, and is tried again an hour later`() = runBlocking {
        // Just after midnight UTC on the 1st, an edge could still hold October's answer for a few minutes.
        now = ms("2026-11-01T00:02:00Z")
        server.enqueue(goalJson(19000, month = "2026-10"))
        val repo = repo()
        assertThat(repo.refreshIfDue()).isTrue()
        assertThat(repo.goal.first()).isNull()

        now += 20 * 60_000L
        assertThat(repo.refreshIfDue()).isFalse()

        server.enqueue(goalJson(0))
        now = ms("2026-11-01T01:02:00Z")
        assertThat(repo.refreshIfDue()).isTrue()
        assertThat(repo.goal.first()).isEqualTo(DonationGoal(0, 20000, "2026-11"))
    }

    @Test fun `a goal kept from last month isn't shown as this month's`() = runBlocking {
        server.enqueue(goalJson(19000, month = "2026-10"))
        now = ms("2026-10-31T22:00:00Z")
        val repo = repo()
        repo.refreshIfDue()
        assertThat(repo.goal.first()?.raisedCents).isEqualTo(19000)

        now = ms("2026-11-01T00:30:00Z")
        assertThat(repo.goal.first()).isNull()
    }

    @Test fun `offline, there is no goal, no crash, and another try an hour later`() = runBlocking {
        // An address nothing answers at: a server that has stopped.
        val gone = MockWebServer().apply { start() }
        val url = gone.url("/goal.json").toString()
        gone.shutdown()
        val repo = DonationGoalRepository(null, OkHttpClient(), url, clock = { now })
        assertThat(repo.refreshIfDue()).isTrue()
        assertThat(repo.goal.first()).isNull()

        now += 10 * 60_000L
        assertThat(repo.refreshIfDue()).isFalse()
        now += hour
        assertThat(repo.refreshIfDue()).isTrue()
    }

    @Test fun `the stored goal answers at once while a slow reply is still on its way`() = runBlocking {
        val file = File(tmp.root, "donation_goal.preferences_pb")
        server.enqueue(goalJson(16800))
        repo(file).refreshIfDue()
        server.takeRequest() // yesterday's, so the next take is today's slow one
        scopes.removeAt(0).coroutineContext[Job]!!.cancelAndJoin() // the "process" ends; one DataStore per file

        // The next day, Settings opens and the site is slow.
        now = ms("2026-11-02T09:00:00Z")
        server.enqueue(goalJson(18000).setHeadersDelay(2, TimeUnit.SECONDS))
        val repo = repo(file)
        val asking = async(Dispatchers.IO) { repo.refreshIfDue() }
        assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull() // the ask is out, its reply not back

        // The card gets yesterday's number straight away, not after the network answers.
        assertThat(withTimeout(1_000) { repo.goal.first() }).isEqualTo(DonationGoal(16800, 20000, "2026-11"))
        // And a second opening meanwhile doesn't send another ask.
        assertThat(withTimeout(1_000) { repo.refreshIfDue() }).isFalse()

        assertThat(asking.await()).isTrue()
        assertThat(repo.goal.first()?.raisedCents).isEqualTo(18000)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `a cancelled ask stamps nothing, so the next one goes out`() = runBlocking {
        server.enqueue(goalJson(16800).setHeadersDelay(3, TimeUnit.SECONDS))
        val repo = repo()
        val job = launch(Dispatchers.IO) { repo.refreshIfDue() }
        assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull()
        // Leaving Settings: the call is cancelled with the coroutine, without waiting out the slow reply.
        withTimeout(2_000) { job.cancelAndJoin() }

        server.enqueue(goalJson(16900))
        assertThat(repo.refreshIfDue()).isTrue()
        assertThat(repo.goal.first()?.raisedCents).isEqualTo(16900)
    }

    @Test fun `the goal and the day's answer survive a restart`() = runBlocking {
        val file = File(tmp.root, "donation_goal.preferences_pb")
        server.enqueue(goalJson(16800))
        val first = repo(file)
        first.refreshIfDue()
        scopes.removeAt(0).coroutineContext[Job]!!.cancelAndJoin()

        val second = repo(file)
        // The stored goal straight away, and no second ask the same day.
        assertThat(second.goal.first()).isEqualTo(DonationGoal(16800, 20000, "2026-11"))
        assertThat(second.refreshIfDue()).isFalse()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `a failure survives a restart too`() = runBlocking {
        val file = File(tmp.root, "donation_goal.preferences_pb")
        server.enqueue(MockResponse().setResponseCode(500))
        repo(file).refreshIfDue()
        scopes.removeAt(0).coroutineContext[Job]!!.cancelAndJoin()

        now += 10 * 60_000L
        assertThat(repo(file).refreshIfDue()).isFalse()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `a build without a goal address asks nothing and shows nothing`() = runBlocking {
        val repo = DonationGoalRepository(null, OkHttpClient(), "", clock = { now })
        assertThat(repo.refreshIfDue()).isTrue()
        assertThat(repo.goal.first()).isNull()
    }
}
