package com.stash.data.download.lossless.addon

import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.StreamCapabilities
import com.stash.core.addons.AddonEntryState
import com.stash.core.addons.AddonHold
import com.stash.core.addons.AddonManifest
import com.stash.core.addons.AddonSees
import com.stash.core.addons.BlockReason
import com.stash.core.addons.InTreeAddonRegistry
import com.stash.core.addons.OfficialEntry
import com.stash.core.addons.Trust
import com.stash.core.addons.UserAddonEntry
import com.stash.data.download.lossless.PlayOrder
import org.junit.Test

/**
 * The rows of Settings › Add-ons (add-ons plan phase 4 §2.1, §2.8, §2.9), built from plain values:
 * which rows there are, in what order, what each one's mark and status say, and what it lets the
 * user do. The screens only pick words for these, so what is wrong here is wrong on screen.
 */
class AddonOverviewRowsTest {

    private fun manifest(id: String, name: String = id, resources: List<String> = listOf("search", "stream"), tiers: List<String>? = listOf("lossless")) =
        AddonManifest(
            Manifest(
                stash = 1, id = id, name = name, version = "1.2.0", kind = AddonKind.SERVER, resources = resources,
                stream = StreamCapabilities(cors = false, manifests = listOf("none"), tiers = tiers),
            ),
        )

    private fun link(id: String) = "https://$id.example.org/v1/s3cret-tok/manifest.json"

    private fun user(id: String, enabled: Boolean = true, name: String? = null) =
        UserAddonEntry(link(id), enabled = enabled, id = id, name = name ?: id, consent = setOf("PLAYS", "NEXT_UP"))

    private fun entry(
        id: String,
        userAdded: Boolean = true,
        hold: AddonHold? = null,
        trust: Trust = if (userAdded) Trust.UNOFFICIAL else Trust.OFFICIAL,
        manifest: AddonManifest? = manifest(id),
        firstParty: Boolean = false,
        blockReason: BlockReason? = null,
        needsNewer: Boolean = false,
    ) = AddonEntryState(
        key = InTreeAddonRegistry.keyOf(link(id)), userAdded = userAdded, firstParty = firstParty, host = "$id.example.org",
        trust = trust, blockReason = blockReason, manifest = manifest, hold = hold, needsNewerStash = needsNewer,
    )

    private val both = AddonOverview.Named(hasQobuz = true, tidalKnown = true, ownHost = null, off = emptySet())

    private fun build(
        users: List<UserAddonEntry> = emptyList(),
        entries: List<AddonEntryState> = emptyList(),
        named: AddonOverview.Named = both,
        saved: List<String>? = null,
        reading: Boolean = false,
        official: List<OfficialEntry> = emptyList(),
        unauthorized: Set<String> = emptySet(),
        described: (String) -> OfficialEntry? = { null },
    ) = AddonOverview.build(users, entries, reading, named, saved, official, unauthorized, described)

    // ------------------------------------------------------------------------------------------ which rows, in what order

    @Test fun `with nothing saved - Your Qobuz, Tidal, the user's add-ons as added, then the defaults`() {
        val state = build(
            users = listOf(user("mine"), user("second")),
            entries = listOf(entry("mine"), entry("second"), entry("builtin", userAdded = false)),
        )
        assertThat(state.order.map { it.key }).containsExactly("qobuz", "tidal", "addon:mine", "addon:second", "addon:builtin").inOrder()
        assertThat(state.automatic).isTrue()
        assertThat(state.loaded).isTrue()
        // The order the chains walk is the same list of keys.
        assertThat(state.keys).isEqualTo(PlayOrder.effective(null, listOf("addon:mine", "addon:second", "addon:builtin")).keys)
    }

    @Test fun `Your Qobuz is a row only when an account is connected, and keeps its place while it isn't shown`() {
        val state = build(users = listOf(user("mine")), entries = listOf(entry("mine")), named = both.copy(hasQobuz = false))
        assertThat(state.order.map { it.key }).containsExactly("tidal", "addon:mine").inOrder()
        assertThat(state.keys).containsExactly("qobuz", "tidal", "addon:mine").inOrder()
        // Dragging the add-on above Tidal moves it among the rows shown; Your Qobuz stays first for when it is back.
        assertThat(PlayOrder.moved(state.keys, state.order.map { it.key }, "addon:mine", 0)).containsExactly("qobuz", "addon:mine", "tidal").inOrder()
    }

