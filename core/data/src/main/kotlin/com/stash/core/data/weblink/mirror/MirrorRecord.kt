@file:UseSerializers(WireSongSerializer::class, HlcSerializer::class)

package com.stash.core.data.weblink.mirror

import android.content.Context
import android.util.AtomicFile
import com.stash.core.data.weblink.handoff.HandoffWire
import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.data.weblink.merge.Follow
import com.stash.core.model.weblink.Hlc
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject

/** A wire song kept as the wire writes it, read back with the wire's reader. */
object WireSongSerializer : KSerializer<WireSong> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(encoder: Encoder, value: WireSong) =
        (encoder as JsonEncoder).encodeJsonElement(HandoffWire.songJson(value))

    override fun deserialize(decoder: Decoder): WireSong {
        val e: JsonElement = (decoder as JsonDecoder).decodeJsonElement()
        return HandoffWire.readSong(e) ?: WireSong("?", "?")
    }
}

/** `[wall, counter, device]`, as on the wire. */
object HlcSerializer : KSerializer<Hlc> {
    override val descriptor: SerialDescriptor = JsonArray.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Hlc) = (encoder as JsonEncoder).encodeJsonElement(MirrorWire.hlcJson(value))

    override fun deserialize(decoder: Decoder): Hlc =
        MirrorWire.readHlc((decoder as JsonDecoder).decodeJsonElement()) ?: Hlc(0, 0, "d_unknown0")
}

@Serializable
data class FollowRec(val id: String, val version: Int) {
    fun follow() = Follow(id, version)

    companion object {
        fun of(f: Follow?) = f?.let { FollowRec(it.id, it.version) }
    }
}

// ---------------------------------------------------------------------------------------------- the view of the space

/** One like in the view: last writer wins by [at]; [seq] is the log position that last set it (snapshot records: its `uptoSeq`). */
@Serializable
data class ViewLike(val s: WireSong, val on: Boolean, val at: Hlc, val seq: Long)

/** One play in the view, with the device that played it. */
@Serializable
data class ViewPlay(val s: WireSong, val playedAt: Long, val device: String, val seq: Long)

/** A mirrored playlist's last version in the log ([items] null: deleted everywhere). */
@Serializable
data class ViewPl(
    val name: String,
    val items: List<WireSong>?,
    val follow: FollowRec? = null,
    val ro: Boolean = false,
    val at: Hlc,
    val hash: String,
    val seq: Long,
)

/**
 * The space as this phone has read it (sync-v1 §5.4, §7; the web's `mirror-view.ts`): every like, play and mirrored playlist any
 * device put in the log, whatever this phone mirrors itself. A snapshot is written from it; a first merge compares with it.
 * [joined] holds `"<kind>|<since>|<device>"` for every `joined` mark read.
 */
