package com.stash.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.util.AtomicFile
import com.stash.core.media.PlaybackResumer
import com.stash.core.media.widget.NowPlayingWidgetState
import com.stash.core.media.widget.WidgetCoverPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What [NowPlayingWidgetStore.refreshCover] did. */
enum class CoverRefresh {
    /** Nothing to fetch: the cover on hand is the song's, it has none, or its URL failed for good. */
    UP_TO_DATE,

    /** The song's cover arrived: redraw. */
    LOADED,

    /** The song changed during the fetch: try again for the new one. */
    STALE,

    /** The fetch failed (a timeout or a lost connection is tried again on the next update). */
    UNAVAILABLE,
}

/** What the widget draws: the song (null = nothing playing), its cover and its colours. */
data class WidgetModel(
    val state: NowPlayingWidgetState?,
    val cover: Bitmap?,
    val accents: WidgetAccents,
) {
    companion object {
        val Empty = WidgetModel(state = null, cover = null, accents = WidgetAccents.Brand)
    }
}

/**
 * The widget's state, kept on disk (`files/now_playing_widget/`: a small JSON file plus the cover as
 * a PNG) so a widget drawn in a fresh process (the launcher restarting, the system re-binding
 * widgets after a reboot, process death) still shows the last song, and in memory as a flow the
 * running Glance composition follows.
 *
 * The first read in a process with no file yet (the update that adds the widget) seeds it from the
 * saved queue, the song the widget's Play resumes, so a newly placed widget shows the last song
 * instead of "Nothing playing".
 *
 * The song's text is stored and shown at once ([publish]); its cover follows ([refreshCover]), fetched
 * outside the lock, so a slow or failing fetch never holds the title back.
 *
 * All access is serialized by one mutex and runs on [Dispatchers.IO].
 */
