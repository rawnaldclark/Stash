package com.stash.core.data.weblink.merge

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.stash.core.data.weblink.SyncVectors
import com.stash.core.data.weblink.get
import com.stash.core.data.weblink.has
import com.stash.core.data.weblink.hlcOf
import com.stash.core.data.weblink.i
import com.stash.core.data.weblink.l
import com.stash.core.data.weblink.list
import com.stash.core.data.weblink.s
import com.stash.core.data.weblink.songOf
import com.stash.core.model.weblink.Hlc
import com.stash.core.model.weblink.SongRef
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.util.IdentityHashMap

/** The shared merge vectors of sync-v1 (`merge-vectors.json`, see [SyncVectors]): likes, plays, playlists, hashes, first merge. */
class SyncMergeVectorsTest {
    private val v = SyncVectors.load("merge-vectors.json")

    /** One object per song name; results map back by identity, so the test sees which side's copy of a song was kept. */
    private val songs: Map<String, SongRef> = v["songs"].jsonObject.mapValues { songOf(it.value) }
    private val names = IdentityHashMap<SongRef, String>().apply { songs.forEach { (k, s) -> put(s, k) } }
    private fun song(e: JsonElement) = songs.getValue(e.s)
    private fun name(s: SongRef) = names[s] ?: "?$s"

    private fun like(e: JsonElement) = LikeRec(song(e["s"]), e["on"].jsonPrimitive.boolean, hlcOf(e["at"])!!)
    private fun likeOut(r: LikeRec<SongRef>) = Triple(name(r.s), r.on, r.at)
    private fun likeExpect(e: JsonElement) = Triple(e["s"].s, e["on"].jsonPrimitive.boolean, hlcOf(e["at"])!!)

    @Test fun `the vector files are the pinned ones`() {
        assertThat(SyncVectors.sha256(SyncVectors.bytes("merge-vectors.json"))).isEqualTo(SyncVectors.MERGE_SHA256)
        assertThat(SyncVectors.sha256(SyncVectors.bytes("crypto-vectors.json"))).isEqualTo(SyncVectors.CRYPTO_SHA256)
    }

    @Test fun `likes - apply`() {
        for (c in v["likes"]["apply"].list) {
            val got = LikesMerge.apply(c["state"].list.map(::like), c["ops"].list.map(::like)).map(::likeOut)
            assertWithMessage(c["name"].s).that(got).isEqualTo(c["expect"].list.map(::likeExpect))
        }
    }

    @Test fun `likes - diff`() {
        for (c in v["likes"]["diff"].list) {
            val liked = c["liked"].list.map { LikedSong(song(it["s"]), it.has("external") && it["external"].jsonPrimitive.boolean) }
            val got = LikesMerge.diff(c["base"].list.map(::like), liked, hlcOf(c["at"])!!).map(::likeOut)
            assertWithMessage(c["name"].s).that(got).isEqualTo(c["expect"].list.map(::likeExpect))
        }
    }

    private fun play(e: JsonElement) = PlayRec(song(e["s"]), e["playedAt"].l, if (e.has("origin")) e["origin"].s else null)
    private fun playOut(p: PlayRec<SongRef>) = Triple(name(p.s), p.playedAt, p.origin)
    private fun playExpect(e: JsonElement) = Triple(e["s"].s, e["playedAt"].l, if (e.has("origin")) e["origin"].s else null)
    private fun op(e: JsonElement): PlayOp<SongRef> =
        if (e["t"].s == "play") PlayOp.Play(song(e["s"]), e["playedAt"].l) else PlayOp.ClearPlays(e["before"].l, hlcOf(e["at"])!!)
    private fun opOut(o: PlayOp<SongRef>): Any = when (o) {
        is PlayOp.Play -> listOf("play", name(o.s), o.playedAt)
        is PlayOp.ClearPlays -> listOf("clearPlays", o.before, o.at)
    }
    private fun opExpect(e: JsonElement): Any =
        if (e["t"].s == "play") listOf("play", e["s"].s, e["playedAt"].l) else listOf("clearPlays", e["before"].l, hlcOf(e["at"])!!)

