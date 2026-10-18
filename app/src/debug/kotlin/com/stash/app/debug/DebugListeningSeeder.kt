package com.stash.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.room.withTransaction
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.ListeningHistoryDao
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.listening.ListeningDay
import com.stash.core.data.listening.ListeningRange
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.pow
import kotlin.random.Random

/**
 * DEBUG BUILDS ONLY (app/src/debug). Fills listening history with synthetic listens so the
 * "Your listening" screens and queries can be tried at scale on a real phone, then takes them out
 * again. Never ships: the receiver is declared only in the debug manifest.
 *
 *   adb shell am broadcast -a com.stash.debug.SEED_PLAYS --ei count 60000 -p com.stash.app.debug
 *   adb shell am broadcast -a com.stash.debug.CLEAR_SEEDED_PLAYS -p com.stash.app.debug
 *   adb shell am broadcast -a com.stash.debug.TIME_LISTENING_QUERIES -p com.stash.app.debug
 *
 * Seeded rows are marked `origin_device = 'debug-seed'` ([ORIGIN]). That makes them plays "from
 * another device", which is exactly what keeps them harmless: they never change play_count, mixes,
 * auto-save, the mirror or any scrobble destination (`RemotePlaysDaoTest`), while History, Top,
 * Stats and Your Year show them. CLEAR deletes `origin_device = 'debug-seed'` and nothing else, so
 * the phone's real history is left exactly as it was. Logcat tag: [TAG].
 *
 * Listens are spread over ~3 years on existing track rows: a Zipf-ish favourite curve, ~20% of
 * days without music, an evening-heavy clock, and one in five with unknown listening time.
 */