@Serializable
data class SpaceView(
    val likes: List<ViewLike> = emptyList(),
    val plays: List<ViewPlay> = emptyList(),
    val clearedBefore: Long = 0,
    val playlists: Map<String, ViewPl> = emptyMap(),
    val joined: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------------------------- what this phone keeps

@Serializable
data class BaseLike(val s: WireSong, val on: Boolean, val at: Hlc)

/**
 * The plays base: this phone's own plays the space has. A play that started before [floor] counts as in the base (the newest
 * 5,000 go on a first merge; after a push the floor moves up to two days ago), so [ids] stays small however long History is.
 */
@Serializable
data class BasePlays(val floor: Long = 0, val ids: Set<String> = emptySet(), val clearedBefore: Long = 0)

/** A mirrored playlist's version this phone last agreed on, with its hash. */
@Serializable
data class BasePl(
    val name: String,
    val items: List<WireSong>?,
    val follow: FollowRec? = null,
    val ro: Boolean = false,
    val at: Hlc,
    val hash: String,
)

/** The first-merge question for likes (spec §2.4): counts here and there, and whether this phone only receives. */
@Serializable
data class LikesQuestion(
    val oneWay: Boolean,
    val here: Int,
    val there: Int,
    val both: Int,
    val combined: Int,
    /** "Chrome on Windows", or "your browsers". */
    val thereName: String,
    val since: Long,
    /** Likes here only through Spotify / YouTube Music: a "use the browser's" can't remove those. */
    val externalHere: Int = 0,
)

@Serializable
data class LikesAnswer(val since: Long, val choice: FirstMergeChoice)

/** The first-merge answers: keep both, take the browsers' (`THEIRS`), or this phone's (`MINE`). */
enum class FirstMergeChoice { COMBINE, THEIRS, MINE }

@Serializable
data class PendingMark(val kind: Kind, val since: Long)

/** A mirrored playlist here: its local playlist and, for a copy of a followed shared mix, the follow it mirrors. */
@Serializable
data class MappedPl(val localId: Long, val follow: FollowRec? = null)

@Serializable
data class ClockSample(val sent: Long, val received: Long, val serverTime: Long)

/**
 * Everything this phone keeps for the mirror (spec §4.4): never synced, never in a backup (it lives in `no_backup/`, and
 * `data_extraction_rules.xml` excludes it), and it goes with the link. [configJson] is the newest settings applied (never an
 * older one: no rollback); [joined] maps a kind to the `since` this phone joined it under.
 */
@Serializable
data class MirrorRecord(
    val v: Int = 1,
    val spaceId: String,
    var seen: Long = 0,
    var snapUpto: Long = 0,
    var logBytes: Long = 0,
    var view: SpaceView = SpaceView(),
    var configJson: String? = null,
    var configServerAt: Long = 0,
    var last: Hlc? = null,
    var joined: Map<Kind, Long> = emptyMap(),
    var baseLikes: List<BaseLike> = emptyList(),
    var basePlays: BasePlays = BasePlays(),
    var basePlaylists: Map<String, BasePl> = emptyMap(),
    var question: LikesQuestion? = null,
    var answer: LikesAnswer? = null,
    var marks: List<PendingMark> = emptyList(),
    var waiting: Boolean = false,
    var newOnesFrom: Long? = null,
    /** Mirror id → this phone's playlist. */
    var map: Map<String, MappedPl> = emptyMap(),
    /** This phone's own history clear mark (raised only by a "Clear on all your devices" from another device). */
    var clearMark: Long = 0,
    var samples: List<ClockSample> = emptyList(),
    /** Log positions (or a snapshot's `uptoSeq`) sealed under a key this phone never held: no snapshot while any is open. */
    var gaps: List<Long> = emptyList(),
    /** For diagnostics: when the last run ended (local time), how, and when a batch last went up. */
    var lastRunAt: Long = 0,
    var lastResult: String? = null,
    var lastPushAt: Long = 0,
    /** The change signal's digests at the last run (spec §9: only a real change schedules a run). */
    var digest: String? = null,
)

/** Where [MirrorRecord] lives. */
interface MirrorStore {
    suspend fun load(): MirrorRecord?
    suspend fun save(r: MirrorRecord)
    suspend fun clear()
}

/**
 * [MirrorStore] as one gzipped JSON file in `no_backup/` (written atomically). A file, not a Room row: the view of a big space
 * is several MB, past what one cursor window can read back.
 */
@Singleton
class FileMirrorStore internal constructor(private val file: File) : MirrorStore {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, FILE_NAME))

    private val lock = Mutex()
    private val atomic = AtomicFile(file)

    override suspend fun load(): MirrorRecord? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!file.exists()) return@withLock null
            try {
                GZIPInputStream(atomic.openRead()).use { json.decodeFromString(MirrorRecord.serializer(), it.readBytes().decodeToString()) }
            } catch (e: Exception) {
                null // unreadable: start again (a fresh record joins every kind again, which is safe)
            }
        }
    }

    override suspend fun save(r: MirrorRecord) = withContext(Dispatchers.IO) {
        lock.withLock {
            file.parentFile?.mkdirs()
            val out = atomic.startWrite()
            try {
                val gz = GZIPOutputStream(out)
                gz.write(json.encodeToString(MirrorRecord.serializer(), r).encodeToByteArray())
                gz.finish()
                atomic.finishWrite(out)
            } catch (e: Exception) {
                atomic.failWrite(out)
                throw e
            }
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock { atomic.delete() }
    }

    companion object {
        const val FILE_NAME = "stash_mirror.json.gz"
        internal val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }
    }
}

/** A [MirrorStore] in memory (tests). */
class InMemoryMirrorStore : MirrorStore {
    var record: MirrorRecord? = null
    var saves = 0

    override suspend fun load(): MirrorRecord? = record?.let {
        // Through JSON, as the file store does, so a test sees exactly what survives a save.
        FileMirrorStore.json.decodeFromString(MirrorRecord.serializer(), FileMirrorStore.json.encodeToString(MirrorRecord.serializer(), it))
    }

    override suspend fun save(r: MirrorRecord) {
        record = FileMirrorStore.json.decodeFromString(MirrorRecord.serializer(), FileMirrorStore.json.encodeToString(MirrorRecord.serializer(), r))
        saves++
    }

    override suspend fun clear() {
        record = null
    }
}