    @Test fun `plays - ids, apply, diff`() {
        for (c in v["plays"]["ids"].list) assertThat(PlaysMerge.playId(song(c["s"]), c["playedAt"].l)).isEqualTo(c["id"].s)
        for (c in v["plays"]["apply"].list) {
            val r = PlaysMerge.apply(PlaysState(c["state"]["plays"].list.map(::play), c["state"]["clearedBefore"].l), c["ops"].list.map(::op), c["origin"].s)
            assertWithMessage(c["name"].s).that(r.plays.map(::playOut)).isEqualTo(c["expect"]["plays"].list.map(::playExpect))
            assertWithMessage(c["name"].s).that(r.clearedBefore).isEqualTo(c["expect"]["clearedBefore"].l)
        }
        for (c in v["plays"]["diff"].list) {
            val r = PlaysMerge.diff(
                baseIds = c["base"]["ids"].list.map { it.s }.toSet(),
                baseClearedBefore = c["base"]["clearedBefore"].l,
                local = PlaysState(c["local"]["plays"].list.map(::play), c["local"]["clearedBefore"].l),
                at = hlcOf(c["at"])!!,
            )
            assertWithMessage(c["name"].s).that(r.map(::opOut)).isEqualTo(c["expect"].list.map(::opExpect))
        }
    }

    private fun ver(e: JsonElement): PlVersion<SongRef>? = if (e is JsonNull) null else PlVersion(
        name = e["name"].s,
        items = if (e["items"] is JsonNull) null else e["items"].list.map(::song),
        at = hlcOf(e["at"])!!,
        follow = if (e.has("follow")) Follow(e["follow"]["id"].s, e["follow"]["version"].i) else null,
        ro = e.has("ro") && e["ro"].jsonPrimitive.boolean,
    )

    /** A version as comparable plain values: items by song name. */
    private fun shape(p: PlVersion<SongRef>?): Any? = p?.let { listOf(it.name, it.items?.map(::name), it.at, it.follow, it.ro) }
    private fun shapeExpect(e: JsonElement): Any? = ver(e)?.let { listOf(it.name, it.items?.map(::name), it.at, it.follow, it.ro) }

    @Test fun `playlists - three-way merge`() {
        for (c in v["playlists"]["merge"].list) {
            val r = PlaylistMerge.merge(
                phone = c["phone"].jsonPrimitive.boolean,
                base = ver(c["base"]),
                local = ver(c["local"]),
                localHash = if (c["local"] is JsonNull) null else c["local"]["hash"].s,
                remote = ver(c["remote"])!!,
                parent = if (c["remote"]["parent"] is JsonNull) null else c["remote"]["parent"].s,
                fromPhone = c["remote"]["fromPhone"].jsonPrimitive.boolean,
            )
            val e = c["expect"]
            assertWithMessage(c["name"].s).that(r.action.name.lowercase()).isEqualTo(e["action"].s)
            assertWithMessage(c["name"].s).that(shape(r.local)).isEqualTo(shapeExpect(e["local"]))
            val wantBase = if (e["base"].s == "remote") c["remote"] else c["base"]
            assertWithMessage(c["name"].s).that(shape(r.base)).isEqualTo(shapeExpect(wantBase))
        }
    }

    @Test fun `playlists - hashes and queue ids`() {
        for (c in v["playlists"]["hash"].list) {
            val items = if (c["items"] is JsonNull) null else c["items"].list.map(::song)
            val follow = if (c.has("follow")) Follow(c["follow"]["id"].s, c["follow"]["version"].i) else null
            assertThat(SyncHashes.playlistHashInput(c["name"].s, items, follow)).isEqualTo(c["input"].s)
            assertThat(SyncHashes.playlistHash(c["name"].s, items, follow)).isEqualTo(c["hash"].s)
        }
        for (c in v["playlists"]["queueId"].list) {
            val items = c["items"].list.map(::song)
            val original = if (c.has("original")) c["original"].list.map { it.i } else null
            assertThat(SyncHashes.queueIdInput(items, c["offset"].i, original)).isEqualTo(c["input"].s)
            assertThat(SyncHashes.queueId(items, c["offset"].i, original)).isEqualTo(c["id"].s)
        }
    }

    @Test fun `first merge - counts and names`() {
        for (c in v["firstMerge"]["counts"].list) {
            val got = FirstMerge.counts(c["a"].list.map(::song), c["b"].list.map(::song))
            val e = c["expect"]
            assertThat(got).isEqualTo(FirstMerge.Counts(e["a"].i, e["b"].i, e["both"].i, e["combined"].i, e["onlyA"].i, e["onlyB"].i))
        }
        for (c in v["firstMerge"]["names"].list) {
            assertThat(FirstMerge.combinedName(c["name"].s, c["taken"].list.map { it.s })).isEqualTo(c["expect"].s)
        }
    }

    @Test fun `an HLC tie on wall and counter falls to the device id`() {
        assertThat(Hlc(1, 0, "d_web") > Hlc(1, 0, "d_phone")).isTrue()
    }
}
