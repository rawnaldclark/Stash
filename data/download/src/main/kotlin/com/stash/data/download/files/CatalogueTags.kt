package com.stash.data.download.files

import android.os.SystemClock
import android.util.Log
import com.stash.core.model.Track
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * [ExtraTags] from the iTunes Search API: a public catalogue that needs
 * no account and answers with a recording's album, release date, track
 * and disc number and genre in one request.
 *
 * It is asked once per file being tagged, with the track's first artist
 * and title, and only when the file lacks something it could add. The
 * API allows about twenty requests a minute, so requests are spaced out
 * ([paceMs]); a file whose turn would come too late ([maxWaitMs]) is
 * tagged without, as is every file for a while after the catalogue
 * turns a request away. Tagging never fails because of it.
 */
@Singleton
class CatalogueTags @Inject constructor(sharedClient: OkHttpClient) : ExtraTagSource {
    private val httpClient = sharedClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Test seams. */
    internal var baseUrl = "https://itunes.apple.com"
    internal var paceMs = 3_200L
    internal var maxWaitMs = 20_000L
    internal var coolOffMs = 120_000L
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    private val turn = Mutex()
    private var nextAt = 0L
    @Volatile private var quietUntil = 0L

    override suspend fun lookUp(track: Track): ExtraTags? {
        val query = CatalogueMatch.query(track)
        if (query.isBlank() || clock() < quietUntil) return null
        return try {
            withTimeoutOrNull(maxWaitMs) {
                turn.withLock {
                    val wait = nextAt - clock()
                    if (wait > 0) delay(wait)
                    nextAt = clock() + paceMs
                    CatalogueMatch.pick(track, search(query))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "catalogue lookup failed: ${e.message}")
            null
        }
    }

    private suspend fun search(query: String): List<CatalogueHit> = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("term", query)
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "50")
            .build()
        val call = httpClient.newCall(Request.Builder().url(url).get().build())
        runInterruptible {
            call.execute().use { response ->
                if (response.code == 403 || response.code == 429) {
                    // Asked too fast for the catalogue's liking: leave it alone for a while.
                    quietUntil = clock() + coolOffMs
                    return@use emptyList()
                }
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string().orEmpty()
                JSON.decodeFromString<Answer>(body).results
                    .filter { it.kind.isEmpty() || it.kind == "song" }
                    .map {
                        CatalogueHit(
                            title = it.trackName,
                            artist = it.artistName,
                            album = it.collectionName,
                            albumArtist = it.collectionArtistName,
                            releaseDate = it.releaseDate,
                            trackNumber = it.trackNumber,
                            discNumber = it.discNumber,
                            genre = it.primaryGenreName,
                            durationMs = it.trackTimeMillis,
                        )
                    }
            }
        }
    }

    @Serializable
    private data class Answer(val results: List<Hit> = emptyList())

    @Serializable
    private data class Hit(
        val kind: String = "",
        val trackName: String = "",
        val artistName: String = "",
        val collectionName: String = "",
        val collectionArtistName: String = "",
        val releaseDate: String = "",
        val trackNumber: Int = 0,
        val discNumber: Int = 0,
        val primaryGenreName: String = "",
        val trackTimeMillis: Long = 0,
    )

    private companion object {
        const val TAG = "CatalogueTags"
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