    @Test fun `a saved order is the order shown, a switched-off add-on keeps its row and its place`() {
        val state = build(
            users = listOf(user("mine", enabled = false), user("other")),
            entries = listOf(entry("mine", hold = AddonHold.OFF), entry("other")),
            saved = listOf("addon:mine", "tidal", "qobuz", "addon:other"),
        )
        assertThat(state.order.map { it.key }).containsExactly("addon:mine", "tidal", "qobuz", "addon:other").inOrder()
        assertThat(state.automatic).isFalse()
        val off = state.row("addon:mine")!!
        assertThat(off.status).isEqualTo(AddonOverview.Status.OFF)
        assertThat(off.on).isFalse()
        assertThat(off.canSwitch).isTrue()
        assertThat(off.name).isEqualTo("mine")
    }

    @Test fun `a lyrics-only add-on has a row, and no place in the play order`() {
        val state = build(users = listOf(user("words")), entries = listOf(entry("words", manifest = manifest("words", resources = listOf("lyrics"), tiers = null))))
        assertThat(state.order.map { it.key }).containsExactly("qobuz", "tidal").inOrder()
        val words = state.others.single()
        assertThat(words.playsSongs).isFalse()
        assertThat(words.uses).containsExactly(AddonLinks.Use.LYRICS)
        // Its server still sees the song that is playing, and a song once it is downloaded: lyrics are
        // asked for both, by name and artist. Never the next one in the queue.
        assertThat(words.sees).containsExactly(AddonSees.PLAYS, AddonSees.SAVES)
    }

    // ------------------------------------------------------------------------------------------ the Tidal row

    @Test fun `Stash's Tidal is Official, with the index's line and a switch`() {
        val state = build(described = { id -> OfficialEntry(id, "Tidal", "Lossless and Hi-Res").takeIf { id == "tidal" } })
        val tidal = state.row("tidal")!!
        assertThat(tidal.mark).isEqualTo(AddonOverview.Mark.OFFICIAL)
        assertThat(tidal.summary).isEqualTo("Lossless and Hi-Res")
        assertThat(tidal.canSwitch).isTrue()
        assertThat(tidal.canRemove).isFalse()
        assertThat(tidal.on).isTrue()
    }

    @Test fun `the user's own Tidal server shows its host, has no mark, and can be given up`() {
        val tidal = build(named = both.copy(ownHost = "tidal.mine.example")).row("tidal")!!
        assertThat(tidal.ownServer).isTrue()
        assertThat(tidal.host).isEqualTo("tidal.mine.example")
        assertThat(tidal.mark).isEqualTo(AddonOverview.Mark.NONE)
        assertThat(tidal.canRemove).isTrue()
    }

    @Test fun `Tidal switched off keeps its row, so the switch can be flipped back, and no Tidal at all has none`() {
        val off = build(named = both.copy(off = setOf("tidal"))).row("tidal")!!
        assertThat(off.on).isFalse()
        assertThat(off.status).isEqualTo(AddonOverview.Status.OFF)
        assertThat(build(named = both.copy(tidalKnown = false)).row("tidal")).isNull()
        // The first-party Tidal add-on is the Tidal row's, never a second row.
        val state = build(entries = listOf(entry("tidal", userAdded = false, firstParty = true, trust = Trust.FIRST_PARTY)))
        assertThat(state.order.map { it.key }).containsExactly("qobuz", "tidal").inOrder()
    }

    // ------------------------------------------------------------------------------------------ marks, addresses and statuses

