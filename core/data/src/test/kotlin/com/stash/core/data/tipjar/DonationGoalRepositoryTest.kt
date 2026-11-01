package com.stash.core.data.tipjar

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The Support card's goal: reading /goal.json, asking at most once a UTC day, and only this month's total. */
class DonationGoalRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val scopes = mutableListOf<CoroutineScope>()
    private var now = ms("2026-11-01T10:00:00Z")

    @Before fun setUp() = server.start()

    @After fun tearDown() {
        server.shutdown()
        scopes.forEach { it.cancel() }
    }

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

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

    @Test fun `it asks once per UTC day, failed asks included`() {
        assertThat(DonationGoals.fetchDue(0L, now)).isTrue()
        assertThat(DonationGoals.fetchDue(ms("2026-11-01T00:00:00Z"), ms("2026-11-01T23:59:59Z"))).isFalse()
        // Twenty minutes later, but a new day: and a new day can be a new month.
        assertThat(DonationGoals.fetchDue(ms("2026-10-31T23:50:00Z"), ms("2026-11-01T00:10:00Z"))).isTrue()
        // A clock set back to an earlier day asks again rather than never.
        assertThat(DonationGoals.fetchDue(ms("2026-11-05T10:00:00Z"), ms("2026-11-01T10:00:00Z"))).isTrue()
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

    @Test fun `the first ask of the day fetches, the next ones that day don't`() = runBlocking {
        server.enqueue(goalJson(16800))
        val repo = repo()
        assertThat(repo.goal.first()).isNull()

        assertThat(repo.refreshIfDue()).isTrue()
        assertThat(repo.goal.first()).isEqualTo(DonationGoal(16800, 20000, "2026-11"))
        assertThat(server.takeRequest().path).isEqualTo("/goal.json")

        now += 6 * 3_600_000L
        assertThat(repo.refreshIfDue()).isFalse()
        assertThat(server.requestCount).isEqualTo(1)

        server.enqueue(goalJson(18000))
        now = ms("2026-11-02T00:05:00Z")
        assertThat(repo.refreshIfDue()).isTrue()
        assertThat(repo.goal.first()?.raisedCents).isEqualTo(18000)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `a failed ask keeps the last good goal and still counts as the day's ask`() = runBlocking {
        server.enqueue(goalJson(16800))
        val repo = repo()
        repo.refreshIfDue()

        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"unavailable"}"""))
        now = ms("2026-11-02T09:00:00Z")
        assertThat(repo.refreshIfDue()).isTrue()
        assertThat(repo.goal.first()?.raisedCents).isEqualTo(16800)

        now += 3_600_000L
        assertThat(repo.refreshIfDue()).isFalse()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `a reply that isn't a goal changes nothing`() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>gate</html>"))
        val repo = repo()
        assertThat(repo.refreshIfDue()).isTrue()
        assertThat(repo.goal.first()).isNull()
    }

    @Test fun `offline, there is no goal and no crash`() = runBlocking {
        // An address nothing answers at: a server that has stopped.
        val gone = MockWebServer().apply { start() }
        val url = gone.url("/goal.json").toString()
        gone.shutdown()
        val repo = DonationGoalRepository(null, OkHttpClient(), url, clock = { now })
        assertThat(repo.refreshIfDue()).isTrue()
        assertThat(repo.goal.first()).isNull()
    }

    @Test fun `last month's goal isn't shown as this month's`() = runBlocking {
        server.enqueue(goalJson(19000, month = "2026-10"))
        now = ms("2026-10-31T22:00:00Z")
        val repo = repo()
        repo.refreshIfDue()
        assertThat(repo.goal.first()?.raisedCents).isEqualTo(19000)

        now = ms("2026-11-01T00:30:00Z")
        assertThat(repo.goal.first()).isNull()
    }

    @Test fun `the goal and the day's ask survive a restart`() = runBlocking {
        val file = File(tmp.root, "donation_goal.preferences_pb")
        server.enqueue(goalJson(16800))
        val first = repo(file)
        first.refreshIfDue()
        scopes.removeAt(0).coroutineContext[Job]!!.cancelAndJoin() // the "process" ends; one DataStore per file

        val second = repo(file)
        // The stored goal straight away, and no second ask the same day.
        assertThat(second.goal.first()).isEqualTo(DonationGoal(16800, 20000, "2026-11"))
        assertThat(second.refreshIfDue()).isFalse()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `a build without a goal address asks nothing and shows nothing`() = runBlocking {
        val repo = DonationGoalRepository(null, OkHttpClient(), "", clock = { now })
        assertThat(repo.refreshIfDue()).isTrue()
        assertThat(repo.goal.first()).isNull()
    }
}
