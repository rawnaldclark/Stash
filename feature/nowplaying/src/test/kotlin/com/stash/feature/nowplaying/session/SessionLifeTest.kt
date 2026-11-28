package com.stash.feature.nowplaying.session

import com.google.common.truth.Truth.assertThat
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.listen.ListenTogetherController.Command
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.media.listen.SessionEvent
import com.stash.core.media.listen.SessionRole
import com.stash.core.model.listen.RoomMember
import com.stash.core.model.listen.RoomProtocol
import com.stash.core.model.listen.ServerMessage
import com.stash.core.model.listen.SessionInfo
import com.stash.core.model.listen.SongStats
import com.stash.core.model.share.SharedTrack
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The room's life: the rules ([SessionLifeUi]) and the feed's view model. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionLifeTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    // ── the rules ───────────────────────────────────────────────────────

    @Test fun `every face and every phrase this app can send has something to show, each different`() {
        for (id in RoomProtocol.FACES) assertThat(faceText(id)).isNotNull()
        assertThat(RoomProtocol.FACES.map(::faceText).toSet()).hasSize(17)
        assertThat(RoomProtocol.FACES.map(::faceName).toSet()).hasSize(17)
        // The big ones are the tray's last row, five of them, each with two frames.
        assertThat(RoomProtocol.FACES.filter(::isBigFace)).containsExactly("k:tableflip", "k:unflip", "k:dance", "k:rockon", "k:sparkle").inOrder()
        assertThat(RoomProtocol.FACES.filter(::isBigFace).map { faceFrames(it)!!.size }.toSet()).containsExactly(2)
        for (id in RoomProtocol.PHRASES) assertThat(phraseRes(id)).isNotNull()
        assertThat(RoomProtocol.PHRASES.map(::phraseRes).toSet()).hasSize(10)
    }

    @Test fun `a face is drawn as its text, named as its command, and an emoji or an id from a newer app as nothing`() {
        assertThat(faceText("k:shrug")).isEqualTo("""¯\_(ツ)_/¯""")
        assertThat(faceName("k:shrug")).isEqualTo("/shrug")
        assertThat(faceText("k:tableflip")).isEqualTo("(╯°□°)╯︵ ┻━┻")
        assertThat(isBigFace("k:shrug")).isFalse()
        assertThat(isBigFace("k:tableflip")).isTrue()
        // A session passes on only faces, so an emoji (a private room's) shows nothing here.
        assertThat(faceFrames("🔥")).isNull()
        assertThat(faceFrames("k:from-next-year")).isNull()
        assertThat(isBigFace("k:from-next-year")).isFalse()
        assertThat(phraseRes("from-next-year")).isNull()
    }

    @Test fun `the feed keeps the last four lines, newest last`() {
        var feed = Feed()
        listOf("Ana", "Ben", "Cy", "Di", "Eve").forEachIndexed { i, name -> feed = feed.add(FeedLine.Joined(name), 1_000L * i) }
        assertThat(feed.lines.map { it.line }).containsExactly(
            FeedLine.Joined("Ben"), FeedLine.Joined("Cy"), FeedLine.Joined("Di"), FeedLine.Joined("Eve"),
        ).inOrder()
        assertThat(feed.lines.map { it.id }.toSet()).hasSize(4)
    }

    @Test fun `the same phrase within 10 s folds into one line, which moves to the end`() {
        var feed = Feed().add(FeedLine.Said("Ben", "tune"), 0)
        val id = feed.lines.single().id
        feed = feed.add(FeedLine.Joined("Cy"), 1_000)
        feed = feed.add(FeedLine.Said("Di", "tune"), 4_000)
        feed = feed.add(FeedLine.Said("Eve", "tune"), 9_000)
        assertThat(feed.lines.map { it.line }).containsExactly(FeedLine.Joined("Cy"), FeedLine.Said("Ben", "tune", count = 3)).inOrder()
        assertThat(feed.lines.last().id).isEqualTo(id) // the same line, so the screen doesn't flash it
    }

    @Test fun `a different phrase, or the same one 10 s later, is its own line`() {
        var feed = Feed().add(FeedLine.Said("Ben", "tune"), 0)
        feed = feed.add(FeedLine.Said("Di", "chills"), 1_000)
        feed = feed.add(FeedLine.Said("Eve", "tune"), 10_000)
        assertThat(feed.lines.map { it.line }).containsExactly(
            FeedLine.Said("Ben", "tune"), FeedLine.Said("Di", "chills"), FeedLine.Said("Eve", "tune"),
        ).inOrder()
    }

    // ── the view model ──────────────────────────────────────────────────

    private val state = MutableStateFlow<ListenTogetherState>(ListenTogetherState.Idle)
    private val said = MutableSharedFlow<ServerMessage.Said>(extraBufferCapacity = 8)
    private val events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 8)
    private val deckOuts = MutableSharedFlow<ServerMessage.DeckOut>(extraBufferCapacity = 8)
    private val reactions = MutableSharedFlow<ServerMessage.Reaction>(extraBufferCapacity = 8)
    private val controller: ListenTogetherController = mockk(relaxed = true) {
        every { state } returns this@SessionLifeTest.state
        every { said } returns this@SessionLifeTest.said
        every { events } returns this@SessionLifeTest.events
        every { deckOuts } returns this@SessionLifeTest.deckOuts
        every { reactions } returns this@SessionLifeTest.reactions
    }

    private fun room(
        code: String = "K7QA2PXM",
        host: Boolean = false,
        stats: SongStats? = null,
        track: SharedTrack? = SharedTrack("Redbone", "Childish Gambino"),
        session: SessionInfo? = SessionInfo("St4tion1"),
    ) = ListenTogetherState.InRoom(
        code = code, url = "u", myId = "me", isHost = host, hostId = if (host) "me" else "h",
        members = listOf(RoomMember("h", "Maya"), RoomMember("me", "Rawn"), RoomMember("b", "Ben")),
        suggestions = emptyList(), track = track, session = session,
        role = if (host) SessionRole.HOST else SessionRole.LISTENER, songStats = stats, names = mapOf("gone" to "Sam"),
    )

    private fun lines(vm: SessionLifeViewModel) = vm.feed.value.lines.map { it.line }

    @Test fun `phrases and arrivals become feed lines, with names from the room`() = runTest(dispatcher) {
        state.value = room()
        val vm = SessionLifeViewModel(controller); runCurrent()
        said.tryEmit(ServerMessage.Said("b", "tune")); runCurrent()
        said.tryEmit(ServerMessage.Said("gone", "chills")); runCurrent()
        said.tryEmit(ServerMessage.Said("nobody", "thanks")); runCurrent()
        events.tryEmit(SessionEvent(SessionEvent.Kind.JOINED, "x", "Jo")); runCurrent()
        assertThat(lines(vm)).containsExactly(
            FeedLine.Said("Ben", "tune"), FeedLine.Said("Sam", "chills"), FeedLine.Said(null, "thanks"), FeedLine.Joined("Jo"),
        ).inOrder()
    }

    @Test fun `a phrase this app doesn't know, this phone's own arrival, and anything in a private room add nothing`() = runTest(dispatcher) {
        state.value = room()
        val vm = SessionLifeViewModel(controller); runCurrent()
        said.tryEmit(ServerMessage.Said("b", "from-next-year")); runCurrent()
        events.tryEmit(SessionEvent(SessionEvent.Kind.JOINED, "me", "Rawn")); runCurrent()
        events.tryEmit(SessionEvent(SessionEvent.Kind.SKIPPED, "h", "Maya")); runCurrent()
        assertThat(lines(vm)).isEmpty()

        state.value = room(session = null); runCurrent()
        said.tryEmit(ServerMessage.Said("b", "tune")); runCurrent()
        assertThat(lines(vm)).isEmpty()
    }

    @Test fun `a song becoming a crowd favourite is said once`() = runTest(dispatcher) {
        state.value = room(stats = SongStats(4, reactions = 2))
        val vm = SessionLifeViewModel(controller); runCurrent()
        assertThat(lines(vm)).isEmpty()
        state.value = room(stats = SongStats(4, reactions = 3, favourite = true)); runCurrent()
        state.value = room(stats = SongStats(4, reactions = 5, favourite = true)); runCurrent()
        assertThat(lines(vm)).containsExactly(FeedLine.Favourite("Redbone"))
        state.value = room(stats = SongStats(5, reactions = 3, favourite = true), track = SharedTrack("Ivy", "Frank Ocean")); runCurrent()
        assertThat(lines(vm)).containsExactly(FeedLine.Favourite("Redbone"), FeedLine.Favourite("Ivy")).inOrder()
    }

    @Test fun `a regular arrives as back, and the last song is said once each time it's called`() = runTest(dispatcher) {
        val withRegular = room().copy(members = listOf(RoomMember("h", "Maya"), RoomMember("me", "Rawn"), RoomMember("b", "Ben", regular = true)))
        state.value = withRegular
        val vm = SessionLifeViewModel(controller); runCurrent()
        events.tryEmit(SessionEvent(SessionEvent.Kind.JOINED, "b", "Ben")); runCurrent()
        events.tryEmit(SessionEvent(SessionEvent.Kind.JOINED, "x", "Jo")); runCurrent()
        state.value = withRegular.copy(session = SessionInfo("St4tion1", lastSong = true)); runCurrent()
        state.value = withRegular.copy(session = SessionInfo("St4tion1", lastSong = true, listeners = 3)); runCurrent()
        assertThat(lines(vm)).containsExactly(FeedLine.Back("Ben"), FeedLine.Joined("Jo"), FeedLine.LastSong).inOrder()
        // Taken back, then called again: said again.
        state.value = withRegular.copy(session = SessionInfo("St4tion1")); runCurrent()
        state.value = withRegular.copy(session = SessionInfo("St4tion1", lastSong = true)); runCurrent()
        assertThat(lines(vm).count { it == FeedLine.LastSong }).isEqualTo(2)
    }

    @Test fun `the song card names the album only when there is one that isn't the title again`() {
        assertThat(songCard("Nights", "Frank Ocean", "Blonde")).isEqualTo(SongCard("Nights", "Frank Ocean", "Blonde"))
        assertThat(songCard("Nights", "Frank Ocean", null).album).isNull()
        assertThat(songCard("Nights", "Frank Ocean", "  ").album).isNull()
        assertThat(songCard("Creep", "Radiohead", "creep ").album).isNull() // a single, named for its song
        assertThat(PHRASE_WHO).isIn(com.stash.core.model.listen.RoomProtocol.PHRASES)
    }

    @Test fun `when a station is next on reads as a day and a time on the phone's clock`() {
        val ny = java.time.ZoneId.of("America/New_York")
        val at = java.time.ZonedDateTime.of(2026, 11, 28, 21, 0, 0, 0, ny).toInstant().toEpochMilli() // a Saturday
        assertThat(recapNextOn(at, ny, java.util.Locale.US).replace('\u202f', ' ')).isEqualTo("Sat 9:00 PM")
        // The same moment is already Sunday in Tokyo.
        assertThat(recapNextOn(at, java.time.ZoneId.of("Asia/Tokyo"), java.util.Locale.US)).startsWith("Sun ")
    }

    @Test fun `a recap's length reads as hours and minutes`() {
        assertThat(recapDuration(0)).isEqualTo(0 to 0)
        assertThat(recapDuration(59_999)).isEqualTo(0 to 0)
        assertThat(recapDuration(42 * 60_000L)).isEqualTo(0 to 42)
        assertThat(recapDuration(102 * 60_000L + 30_000)).isEqualTo(1 to 42)
        assertThat(recapDuration(-5)).isEqualTo(0 to 0)
    }

    @Test fun `the crowd's energy is the others' reactions and phrases in the last minute`() = runTest(dispatcher) {
        state.value = room(host = true)
        val vm = SessionLifeViewModel(controller).also { it.clock = { testScheduler.currentTime } }; runCurrent()
        assertThat(vm.energy.value).isEqualTo(0)
        reactions.tryEmit(ServerMessage.Reaction("b", "🔥")); runCurrent()
        reactions.tryEmit(ServerMessage.Reaction("me", "🔥")); runCurrent() // this phone's own isn't the crowd's
        advanceTimeBy(20_000); runCurrent()
        said.tryEmit(ServerMessage.Said("b", "tune")); runCurrent()
        reactions.tryEmit(ServerMessage.Reaction("x", "k:tableflip")); runCurrent()
        assertThat(vm.energy.value).isEqualTo(3)
        advanceTimeBy(40_000); runCurrent() // the first is a minute old
        assertThat(vm.energy.value).isEqualTo(2)
        advanceTimeBy(20_000); runCurrent()
        assertThat(vm.energy.value).isEqualTo(0)
    }

    @Test fun `the energy starts again with a new session, and a private room has none`() = runTest(dispatcher) {
        state.value = room(host = true)
        val vm = SessionLifeViewModel(controller).also { it.clock = { testScheduler.currentTime } }; runCurrent()
        reactions.tryEmit(ServerMessage.Reaction("b", "🔥")); runCurrent()
        assertThat(vm.energy.value).isEqualTo(1)
        state.value = room(code = "SECOND02", host = true); runCurrent()
        assertThat(vm.energy.value).isEqualTo(0)
        state.value = room(session = null); runCurrent()
        reactions.tryEmit(ServerMessage.Reaction("b", "🔥")); runCurrent()
        assertThat(vm.energy.value).isEqualTo(0)
    }

    @Test fun `a new session starts with an empty feed`() = runTest(dispatcher) {
        state.value = room()
        val vm = SessionLifeViewModel(controller); runCurrent()
        said.tryEmit(ServerMessage.Said("b", "tune")); runCurrent()
        state.value = room(code = "ANOTHER1"); runCurrent()
        assertThat(lines(vm)).isEmpty()
    }

    @Test fun `Hi all is lit for a listener's first 10 seconds, or until they say something`() = runTest(dispatcher) {
        state.value = room()
        val vm = SessionLifeViewModel(controller); runCurrent()
        assertThat(vm.greet.value).isTrue()
        advanceTimeBy(9_999); runCurrent()
        assertThat(vm.greet.value).isTrue()
        advanceTimeBy(2); runCurrent()
        assertThat(vm.greet.value).isFalse()

        state.value = room(code = "ANOTHER1"); runCurrent()
        assertThat(vm.greet.value).isTrue()
        vm.say("hi")
        assertThat(vm.greet.value).isFalse()
        verify { controller.send(Command.Say("hi")) }
    }

    @Test fun `a host isn't greeted`() = runTest(dispatcher) {
        state.value = room(host = true)
        val vm = SessionLifeViewModel(controller); runCurrent()
        assertThat(vm.greet.value).isFalse()
    }

    @Test fun `a save is told to the room`() = runTest(dispatcher) {
        val vm = SessionLifeViewModel(controller)
        vm.saved()
        verify { controller.send(Command.SavedSong) }
    }
}
