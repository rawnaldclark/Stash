package com.stash.feature.record.art

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Real covers for Your record and its Home card: stash-player `src/lib/spotify/art.ts` `createArt`, with the Worker's lookup
 * ([ArtLookup]) run here. A play from the record has only its words, so its album's cover is looked up by them.
 *
 * One cover per album (artist + album, folded: [ArtText.artKey]), kept ([ArtStore]) for [HIT_TTL_MS], a miss for
 * [MISS_TTL_MS]. Asked a batch of [BATCH] at a time, one batch at a time across the app (so an album two asks want at once is
 * looked up once: the second finds it known when its turn comes). The work runs off the main thread. A refusal (429, 5xx) or no answer
 * stops the asking for [PAUSE_MS] and keeps nothing: the covers come another time. A song Deezer wouldn't look up yet (its
 * quota: [Found.Later]) is asked again once, [LATER_MS] later, and its cover handed on when it comes.
 */
@Singleton
class RecordArt internal constructor(
    private val lookUp: suspend (ArtSong) -> Found,
    private val store: ArtStore,
    private val now: () -> Long,
    /** Where the work runs: off the main thread. */
    private val work: CoroutineContext = Dispatchers.Default,
) {
    @Inject
    constructor(client: OkHttpClient, @ApplicationContext context: Context) : this(
        lookUp = ArtLookup(OkHttpArtHttp(client))::lookUp,
        store = FileArtStore(File(context.noBackupFilesDir, STORE_FILE)),
        now = System::currentTimeMillis,
    )

    /** What this process knows: a cover, or null for none. Guarded by itself. */
    private val known = HashMap<String, String?>()

    @Volatile private var pausedUntil = 0L

    /** One batch at a time: the record and Home's card asking together don't double up. */
    private val asking = Mutex()

    /**
     * Each song's cover (kept, else looked up), or null. [onBatch] gets every song's cover known so far as each batch lands;
     * the songs to ask again later are asked in [retry] (the caller's scope: nothing is asked for a screen that's gone), and
     * [onBatch] hears of them too.
     */
    suspend fun covers(
        songs: List<ArtSong>,
        retry: CoroutineScope? = null,
        onBatch: ((List<String?>) -> Unit)? = null,
    ): List<String?> {
        // The work (folding, the store, parsing answers, matching hits) off the caller's thread; each batch told on it
        val caller = currentCoroutineContext()[ContinuationInterceptor] ?: EmptyCoroutineContext
        val tell: suspend (List<String?>) -> Unit = { sofar -> onBatch?.let { withContext(caller) { it(sofar) } } }
        return withContext(work) {
            val keys = songs.map(ArtText::artKey)
            val out = MutableList(songs.size) { i -> kept(keys[i]) }
            // The covers not known yet, once each, in the order they first appear
            val missing = LinkedHashMap<String, ArtSong>()
            for (i in songs.indices) if (!isKnown(keys[i]) && keys[i] !in missing) missing[keys[i]] = songs[i]
            if (missing.isEmpty()) return@withContext out.toList()
            tell(out.toList())
            val again = pass(missing.toList(), keys, out, tell)
            if (again.isNotEmpty() && retry != null) {
                retry.launch(work) {
                    delay(LATER_MS)
                    pass(again, keys, out, tell)
                }
            }
            out.toList()
        }
    }

    /** Asks for [list] a batch at a time, keeping what's found; returns those to ask again later. */
    private suspend fun pass(
        list: List<Pair<String, ArtSong>>,
        keys: List<String>,
        out: MutableList<String?>,
        tell: suspend (List<String?>) -> Unit,
    ): List<Pair<String, ArtSong>> {
        val again = ArrayList<Pair<String, ArtSong>>()
        for (part in list.chunked(BATCH)) {
            var stopped = false
            val paused = asking.withLock {
                // Found while this waited its turn (another ask had the same album in flight): not asked twice
                val todo = part.filter { !isKnown(it.first) }
                if (todo.isEmpty()) return@withLock false
                // Paused: nothing asked, nothing kept; the covers come another time
                val answers = ask(todo.map { it.second }) ?: return@withLock true
                todo.forEachIndexed { j, entry ->
                    when (val a = answers[j]) {
                        is Found.Cover -> remember(entry.first, a.url)
                        Found.Later -> again += entry
                        Found.Refused, null -> stopped = true
                    }
                }
                store.flush()
                false
            }
            if (paused) break
            for (i in keys.indices) knownUrl(keys[i])?.let { out[i] = it }
            tell(out.toList())
            if (stopped) break
        }
        return again
    }

    /** One batch, a few at a time (Deezer's own searches stay one at a time: [Pace]); null when paused. */
    private suspend fun ask(part: List<ArtSong>): List<Found?>? {
        if (now() < pausedUntil) return null
        val gate = Semaphore(CONCURRENCY)
        return coroutineScope {
            part.map { s ->
                async {
                    gate.withPermit {
                        // A refusal mid-batch: the rest wait for another time
                        if (now() < pausedUntil) {
                            null
                        } else {
                            one(s).also { if (it == Found.Refused) pausedUntil = now() + PAUSE_MS }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /** The Worker's reading of what it's asked (worker art.ts `handleArt`), then the lookup. Words it won't take: no cover. */
    private suspend fun one(s: ArtSong): Found {
        val artist = ArtText.clean(s.artist) ?: return Found.Cover(null)
        val song = if (s.isArtist) {
            ArtSong(title = artist, artist = artist, isArtist = true)
        } else {
            ArtSong(title = ArtText.clean(s.title) ?: return Found.Cover(null), artist = artist, album = ArtText.clean(s.album))
        }
        return try {
            lookUp(song)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "a cover lookup failed: ${e.javaClass.simpleName}")
            Found.Refused
        }
    }

    private fun isKnown(key: String) = synchronized(known) { known.containsKey(key) }

    private fun knownUrl(key: String) = synchronized(known) { known[key] }

    /** The cover kept for [key] (and now known here), or null: none, or nothing kept that's still fresh. */
    private suspend fun kept(key: String): String? {
        synchronized(known) { if (known.containsKey(key)) return known[key] }
        val h = store.get(key) ?: return null
        if (now() - h.at >= if (h.url != null) HIT_TTL_MS else MISS_TTL_MS) return null
        synchronized(known) { known[key] = h.url }
        return h.url
    }

    private suspend fun remember(key: String, url: String?) {
        synchronized(known) { known[key] = url }
        store.set(key, ArtHit(url, now()))
    }

    companion object {
        private const val TAG = "RecordArt"
        private const val STORE_FILE = "record-art.tsv"

        /** Covers per batch (art.ts `BATCH`). */
        const val BATCH = 25

        /** Lookups at once within a batch (the Worker's `CONCURRENCY`). */
        const val CONCURRENCY = 6

        /** No asking for this long after a refusal or no answer (art.ts `PAUSE_MS`). */
        const val PAUSE_MS = 60_000L

        /** Songs Deezer wouldn't look up yet are asked again after this long, once (art.ts `LATER_MS`). */
        const val LATER_MS = 15_000L

        const val HIT_TTL_MS = 180 * 86_400_000L
        const val MISS_TTL_MS = 3 * 86_400_000L
    }
}