    @Test fun `an add-on Stash doesn't run always shows its address, and never the rest of its link`() {
        val state = build(users = listOf(user("mine", name = "Hi-Fi Relay")), entries = listOf(entry("mine", manifest = manifest("mine", name = "Hi-Fi Relay"))))
        val row = state.row("addon:mine")!!
        assertThat(row.mark).isEqualTo(AddonOverview.Mark.NOT_MADE_BY_STASH)
        assertThat(row.host).isEqualTo("mine.example.org")
        assertThat(row.toString()).doesNotContain("s3cret-tok")
        assertThat(row.version).isEqualTo("1.2.0")
        assertThat(row.uses).containsExactly(AddonLinks.Use.PLAYS_SONGS, AddonLinks.Use.LOSSLESS, AddonLinks.Use.DOWNLOADS).inOrder()
        assertThat(row.sees).containsExactly(AddonSees.PLAYS, AddonSees.NEXT_UP, AddonSees.SAVES)
        assertThat(row.canRemove).isTrue()
        // Before the registry has read it, the address still comes from the link itself.
        val early = build(users = listOf(user("mine", name = "Hi-Fi Relay")))
        assertThat(early.order.map { it.key }).containsExactly("qobuz", "tidal").inOrder() // nothing known about what it does yet: not in the play order...
        val waiting = early.others.single()
        assertThat(waiting.host).isEqualTo("mine.example.org") // ...but listed, by the name it was added under.
        assertThat(waiting.name).isEqualTo("Hi-Fi Relay")
        assertThat(waiting.status).isEqualTo(AddonOverview.Status.WORKING) // no verdict before it has been read
    }

    @Test fun `an official add-on the user added is Official, with the index's line`() {
        val state = build(
            users = listOf(user("radio")),
            entries = listOf(entry("radio", trust = Trust.OFFICIAL)),
            official = listOf(OfficialEntry("radio", "Example Radio", "Live stations", manifestUrl = link("radio")), OfficialEntry("more", "More", "Another")),
            described = { id -> OfficialEntry(id, "Example Radio", "Live stations").takeIf { id == "radio" } },
        )
        val row = state.row("addon:radio")!!
        assertThat(row.mark).isEqualTo(AddonOverview.Mark.OFFICIAL)
        assertThat(row.host).isNull()
        assertThat(row.summary).isEqualTo("Live stations")
        // Get add-ons offers only what isn't added yet.
        assertThat(state.official.map { it.id }).containsExactly("more")
    }

    @Test fun `each thing that can be wrong has its own status, and the ones Stash won't use have no switch`() {
        fun statusOf(hold: AddonHold?, unauthorized: Set<String> = emptySet(), needsNewer: Boolean = false, reading: Boolean = false, block: BlockReason? = null) =
            build(
                users = listOf(user("mine")),
                entries = listOf(entry("mine", hold = hold, needsNewer = needsNewer, blockReason = block, trust = if (hold == AddonHold.BLOCKED) Trust.BLOCKED else Trust.UNOFFICIAL)),
                unauthorized = unauthorized, reading = reading,
            ).let { s -> (s.order + s.others).single { it.kind == AddonOverview.Kind.ADDON } }

        assertThat(statusOf(null).status).isEqualTo(AddonOverview.Status.WORKING)
        val blocked = statusOf(AddonHold.BLOCKED, block = BlockReason.HARMFUL)
        assertThat(blocked.status).isEqualTo(AddonOverview.Status.TURNED_OFF_BY_STASH)
        assertThat(blocked.blockReason).isEqualTo(BlockReason.HARMFUL)
        assertThat(blocked.canSwitch).isFalse()
        assertThat(blocked.on).isFalse()
        assertThat(blocked.canRemove).isTrue() // the one thing left to do with it
        assertThat(statusOf(AddonHold.WANTS_MORE).status).isEqualTo(AddonOverview.Status.WANTS_MORE)
        assertThat(statusOf(null, unauthorized = setOf("mine")).status).isEqualTo(AddonOverview.Status.NEEDS_NEW_LINK)
        assertThat(statusOf(null, needsNewer = true).status).isEqualTo(AddonOverview.Status.NEEDS_NEWER_STASH)
        assertThat(statusOf(AddonHold.NOT_LOADED).status).isEqualTo(AddonOverview.Status.NOT_REACHABLE)
        // While manifests are being read there is no verdict yet.
        assertThat(statusOf(AddonHold.NOT_LOADED, reading = true).status).isEqualTo(AddonOverview.Status.WORKING)
        for ((hold, status) in listOf(
            AddonHold.CHANGED to AddonOverview.Status.CHANGED,
            AddonHold.REPLACED to AddonOverview.Status.REPLACED,
            AddonHold.ONLY_BITCHORD to AddonOverview.Status.ONLY_BITCHORD,
        )) {
            val row = statusOf(hold)
            assertThat(row.status).isEqualTo(status)
            assertThat(row.canSwitch).isFalse()
            assertThat(row.on).isFalse()
        }
    }