class DebugListeningSeeder : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun database(): StashDatabase
    }

    override fun onReceive(context: Context, intent: Intent) {
        val db = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java).database()
        // Not goAsync(): seeding takes longer than a broadcast may hold the process. The work runs
        // in the app's process scope; keep the app open (it is, on the test rig) until the log says done.
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_SEED -> {
                        val count = intent.getIntExtra(EXTRA_COUNT, DEFAULT_COUNT).coerceIn(1, MAX_COUNT)
                        seed(db, count)
                        timeQueries(db.listeningHistoryDao())
                    }
                    ACTION_CLEAR -> {
                        val t0 = System.nanoTime()
                        val n = db.listeningHistoryDao().deleteFromOrigin(ORIGIN)
                        Log.i(TAG, "cleared $n seeded listens in ${ms(t0)} ms")
                    }
                    ACTION_TIME -> timeQueries(db.listeningHistoryDao())
                }
            } catch (e: Exception) {
                Log.e(TAG, "${intent.action} failed", e)
            }
        }
    }

    private suspend fun seed(db: StashDatabase, count: Int) {
        val t0 = System.nanoTime()
        val tracks = db.query("SELECT id, duration_ms FROM tracks", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to c.getLong(1)) }
        }.shuffled(Random(7))
        if (tracks.isEmpty()) {
            Log.w(TAG, "no tracks to seed onto")
            return
        }
        val rnd = Random(42)
        // Favourites: the n-th song is picked ~1/n^0.9 as often as the first.
        val cumulative = DoubleArray(tracks.size)
        var sum = 0.0
        for (i in tracks.indices) {
            sum += 1.0 / (i + 1).toDouble().pow(0.9)
            cumulative[i] = sum
        }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val nowMs = System.currentTimeMillis()
        val activeDays = (0 until DAYS).map { today.minusDays(it.toLong()) }.filter { rnd.nextDouble() < 0.8 }
        // Generated and written one chunk at a time: up to MAX_COUNT listens never sit in memory at once.
        val dao = db.listeningEventDao()
        var left = count
        while (left > 0) {
            val chunk = ArrayList<ListeningEventEntity>(minOf(BATCH, left))
            repeat(minOf(BATCH, left)) {
                val r = rnd.nextDouble() * sum
                var idx = cumulative.binarySearch(r)
                if (idx < 0) idx = -idx - 1
                val (trackId, durationMs) = tracks[idx.coerceIn(0, tracks.lastIndex)]
                val date = activeDays[rnd.nextInt(activeDays.size)]
                val time = LocalTime.of(pickHour(rnd), rnd.nextInt(60), rnd.nextInt(60))
                val at = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
                // Today's later hours haven't happened yet: such a listen goes on the day before.
                val startedAt = if (at > nowMs) at - ListeningDay.MS_PER_DAY else at
                val stamp = ListeningDay.localStamp(startedAt, zone)
                val length = if (durationMs > 0) durationMs else 180_000L
                val listened = (length * (0.5 + rnd.nextDouble() * 0.5)).toLong()
                chunk += ListeningEventEntity(
                    trackId = trackId,
                    startedAt = startedAt,
                    scrobbled = true,
                    ytScrobbled = true,
                    completedAt = startedAt + listened,
                    originDevice = ORIGIN,
                    localDay = stamp.day,
                    localMinute = stamp.minute,
                    listenedMs = if (rnd.nextInt(5) == 0) null else listened,
                )
            }
            db.withTransaction { for (e in chunk) dao.insert(e) }
            left -= chunk.size
        }
        Log.i(TAG, "seeded $count listens over ${activeDays.size} days on ${tracks.size} songs in ${ms(t0)} ms")
    }

    /** Times every query the listening screens run, over the whole history and the common ranges. */
    private suspend fun timeQueries(dao: ListeningHistoryDao) {
        val today = ListeningDay.today()
        val first = dao.firstListenDay()
        val total = dao.observeTotals(0, today).first()
        Log.i(TAG, "history: ${total.plays} listens, ${total.songs} songs, ${total.days} days, first day $first")
        for (range in listOf(ListeningRange.Days30, ListeningRange.Months12, ListeningRange.AllTime)) {
            val r = range.toDayRange(today, first)
            val label = range.label
            timed("dayCounts $label") { dao.observeDayCounts(r.fromDay, r.toDay).first().size }
            timed("topTracks $label") { dao.observeTopTracks(r.fromDay, r.toDay).first().size }
            timed("topArtists $label") { dao.observeTopArtists(r.fromDay, r.toDay).first().size }
            timed("topAlbums $label") { dao.observeTopAlbums(r.fromDay, r.toDay).first().size }
            timed("totals $label") { dao.observeTotals(r.fromDay, r.toDay).first().plays }
            timed("hours $label") { dao.observeHourHistogram(r.fromDay, r.toDay).first().size }
        }
        val year = ListeningRange.year(LocalDate.now().year, today)
        timed("newTracksIn year") { dao.newTracksIn(year.fromDay, year.toDay).size }
        timed("countNewTracksIn year") { dao.countNewTracksIn(year.fromDay, year.toDay) }
        timed("obsession year") { dao.obsession(year.fromDay, year.toDay)?.plays ?: 0 }
        timed("yearsWithListens") { dao.yearsWithListens().size }
        timed("firstListenDay") { if (dao.firstListenDay() == null) 0 else 1 }
        timed("history page 1") { dao.historyPage(Long.MAX_VALUE, Long.MAX_VALUE, null, 200).size }
        val page = dao.historyPage(Long.MAX_VALUE, Long.MAX_VALUE, null, 200)
        page.lastOrNull()?.event?.let { last ->
            timed("history page 2") { dao.historyPage(last.startedAt, last.id, null, 200).size }
        }
        timed("history search 'the'") { dao.historyPage(Long.MAX_VALUE, Long.MAX_VALUE, "%the%", 200).size }
        timed("history search miss") { dao.historyPage(Long.MAX_VALUE, Long.MAX_VALUE, "%zzqx%", 200).size }
    }

    private suspend fun timed(name: String, block: suspend () -> Int) {
        val t0 = System.nanoTime()
        val n = block()
        Log.i(TAG, "query $name: ${ms(t0)} ms ($n rows)")
    }

    /** An evening-heavy hour of the day. */
    private fun pickHour(rnd: Random): Int {
        val r = rnd.nextDouble() * HOUR_WEIGHTS.sum()
        var acc = 0.0
        for (h in 0 until 24) {
            acc += HOUR_WEIGHTS[h]
            if (r < acc) return h
        }
        return 23
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    companion object {
        private const val TAG = "DebugListeningSeeder"
        const val ACTION_SEED = "com.stash.debug.SEED_PLAYS"
        const val ACTION_CLEAR = "com.stash.debug.CLEAR_SEEDED_PLAYS"
        const val ACTION_TIME = "com.stash.debug.TIME_LISTENING_QUERIES"
        const val EXTRA_COUNT = "count"

        /** The `origin_device` of every seeded listen: the only rows CLEAR deletes. */
        const val ORIGIN = "debug-seed"

        private const val DEFAULT_COUNT = 60_000
        private const val MAX_COUNT = 500_000
        private const val DAYS = 3 * 365
        private const val BATCH = 2_000

        private val HOUR_WEIGHTS = doubleArrayOf(
            2.0, 1.0, 0.5, 0.3, 0.2, 0.3, 1.0, 3.0, 5.0, 5.0, 4.0, 4.0,
            5.0, 5.0, 4.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 9.0, 7.0, 4.0,
        )

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
