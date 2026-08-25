package com.stash.core.data.sync.workers

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import org.junit.Test

/**
 * Unit test for [reconciledPlaylistType] — the write-once-no-more rule for
 * `playlists.type`.
 *
 * Issue #437: a Spotify playlist first seen by the home-feed mix pass was
 * inserted as DAILY_MIX, and `type` was never written again. Once the library
 * walk reported the same `source_id` as a saved CUSTOM playlist, the row
 * stayed DAILY_MIX forever — invisible on every CUSTOM surface (the Sync tab's
 * "n/n PLAYLISTS" count and the Library Playlists grid both filter on `type`).
 * Five playlists fetched, one shown.
 *
 * The rule is ONE-WAY, so a wrong re-type never heals: the row leaves the Home
 * mix rails, `mix_number` is gone, the syncEnabled gate exempts DAILY_MIX only
 * so the row is skipped on every later run, and `shouldEnqueueForDownload` is
 * true for CUSTOM — one "Enable all" would queue an entire rotating mix (#368).
 * That is why the pair alone is NOT enough to authorise a re-type, and why the
 * source and the id namespace are part of the decision.
 */
class ReconciledPlaylistTypeTest {

    private val userPlaylistId = "0NIipN6vyz2yLYguJwbUC6"

    /** A Spotify-generated playlist always lives in Spotify's own namespace. */
    private val spotifyMixId = "37i9dQZF1E38ZgHCGJDJmC"

    private fun reconcile(
        existing: PlaylistType,
        snapshot: PlaylistType,
        source: MusicSource = MusicSource.SPOTIFY,
        sourceId: String = userPlaylistId,
    ) = reconciledPlaylistType(existing, snapshot, source, sourceId)

    // ── The #437 case ────────────────────────────────────────────────────────

    @Test fun `a user playlist mis-filed as a mix becomes a playlist again`() {
        assertThat(reconcile(PlaylistType.DAILY_MIX, PlaylistType.CUSTOM))
            .isEqualTo(PlaylistType.CUSTOM)
    }

    @Test fun `a saved playlist is never demoted to a mix`() {
        assertThat(reconcile(PlaylistType.CUSTOM, PlaylistType.DAILY_MIX)).isNull()
    }

    @Test fun `an unchanged type is not rewritten`() {
        for (type in PlaylistType.entries) {
            assertThat(reconcile(type, type)).isNull()
        }
    }

    // ── Spotify-generated mixes must survive the reconcile ───────────────────
    // keepAsLibraryPlaylist cannot be relied on to keep these away: it only
    // withholds ids in homeFeedMixIds, and that set is populated solely inside
    // the home-feed Success branch. One Empty/Error/exception/discovery-off run
    // leaves it empty, after which every saved mix whose name isn't literally
    // "Daily Mix N" arrives as a CUSTOM snapshot.

    @Test fun `a spotify-generated mix is never re-typed`() {
        assertThat(
            reconcile(
                PlaylistType.DAILY_MIX,
                PlaylistType.CUSTOM,
                sourceId = spotifyMixId,
            ),
        ).isNull()
    }

    /** Discover Weekly / Release Radar sit in a different sub-namespace. */
    @Test fun `discover weekly is never re-typed`() {
        assertThat(
            reconcile(
                PlaylistType.DAILY_MIX,
                PlaylistType.CUSTOM,
                sourceId = "37i9dQZEVXcQ9COmYvdajy",
            ),
        ).isNull()
    }

    // ── YouTube cannot reach the reconcile at all ────────────────────────────
    // A saved auto-mix tile is a VLRD… browseId, and the library parser accepts
    // any VL-prefixed id but VLLM/VLSE and strips the VL — handing back the
    // same RD… id the home-mix pass already snapshotted as DAILY_MIX.

    @Test fun `a saved youtube radio is never re-typed`() {
        assertThat(
            reconcile(
                PlaylistType.DAILY_MIX,
                PlaylistType.CUSTOM,
                source = MusicSource.YOUTUBE,
                sourceId = "RDCLAK5uy_kmPRjHDECIcuVwnKzx3sZLoDEcnzclyVQ",
            ),
        ).isNull()
    }

    @Test fun `no youtube id can be re-typed`() {
        for (id in listOf("PLabc123", "RDabc123", "OLAK5uy_abc", userPlaylistId)) {
            assertThat(
                reconcile(
                    PlaylistType.DAILY_MIX,
                    PlaylistType.CUSTOM,
                    source = MusicSource.YOUTUBE,
                    sourceId = id,
                ),
            ).isNull()
        }
    }

    // ── Everything else is left alone ────────────────────────────────────────

    @Test fun `local-only types are never reconciled`() {
        val localOnly = listOf(
            PlaylistType.STASH_MIX,
            PlaylistType.STASH_LIKED,
            PlaylistType.DOWNLOADS_MIX,
        )
        for (type in localOnly) {
            assertThat(reconcile(type, PlaylistType.CUSTOM)).isNull()
            assertThat(reconcile(type, PlaylistType.DAILY_MIX)).isNull()
            assertThat(reconcile(PlaylistType.DAILY_MIX, type)).isNull()
            assertThat(reconcile(PlaylistType.CUSTOM, type)).isNull()
        }
    }

    @Test fun `liked songs is left alone`() {
        assertThat(reconcile(PlaylistType.LIKED_SONGS, PlaylistType.CUSTOM)).isNull()
        assertThat(reconcile(PlaylistType.LIKED_SONGS, PlaylistType.DAILY_MIX)).isNull()
        assertThat(reconcile(PlaylistType.DAILY_MIX, PlaylistType.LIKED_SONGS)).isNull()
        assertThat(reconcile(PlaylistType.CUSTOM, PlaylistType.LIKED_SONGS)).isNull()
    }
}