    @Test fun `a link that now serves another add-on shows the one the user approved, never the new one's words`() {
        val state = build(
            users = listOf(user("mine", name = "Hi-Fi Relay")),
            entries = listOf(entry("mine", hold = AddonHold.CHANGED, manifest = manifest("impostor", name = "Tidal"))),
        )
        val row = (state.order + state.others).single { it.kind == AddonOverview.Kind.ADDON }
        assertThat(row.name).isEqualTo("Hi-Fi Relay")
        assertThat(row.key).isEqualTo("addon:mine")
        assertThat(row.status).isEqualTo(AddonOverview.Status.CHANGED)
    }

    @Test fun `a copy that one of Stash's own add-ons replaced keeps a row of its own, beside the one in use`() {
        // The user added "radio" from a link; later the signed config turned on Stash's own add-on by that id.
        val stash = AddonEntryState(
            key = InTreeAddonRegistry.keyOf("https://stash.example.org/radio/manifest.json"), userAdded = false, firstParty = true,
            host = "stash.example.org", trust = Trust.FIRST_PARTY, blockReason = null, manifest = manifest("radio", name = "Radio"), hold = null,
        )
        val state = build(
            users = listOf(user("radio", name = "Radio")),
            entries = listOf(entry("radio", hold = AddonHold.REPLACED, manifest = manifest("radio", name = "Radio")), stash),
            saved = listOf("addon:radio", PlayOrder.QOBUZ, PlayOrder.TIDAL),
        )
        // The one in use has the place in the play order, its switch, and no Remove.
        val inUse = state.row("addon:radio")!!
        assertThat(state.order.map { it.key }).containsExactly("addon:radio", PlayOrder.QOBUZ, PlayOrder.TIDAL).inOrder()
        assertThat(inUse.mark).isEqualTo(AddonOverview.Mark.OFFICIAL)
        assertThat(inUse.status).isEqualTo(AddonOverview.Status.WORKING)
        assertThat(inUse.canSwitch).isTrue()
        assertThat(inUse.canRemove).isFalse()
        // The copy says it isn't used, has no place and no switch, and can be removed.
        val copy = state.others.single()
        assertThat(copy.key).isEqualTo("entry:" + InTreeAddonRegistry.keyOf(link("radio")))
        assertThat(copy.status).isEqualTo(AddonOverview.Status.REPLACED)
        assertThat(copy.mark).isEqualTo(AddonOverview.Mark.NOT_MADE_BY_STASH)
        assertThat(copy.canSwitch).isFalse()
        assertThat(copy.canRemove).isTrue()
        assertThat(state.row(copy.key)).isEqualTo(copy)

        // The other way round (the user's own add-on stands in for a default that isn't Stash's own): one row, the user's.
        val userWins = build(
            users = listOf(user("radio")),
            entries = listOf(entry("radio"), stash.copy(firstParty = false, trust = Trust.OFFICIAL, hold = AddonHold.REPLACED)),
        )
        val only = (userWins.order + userWins.others).single { it.kind == AddonOverview.Kind.ADDON }
        assertThat(only.key).isEqualTo("addon:radio")
        assertThat(only.userAdded).isTrue()
        assertThat(only.status).isEqualTo(AddonOverview.Status.WORKING)
    }

    @Test fun `a default has a switch and no Remove`() {
        val state = build(entries = listOf(entry("builtin", userAdded = false, hold = AddonHold.OFF)))
        val row = state.row("addon:builtin")!!
        assertThat(row.mark).isEqualTo(AddonOverview.Mark.OFFICIAL)
        assertThat(row.canSwitch).isTrue()
        assertThat(row.canRemove).isFalse()
        assertThat(row.on).isFalse()
        assertThat(row.userAdded).isFalse()
    }
}