@Singleton
class NowPlayingWidgetStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coverLoader: WidgetCoverLoader,
    private val resumer: PlaybackResumer,
) {
    @Serializable
    private data class Stored(
        val state: NowPlayingWidgetState? = null,
        /** The artUrl [coverFile] was loaded from; the cover only counts while it equals the state's. */
        val coverKey: String? = null,
        val accents: WidgetAccents? = null,
    )

    private val dir = File(context.filesDir, "now_playing_widget")
    private val stateFile = AtomicFile(File(dir, "state.json"))
    private val coverFile = File(dir, "cover.png")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private val model = MutableStateFlow<WidgetModel?>(null)

    // Guarded by mutex.
    private var stored: Stored? = null
    private var coverBitmap: Bitmap? = null

    /**
     * A cover URL that failed for good in this process (a 404, not a picture): not retried on every
     * update. A timeout or a lost connection is never recorded here, so the next update tries again.
     */
    private var failedCoverKey: String? = null

    /** Every model after the first [current]; the running widget composition collects it. */
    val models: Flow<WidgetModel> = model.filterNotNull()

    /** The model to draw now (loaded from disk on first use). Never fetches: see [refreshCover]. */
    suspend fun current(): WidgetModel = locked { emit() }

    /**
     * Stores [state] and shows it at once, before any cover fetch: a new song's title replaces the
     * old one now, over the placeholder until its cover lands ([refreshCover]). Returns whether
     * anything the widget draws changed.
     */
    suspend fun publish(state: NowPlayingWidgetState): Boolean = locked {
        val before = stored!!
        if (before.state == state) return@locked false
        stored = before.copy(state = state)
        save()
        emit()
        true
    }

    /**
     * Fetches the stored song's cover if it isn't on hand ([WidgetCoverPolicy.wanted]). The fetch
     * runs outside the lock, so publishes go on meanwhile; its result is kept only if the song is
     * still the same.
     */
    suspend fun refreshCover(): CoverRefresh {
        val url = locked {
            val s = stored!!
            WidgetCoverPolicy.wanted(s.state?.artUrl, s.coverKey, haveCover = coverBitmap != null, failedKey = failedCoverKey)
        } ?: return CoverRefresh.UP_TO_DATE
        val result = coverLoader.load(url)
        return locked { applyCover(url, result) }
    }

    /** The service stopped: the stored song is paused. Returns whether that changed anything. */
    suspend fun markPaused(): Boolean = locked {
        val before = stored!!
        val state = before.state?.takeIf { it.isPlaying } ?: return@locked false
        stored = before.copy(state = state.copy(isPlaying = false))
        save()
        emit()
        true
    }

    /** Whether any Stash widget is on a home screen right now. */
    fun hasPlacedWidgets(): Boolean = try {
        val manager = AppWidgetManager.getInstance(context)
        RECEIVERS.any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
    } catch (e: Exception) {
        false // no widget service on this device (some TV / car builds)
    }

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            block()
        }
    }

    private suspend fun ensureLoaded() {
        if (stored != null) return
        val loaded = read()
        if (loaded != null) {
            stored = loaded
        } else {
            stored = seedFromSavedQueue()
            save()
        }
        val s = stored!!
        coverBitmap = if (s.coverKey != null && s.coverKey == s.state?.artUrl) decode() else null
    }

    /** Keeps a fetched cover if [url] is still the stored song's. Under the lock. */
    private fun applyCover(url: String, result: WidgetCoverResult): CoverRefresh {
        val s = stored!!
        if (s.state?.artUrl != url) return CoverRefresh.STALE
        return when (result) {
            is WidgetCoverResult.Loaded -> {
                coverBitmap = result.cover.bitmap
                stored = s.copy(coverKey = url, accents = result.cover.accents)
                writeCover(result.cover.bitmap)
                save()
                emit()
                CoverRefresh.LOADED
            }
            is WidgetCoverResult.Failed -> {
                // The widget keeps the placeholder either way; only a lasting failure stops the retries.
                if (result.lasting) failedCoverKey = url
                CoverRefresh.UNAVAILABLE
            }
        }
    }

    private fun emit(): WidgetModel {
        val s = stored!!
        val coverIsCurrent = s.coverKey != null && s.coverKey == s.state?.artUrl
        val next = WidgetModel(
            state = s.state,
            cover = if (coverIsCurrent) coverBitmap else null,
            accents = if (coverIsCurrent) s.accents ?: WidgetAccents.Brand else WidgetAccents.Brand,
        )
        model.value = next
        return next
    }

    private suspend fun seedFromSavedQueue(): Stored = try {
        val plan = resumer.buildResumePlan()
        val track = plan?.tracks?.getOrNull(plan.startIndex)
        if (track == null || track.title.isBlank()) {
            Stored()
        } else {
            Stored(
                state = NowPlayingWidgetState(
                    title = track.title.trim(),
                    artist = track.artist.trim(),
                    // The cover the service's resumed item shows (toAutoMediaItem).
                    artUrl = (track.albumArtUrl ?: track.albumArtPath)?.takeIf { it.isNotBlank() },
                    isPlaying = false,
                ),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "seed from the saved queue failed: ${e.javaClass.simpleName}")
        Stored()
    }

    private fun read(): Stored? = try {
        if (!stateFile.baseFile.exists()) null else json.decodeFromString(Stored.serializer(), stateFile.readFully().decodeToString())
    } catch (e: Exception) {
        Log.w(TAG, "widget state unreadable, starting over: ${e.javaClass.simpleName}")
        null
    }

    private fun save() {
        val s = stored ?: return
        dir.mkdirs()
        val out = stateFile.startWrite()
        try {
            out.write(json.encodeToString(Stored.serializer(), s).encodeToByteArray())
            stateFile.finishWrite(out)
        } catch (e: Exception) {
            stateFile.failWrite(out)
            Log.w(TAG, "widget state not saved: ${e.javaClass.simpleName}")
        }
    }

    private fun decode(): Bitmap? = try {
        if (coverFile.exists()) BitmapFactory.decodeFile(coverFile.path) else null
    } catch (e: Exception) {
        null
    }

    private fun writeCover(bitmap: Bitmap) {
        dir.mkdirs()
        val tmp = File(dir, "cover.png.tmp")
        try {
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(coverFile)) {
                coverFile.delete()
                tmp.renameTo(coverFile)
            }
        } catch (e: Exception) {
            tmp.delete()
            Log.w(TAG, "widget cover not saved: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "StashWidget"

        /** Every receiver that hosts [NowPlayingWidget]. */
        val RECEIVERS = listOf(NowPlayingWidgetReceiver::class.java, NowPlayingSquareWidgetReceiver::class.java)
    }
}
