/**
 * Listen Together room logic (spec docs/superpowers/specs/2026-09-24-listen-together-design.md §2–§4;
 * guests adding songs: docs/superpowers/specs/2026-11-04-listen-together-guest-adds-design.md).
 *
 * Pure: step(state, event, now) → { state, out, alarmAt, bind?, reject?, closed? }.
 * No I/O, clock or randomness: the ListenRoom Durable Object supplies `now`, fresh ids and the
 * host-key check, so `node --test` covers every rule without the Workers runtime.
 *
 * Events:
 *   { type: "create", code, hostName, keyHash }
 *   { type: "hello", msg, hostKeyOk, newId, newToken }  a socket's first message
 *   { type: "msg", from, msg, newId? }                  any later message from member `from`
 *   { type: "close", from }                             member `from`'s socket closed
 *   { type: "alarm" }
 * out: [{ to: memberId | "all", msg }]. An unchanged room comes back as the SAME state object.
 *
 * The queue, front to back: the Play next songs people added (first come, first played), then their Add to queue
 * songs, then the rest of the host's album or playlist. `adds` remembers which queued songs are adds, and the queue
 * version (`qv`) each arrived at; the host's app sends whole queues built from an older version, so adds and removals
 * it hadn't seen yet are put back on top of them (setHostQueue). An app from before guest adds sends no `qv` and gets
 * today's behaviour, and only a host whose app says `v: 2` in its hello can let guests add.
 */
import { cleanTrack } from "./validate.js";

export const MAX_MEMBERS = 10;
/** How long a song's handshake waits for the guests' phones: a slow guest is started without, and joins late. */
export const PREPARE_MS = 8_000;
/**
 * The room waits for its host's phone, not just PREPARE_MS (owner decision 2026-11-07): only the host moves the room on,
 * so a song it doesn't have yet must not play for the guests alone. Its app (v ≥ HOST_WAIT_V) gives up on a song it can't
 * get within HOST_PREPARE_MS of its prepare and reports it unavailable, which skips it for everyone. Keep in sync with
 * HOST_PREPARE_MS in the app's ListenTogetherSession.
 */
export const HOST_PREPARE_MS = 20_000;
/**
 * The room's own end to that wait, for a host that never says (its app stuck, or killed with the socket still open):
 * then it starts without the host, as it always did at PREPARE_MS. Past HOST_PREPARE_MS so the app's own report, which
 * counts from the prepare it received a round trip later, always arrives first.
 */
export const HOST_WAIT_CAP_MS = 25_000;
/**
 * The first app version that gives up on a song within HOST_PREPARE_MS. An older host app (every release before guest
 * adds says no `v`) only gives up when its player fails, ~45 s in, or never: it is waited for no longer than PREPARE_MS,
 * as before.
 */
export const HOST_WAIT_V = 2;
export const START_LEAD_MS = 500;
export const COMMAND_LEAD_MS = 400;
export const HANDOVER_MS = 60_000;
export const EMPTY_CLOSE_MS = 5 * 60_000;
export const MAX_AGE_MS = 12 * 60 * 60_000;
export const MAX_PENDING_SUGGESTIONS = 3;
export const MAX_SUGGESTIONS = 20;
/** Longest position accepted for a song without a known duration. */
export const MAX_POSITION_MS = 86_400_000;
export const MAX_QUEUE = 200;
export const REACT_GAP_MS = 1_000;
/** The six reactions. Keep in sync with RoomProtocol.EMOJI in core/model. */
export const EMOJI = ["\u2764\uFE0F", "\u{1F525}", "\u{1F602}", "\u{1F62E}", "\u{1F44F}", "\u{1F3B6}"];
/**
 * What this room speaks: 2 = guests can add songs (`add`, `unqueue`, `settings`, `qv`), and a host app on 2 gives up on a
 * song within HOST_PREPARE_MS, so the room waits for it (HOST_WAIT_V). Keep in sync with RoomProtocol.VERSION.
 */
export const PROTOCOL_V = 2;
/** Songs one guest may have waiting in the queue at once (owner decision 7, 2026-11-04). The host's only cap is MAX_QUEUE. */
export const MAX_GUEST_WAITING = 20;
/** Removals a host's app may not have seen yet, kept so its next whole-queue message can't bring them back. */
export const MAX_REMOVED = 50;
/**
 * The queue's length as JSON past which no more adds are taken. The host's next Load carries the whole queue, and the
 * shell closes any frame over MAX_FRAME (262,144), so a queue of very long titles must never get near it: that socket
 * would drop for good and only End would work. The app's own 200 ordinary songs come to about 49 KB. Low enough for
 * what can still go in without this check: 20 waiting suggestions and the Load's own song, at most 4,000 characters
 * each (MAX_TRACK_JSON_CHARS, which cleanTrack holds them to), bring it to 254,000 at the very most.
 */
export const MAX_QUEUE_CHARS = 170_000;

const HOST_ONLY = new Set(["load", "play", "pause", "seek", "queue", "suggestion", "makeHost", "end", "settings"]);
/** Phone status → stored member status (`ready` is stored as `ok`, spec §3). */
const STATUSES = { ready: "ok", buffering: "buffering", unavailable: "unavailable", drifting: "drifting" };

const cleanName = (n) => (typeof n === "string" && n.trim() ? n.trim().slice(0, 40) : null);
/** A client position as a safe non-negative integer, clamped to the song (or 24 h); null when unusable. */
const cleanPosition = (v, track) =>
    (Number.isSafeInteger(v) && v >= 0 ? Math.min(v, track?.d ?? MAX_POSITION_MS) : null);
const connected = (s) => s.members.filter((m) => m.leftAt === null);
const findConnected = (s, id) => s.members.find((m) => m.id === id && m.leftAt === null);
const cleanQueue = (q) => (Array.isArray(q) ? q.map(cleanTrack).filter(Boolean).slice(0, MAX_QUEUE) : []);

/** Where the song is at room time `now`. A start time still in the future counts as "not moved yet". */
export function positionAt(tl, now) {
    return tl.playing ? tl.positionMs + Math.max(0, now - tl.atRoomMs) : tl.positionMs;
}

export function createRoom({ code, hostName, keyHash }, now) {
    return {
        code, keyHash, createdAt: now, hostName: cleanName(hostName),
        rev: 0, host: null, track: null, trackKey: 0,
        timeline: { positionMs: 0, atRoomMs: now, playing: false },
        queue: [], members: [], suggestions: [], phase: { kind: "playing" },
        reactAt: {}, lastLeftAt: now, hostless: false, accepted: [],
        qv: 0, adds: [], removed: [],
    };
}

/**
 * A room stored before guest adds (a room live during the deploy) has no qv, adds or removed: read it as if it always
 * had them. Its accepted suggestions still waiting at the front become Play next adds the host's app has already seen.
 */
function upgrade(s) {
    s.qv ??= 0;
    s.removed ??= [];
    if (!s.adds) {
        const accepted = new Set(s.accepted ?? []);
        s.adds = [];
        for (const t of s.queue) {
            if (!accepted.has(songKey(t))) break;
            s.adds.push({ k: addKey(t), qv: 0, next: true });
        }
    }
}

/** `v` only for an app that sent one (2 = it knows guest adds), so older members read exactly as before. */
const publicMembers = (s) => connected(s).map(({ id, name, joinedAt, status, v }) => ({ id, name, joinedAt, status, ...(v ? { v } : {}) }));

/**
 * Guests' Play next and Add to queue go straight in: the host's app knows guest adds (`v` ≥ 2 in its hello) and its
 * "Let guests add songs" switch is on (`ga`, on unless it said otherwise). Read from whoever hosts now, so a new host's
 * own switch takes over with them; an older host keeps the approvals it knows.
 */
export function guestsCanAdd(s) {
    const host = s.members.find((m) => m.id === s.host);
    return !!host && (host.v ?? 1) >= 2 && host.ga !== false;
}

/** What phones see: never the key hash, tokens, or members who have dropped. */
export function publicState(s) {
    return {
        rev: s.rev, host: s.host, track: s.track, trackKey: s.trackKey, timeline: s.timeline, queue: s.queue,
        members: publicMembers(s), suggestions: s.suggestions,
        // The guests' deadline only, as always: the host's is the room's business.
        phase: s.phase.kind === "preparing"
            ? { kind: "preparing", trackKey: s.phase.trackKey, deadlineMs: s.phase.deadlineMs }
            : { kind: "playing" },
        v: PROTOCOL_V, guestsAdd: guestsCanAdd(s), qv: s.qv ?? 0,
    };
}

/** The Join screen's view (GET /v1/rooms/{code}). */
export function preview(s) {
    const host = s.members.find((m) => m.id === s.host);
    return {
        hostName: host?.name ?? s.hostName,
        memberCount: connected(s).length,
        full: s.members.length >= MAX_MEMBERS,
        ...(s.track ? { track: s.track } : {}),
    };
}

/**
 * The earliest pending timer: room expiry, empty-room close, a 60 s handover/prune, the prepare deadline (§2): the
 * guests' while the host is ready, else the end of the wait for the host (the guests' alone would start nothing).
 */
export function nextAlarm(s) {
    const times = [s.createdAt + MAX_AGE_MS];
    if (connected(s).length === 0) times.push(s.lastLeftAt + EMPTY_CLOSE_MS);
    for (const m of s.members) if (m.leftAt !== null) times.push(m.leftAt + HANDOVER_MS);
    if (s.phase.kind === "preparing") times.push(hostSettled(s) ? s.phase.deadlineMs : hostDeadline(s));
    return Math.min(...times);
}

const unchanged = (state, out = []) => ({ state, out, alarmAt: nextAlarm(state) });
const membersMsg = (s) => ({ to: "all", msg: { t: "members", members: publicMembers(s) } });
const suggestionsMsg = (s) => ({ to: "all", msg: { t: "suggestions", suggestions: s.suggestions } });
/**
 * Every phone shows Up next (who added what), and the host's app mirrors it, so each change goes to all. `qv` is the
 * version the host's app builds its next whole queue from.
 */
const queueMsg = (s) => ({ to: "all", msg: { t: "queue", queue: s.queue, qv: s.qv } });
/** A queued song's identity without its adder: the form `accepted` is stored in, which a rolled-back Worker reads. */
const songKey = (t) => JSON.stringify([t.t, t.a, t.yt ?? null, t.isrc ?? null]);
/** An added song's identity, with its adder: a guest's copy is never mistaken for the same song in the host's album. */
const addKey = (t) => JSON.stringify([t.t, t.a, t.yt ?? null, t.isrc ?? null, t.by ?? null]);
const countKeys = (tracks) => {
    const n = new Map();
    for (const t of tracks) { const k = addKey(t); n.set(k, (n.get(k) ?? 0) + 1); }
    return n;
};

/**
 * Where the next add goes: after the run of Play next songs at the front ([next]), or after every added song at the
 * front (Add to queue), which is before the rest of the host's album (owner decision 3). A song the host dragged out
 * of the front run no longer holds it open.
 */
function runEnd(s, next) {
    const kinds = new Map(); // key → "is any copy a Play next"
    for (const e of s.adds) kinds.set(e.k, (kinds.get(e.k) ?? false) || e.next);
    let at = 0;
    while (at < s.queue.length) {
        const isNext = kinds.get(addKey(s.queue[at]));
        if (isNext === undefined || (next && !isNext)) break;
        at++;
    }
    return at;
}

/** [tracks], already stamped with their adder, where Play next or Add to queue puts them, logged at the current qv. */
function insertAdds(s, tracks, next) {
    let at = runEnd(s, next);
    for (const t of tracks) {
        s.queue.splice(at++, 0, t);
        s.adds.push({ k: addKey(t), qv: s.qv, next });
    }
}

/**
 * The room changes hands. The album or playlist still waiting is the new host's from now on, so it counts against
 * nobody's 20 and the former host can't swipe it away as a guest. Songs people added, the former host's too, stay theirs.
 */
function setHost(s, id) {
    if (id === s.host) return;
    s.host = id;
    if (id === null) return; // nobody to give the album to yet: the next host gets it
    const isAdd = addFlags(s);
    s.queue = s.queue.map((t, i) => (isAdd[i] ? t : { ...t, by: id }));
}

/** How many more songs [from] may add: the room's 200, and for a guest their 20 waiting. */
function room(s, from) {
    const left = MAX_QUEUE - s.adds.length;
    if (from === s.host) return Math.max(0, left);
    return Math.max(0, Math.min(left, MAX_GUEST_WAITING - s.queue.filter((t) => t.by === from).length));
}

/**
 * After any queue change: at most MAX_QUEUE songs, losing the end of the host's album before any add; `adds` forgets
 * songs no longer queued (played or removed); `accepted` follows the Play next run, so a rolled-back Worker still
 * finds it.
 */
function settle(s) {
    let over = s.queue.length - MAX_QUEUE;
    if (over > 0) {
        const isAdd = addFlags(s);
        const drop = new Set();
        for (let i = s.queue.length - 1; i >= 0 && over > 0; i--) if (!isAdd[i]) { drop.add(i); over--; }
        s.queue = s.queue.filter((_, i) => !drop.has(i)).slice(0, MAX_QUEUE);
    }
    const left = countKeys(s.queue);
    s.adds = s.adds.filter((e) => {
        const n = left.get(e.k) ?? 0;
        if (n <= 0) return false;
        left.set(e.k, n - 1);
        return true;
    });
    const nextKeys = new Set(s.adds.filter((e) => e.next).map((e) => e.k));
    s.accepted = [...new Set(s.queue.filter((t) => nextKeys.has(addKey(t))).map(songKey))];
}

/**
 * For each queued song, whether it is an add. Counted from the front, so when the host's album also holds the very
 * same copy (the host's own Play next of a song on it), the one nearer the front is the add.
 */
function addFlags(s) {
    const left = new Map();
    for (const e of s.adds) left.set(e.k, (left.get(e.k) ?? 0) + 1);
    return s.queue.map((t) => {
        const k = addKey(t);
        const n = left.get(k) ?? 0;
        if (n <= 0) return false;
        left.set(k, n - 1);
        return true;
    });
}

/** The queued songs that are adds, in queue order: what survives the host starting a new album (owner decision 4). */
function queuedAdds(s) {
    const isAdd = addFlags(s);
    return s.queue.filter((_, i) => isAdd[i]);
}

/**
 * The host's `load` and `queue` carry a whole queue, built by its app from room version `msg.qv`. Without `qv` (an app
 * from before guest adds) it is taken as sent, as it always was. With it, what happened since that version is put
 * back on top: songs guests removed come out again, and songs added go back in by the Play next / Add to queue rules.
 * On a pick (the host started a new album or playlist) every add stays in front of it.
 *
 * [left]: the song a Back just left. The app puts it at the front to play again next, which is a Play next in all but
 * name: logged as one, it holds the front run, so what's added next goes behind it, not in front (`back` marks it, so
 * it doesn't outlive the album: it has already been heard).
 */
function setHostQueue(ctx, pick, left = null) {
    const { s, msg, event } = ctx;
    const sent = stamped(cleanQueue(msg.queue), event.from);
    const seen = Number.isSafeInteger(msg.qv) && msg.qv >= 0 ? msg.qv : null;
    if (seen === null) {
        s.queue = sent;
    } else {
        const before = s.queue;
        if (pick) s.adds = s.adds.filter((e) => !e.back);
        s.queue = [...(pick ? queuedAdds(s) : []), ...sent];
        for (const r of s.removed) {
            if (r.qv <= seen) continue;
            const i = s.queue.findIndex((t) => addKey(t) === r.k);
            if (i >= 0) s.queue.splice(i, 1);
        }
        if (left && s.queue[0] && songKey(s.queue[0]) === songKey(left)) {
            s.adds.push({ k: addKey(s.queue[0]), qv: seen, next: true, back: true });
        }
        const tracks = new Map(before.map((t) => [addKey(t), t]));
        const have = countKeys(s.queue);
        for (const e of s.adds) {
            const n = have.get(e.k) ?? 0;
            if (n > 0) { have.set(e.k, n - 1); continue; }
            // Gone from a queue the host built after the add arrived: the host removed it (or played it), so it stays gone.
            if (e.qv <= seen || !tracks.has(e.k)) continue;
            s.queue.splice(runEnd(s, e.next), 0, tracks.get(e.k));
        }
    }
    s.qv++;
    settle(s);
}
const stateMsg = (s, to = "all") => ({ to, msg: { t: "state", state: publicState(s) } });
/** Every connected phone has either buffered the song or can't get it, so nobody is worth waiting for. */
const allSettled = (s) => connected(s).every((m) => m.status === "ok" || m.status === "unavailable");
/**
 * Nothing left to wait for from the host: it has the song or has said it can't get it, it isn't connected (a drop or a
 * handover in progress: the guests aren't held for a phone that isn't there), or its app is from before HOST_WAIT_V.
 */
function hostSettled(s) {
    const host = findConnected(s, s.host);
    return !host || (host.v ?? 1) < HOST_WAIT_V || host.status === "ok" || host.status === "unavailable";
}
/** When the room stops waiting for its host. A room preparing since before this rule had only the one deadline. */
const hostDeadline = (s) => s.phase.hostDeadlineMs ?? s.phase.deadlineMs;
/**
 * A song's handshake is over: every phone is settled; or the guests' time is up and the host is settled (a slow guest
 * joins late, the host is never left behind); or the room has waited for its host as long as it will.
 */
function startDue(s, now) {
    if (s.phase.kind !== "preparing") return false;
    if (now >= hostDeadline(s)) return true;
    return hostSettled(s) && (allSettled(s) || now >= s.phase.deadlineMs);
}
/** Starts the song if its handshake is over: after anything that may settle it (a status, a leave, a new host, the alarm). */
function startIfDue(ctx) {
    if (!startDue(ctx.s, ctx.now)) return false;
    startPlayback(ctx);
    return true;
}

/** [by] names the member who caused the change (play, pause, seek); the room's own start has none. */
function setTimeline(ctx, timeline, by) {
    ctx.s.timeline = timeline;
    ctx.s.rev++;
    ctx.out.push({ to: "all", msg: { t: "timeline", rev: ctx.s.rev, trackKey: ctx.s.trackKey, ...timeline, ...(by ? { by } : {}) } });
}

/**
 * Why the host loaded a song, passed through to phones so they can say "Rawn skipped" (and stay quiet on a natural end).
 * `unavailable`: the host's phone couldn't play the last song, so everyone moved on ("Skipped …: Rawn can't play it").
 */
const WHYS = new Set(["skip", "back", "end", "pick", "radio", "unavailable"]);
/** Songs without an adder are the sender's. */
const stamped = (tracks, by) => tracks.map((t) => (t.by ? t : { ...t, by }));

/** One waiting suggestion, within the 3-per-listener and 20-in-all limits; false when it doesn't fit. */
function pushSuggestion(s, from, id, track) {
    if (s.suggestions.length >= MAX_SUGGESTIONS) return false;
    if (s.suggestions.filter((x) => x.from === from).length >= MAX_PENDING_SUGGESTIONS) return false;
    s.suggestions.push({ id, from, track });
    return true;
}

/**
 * Tells the sender alone that nothing of their add went in, so their app doesn't claim it did. [suggested]: it became
 * suggestions instead, this many of them. An `added` always answers an `add`, which is what the app's toast waits for.
 */
function answerAdd(ctx, next, suggested) {
    const by = ctx.event.from;
    ctx.out.push({ to: by, msg: { t: "added", by, next, count: 0, ...(suggested === undefined ? {} : { suggested }) } });
}

/**
 * [tracks] straight into the queue for member `from` (stamped as theirs, whatever they claimed), as many as fit, and
 * "Sam added …" to everyone (the adder's own app stays quiet). False when none fit, and only the sender hears.
 */
function addSongs(ctx, tracks, next) {
    const { s } = ctx;
    const from = ctx.event.from;
    let chars = JSON.stringify(s.queue).length;
    const taken = [];
    for (const t of tracks.slice(0, room(s, from))) {
        const song = { ...t, by: from };
        chars += JSON.stringify(song).length + 1;
        if (chars > MAX_QUEUE_CHARS) break;
        taken.push(song);
    }
    if (!taken.length) { answerAdd(ctx, next); return false; }
    s.qv++;
    insertAdds(s, taken, next);
    settle(s);
    s.rev++;
    ctx.out.push(queueMsg(s));
    ctx.out.push({ to: "all", msg: { t: "added", by: from, next, count: taken.length, track: taken[0] } });
    return true;
}

/**
 * Call after anything that may change who hosts or their switch, with guestsCanAdd from before. When it turns on,
 * the suggestions still waiting go in as Play next, in the order they came; one from a guest who already has 20
 * waiting stays in the host's tray instead of being lost. With [announce], everyone hears the new setting (and the
 * queue, if it changed); otherwise the caller's `state` says it.
 */
function switchChanged(ctx, before, announce) {
    const { s } = ctx;
    const now = guestsCanAdd(s);
    if (now === before) return;
    const waiting = now ? s.suggestions : [];
    if (waiting.length) {
        s.suggestions = [];
        s.qv++;
        for (const x of waiting) {
            if (room(s, x.from) > 0) insertAdds(s, [{ ...x.track, by: x.from }], true);
            else s.suggestions.push(x);
        }
        settle(s);
    }
    if (!announce) return;
    if (waiting.length) ctx.out.push(suggestionsMsg(s), queueMsg(s));
    ctx.out.push({ to: "all", msg: { t: "settings", guestsAdd: now } });
}

function startPlayback(ctx) {
    ctx.s.phase = { kind: "playing" };
    setTimeline(ctx, { positionMs: ctx.s.timeline.positionMs, atRoomMs: ctx.now + START_LEAD_MS, playing: true });
}

export function step(state, event, now) {
    if (event.type === "create") {
        if (state) return { ...unchanged(state), reject: { code: 409, reason: "exists" } };
        const created = createRoom(event, now);
        return { state: created, out: [], alarmAt: nextAlarm(created) };
    }
    if (!state) return { state: null, out: [], alarmAt: null, reject: { code: 4404, reason: "closed" } };
    const msg = event.msg;
    if (event.type === "msg") {
        if (!findConnected(state, event.from) || !msg || typeof msg.t !== "string") return unchanged(state);
        if (HOST_ONLY.has(msg.t) && state.host !== event.from) return unchanged(state);
        if (msg.t === "ping") {
            return Number.isFinite(msg.c) ? unchanged(state, [{ to: event.from, msg: { t: "pong", c: msg.c, r: now } }]) : unchanged(state);
        }
    }
    const table = event.type === "msg" ? MESSAGES : EVENTS;
    const name = event.type === "msg" ? msg.t : event.type;
    if (!Object.hasOwn(table, name)) return unchanged(state); // never "constructor" & co.
    const handler = table[name];
    const ctx = { s: structuredClone(state), out: [], now, event, msg: msg ?? {} };
    upgrade(ctx.s);
    const changed = handler(ctx);
    if (ctx.closed) return { state: null, out: ctx.out, alarmAt: null, closed: true };
    const next = changed ? ctx.s : state;
    return { state: next, out: ctx.out, alarmAt: nextAlarm(next), bind: ctx.bind, reject: ctx.reject };
}

const EVENTS = {
    hello(ctx) {
        const { s, now, event, msg } = ctx;
        const name = cleanName(msg.name);
        const before = guestsCanAdd(s);
        // Only the private token rejoins a slot: every member can see every memberId (spec §3).
        let m = typeof msg.resumeToken === "string" ? s.members.find((x) => x.token === msg.resumeToken) : undefined;
        if (!m) {
            if (s.members.length >= MAX_MEMBERS) { ctx.reject = { code: 4409, reason: "full" }; return false; }
            m = { id: event.newId, token: event.newToken, name, joinedAt: now, status: "buffering", leftAt: null };
            s.members.push(m);
        } else {
            m.leftAt = null;
            if (name) m.name = name;
            // Back mid-handshake: its old "ok" was for another song, so wait for this one.
            if (s.phase.kind === "preparing") m.status = "buffering";
        }
        // `v`: what this app speaks (2 = guest adds). `guestsAdd`: its "Let guests add songs" switch, which rules the
        // room whenever this member hosts. Sent on every hello, so a switch flipped while the socket was down still lands.
        if (Number.isSafeInteger(msg.v) && msg.v >= 1 && msg.v <= 99) m.v = msg.v;
        if (typeof msg.guestsAdd === "boolean") m.ga = msg.guestsAdd;
        // The host key proves only the original host, and only while nobody holds the room (spec §2).
        if (event.hostKeyOk && s.host === null) setHost(s, m.id);
        // A room whose host lapsed with nobody around goes to the longest-joined member back.
        if (s.host === null && s.hostless) setHost(s, connected(s).sort((a, b) => a.joinedAt - b.joinedAt)[0].id);
        if (s.host !== null) s.hostless = false;
        s.rev++;
        switchChanged(ctx, before, true);
        ctx.bind = m.id;
        ctx.out.push({ to: m.id, msg: { t: "welcome", memberId: m.id, token: m.token, state: publicState(s) } });
        ctx.out.push(membersMsg(s));
        return true;
    },

    close(ctx) {
        const { s, now, event } = ctx;
        const m = findConnected(s, event.from);
        if (!m) return false;
        m.leftAt = now;
        if (connected(s).length === 0) s.lastLeftAt = now;
        s.rev++;
        ctx.out.push(membersMsg(s));
        startIfDue(ctx); // the last phone waited for left, or the host did: the guests aren't held for it
        return true;
    },

    alarm(ctx) {
        const { s, now } = ctx;
        const empty = connected(s).length === 0;
        if (now >= s.createdAt + MAX_AGE_MS || (empty && now >= s.lastLeftAt + EMPTY_CLOSE_MS)) {
            ctx.out.push({ to: "all", msg: { t: "ended", reason: "closed" } });
            ctx.closed = true;
            return true;
        }
        let changed = false;
        const expired = s.members.filter((m) => m.leftAt !== null && now >= m.leftAt + HANDOVER_MS);
        if (expired.length) {
            const before = guestsCanAdd(s);
            const gone = new Set(expired.map((m) => m.id));
            s.members = s.members.filter((m) => !gone.has(m.id));
            const pending = s.suggestions.length;
            s.suggestions = s.suggestions.filter((x) => !gone.has(x.from));
            for (const id of gone) delete s.reactAt[id];
            s.rev++;
            if (gone.has(s.host)) {
                // Host gone for 60 s: the longest-joined connected member takes over (spec §2), with their own switch.
                const heir = connected(s).sort((a, b) => a.joinedAt - b.joinedAt)[0];
                setHost(s, heir ? heir.id : null);
                s.hostless = !heir;
                switchChanged(ctx, before, false);
                ctx.out.push(stateMsg(s));
            } else {
                ctx.out.push(membersMsg(s));
                if (s.suggestions.length !== pending) ctx.out.push(suggestionsMsg(s));
            }
            changed = true;
        }
        // The guests' deadline (with the host ready), or the end of the wait for the host. A new 60 s heir counts as host.
        if (startIfDue(ctx)) changed = true;
        return changed;
    },
};

const MESSAGES = {
    load(ctx) {
        const { s, now, msg, event } = ctx;
        const track = cleanTrack(msg.track);
        if (!track) return false;
        const left = msg.why === "back" ? s.track : null;
        s.track = track.by ? track : { ...track, by: event.from };
        s.trackKey++;
        setHostQueue(ctx, msg.why === "pick", left);
        s.timeline = { positionMs: cleanPosition(msg.positionMs, track) ?? 0, atRoomMs: now, playing: false };
        s.phase = { kind: "preparing", trackKey: s.trackKey, deadlineMs: now + PREPARE_MS, hostDeadlineMs: now + HOST_WAIT_CAP_MS };
        for (const m of connected(s)) m.status = "buffering";
        s.rev++;
        ctx.out.push({ to: "all", msg: {
            t: "prepare", trackKey: s.trackKey, track: s.track, positionMs: s.timeline.positionMs, deadlineMs: s.phase.deadlineMs,
            by: event.from, ...(WHYS.has(msg.why) ? { why: msg.why } : {}),
        } });
        ctx.out.push(membersMsg(s));
        ctx.out.push(queueMsg(s));
        return true;
    },

    status(ctx) {
        const { s, msg, event } = ctx;
        const value = Object.hasOwn(STATUSES, msg.status) ? STATUSES[msg.status] : null;
        if (!value) return false;
        // A report for an earlier song must not count toward this song's handshake.
        if (msg.trackKey !== undefined && msg.trackKey !== s.trackKey) return false;
        const member = findConnected(s, event.from);
        if (member.status === value) return false; // a repeat (e.g. ready after a reconnect): no write, no broadcast
        member.status = value;
        s.rev++;
        ctx.out.push(membersMsg(s));
        // Everyone settled; or the host settling after the guests' deadline, when the room was waiting only for it.
        startIfDue(ctx);
        return true;
    },

    // ponytail: play/pause/seek during the ready handshake (≤8 s, ≤25 s while it waits for a v2 host) are dropped; the
    // handshake starts playback anyway, and the app sends a pause again once it has.
    play(ctx) {
        const { s, now } = ctx;
        if (s.phase.kind !== "playing" || s.timeline.playing || !s.track) return false;
        setTimeline(ctx, { positionMs: s.timeline.positionMs, atRoomMs: now + COMMAND_LEAD_MS, playing: true }, ctx.event.from);
        return true;
    },

    pause(ctx) {
        const { s, now } = ctx;
        if (s.phase.kind !== "playing" || !s.timeline.playing) return false;
        setTimeline(ctx, { positionMs: positionAt(s.timeline, now), atRoomMs: now, playing: false }, ctx.event.from);
        return true;
    },

    seek(ctx) {
        const { s, now, msg } = ctx;
        const positionMs = cleanPosition(msg.positionMs, s.track);
        if (s.phase.kind !== "playing" || positionMs === null || !s.track) return false;
        setTimeline(ctx, s.timeline.playing
            ? { positionMs, atRoomMs: now + COMMAND_LEAD_MS, playing: true }
            : { positionMs, atRoomMs: now, playing: false }, ctx.event.from);
        return true;
    },

    queue(ctx) {
        if (!Array.isArray(ctx.msg.queue)) return false;
        setHostQueue(ctx, false);
        ctx.s.rev++;
        ctx.out.push(queueMsg(ctx.s));
        return true;
    },

    /**
     * `{ tracks, next }` from anyone: Play next (`next: true`) or Add to queue. Straight in for the host, and for a
     * guest while guests can add; otherwise each song becomes a suggestion, as an older app's Suggest does.
     */
    add(ctx) {
        const { s, msg, event } = ctx;
        if (!Array.isArray(msg.tracks)) return false;
        const next = msg.next === true;
        const tracks = msg.tracks.slice(0, MAX_QUEUE).map(cleanTrack).filter(Boolean);
        if (!tracks.length) { answerAdd(ctx, next); return false; }
        if (event.from === s.host || guestsCanAdd(s)) return addSongs(ctx, tracks, next);
        if (!event.newId) return false;
        let suggested = 0;
        tracks.forEach((track, i) => { if (pushSuggestion(s, event.from, `${event.newId}-${i}`, track)) suggested++; });
        if (suggested) { s.rev++; ctx.out.push(suggestionsMsg(s)); }
        answerAdd(ctx, next, suggested);
        return suggested > 0;
    },

    suggest(ctx) {
        const { s, msg, event } = ctx;
        const track = cleanTrack(msg.track);
        if (!track || s.host === event.from) return false;
        // An older app's Suggest is its Play next: with guest adds on, it goes straight in (owner decision 5).
        if (guestsCanAdd(s)) return addSongs(ctx, [track], true);
        if (!event.newId || !pushSuggestion(s, event.from, event.newId, track)) return false;
        s.rev++;
        ctx.out.push(suggestionsMsg(s));
        return true;
    },

    suggestion(ctx) {
        const { s, msg } = ctx;
        const i = s.suggestions.findIndex((x) => x.id === msg.id);
        if (i < 0 || (msg.action !== "add" && msg.action !== "dismiss")) return false;
        const [picked] = s.suggestions.splice(i, 1);
        const add = msg.action === "add" && s.adds.length < MAX_QUEUE;
        if (add) {
            // Accepted suggestions play next, in the order accepted: behind any Play next still waiting at the front.
            // The host chose it, so the guest's 20 doesn't apply; the end of the host's album makes room if needed.
            s.qv++;
            insertAdds(s, [{ ...picked.track, by: picked.from }], true); // whoever suggested it, whatever they claimed
            settle(s);
        }
        s.rev++;
        ctx.out.push(suggestionsMsg(s));
        if (add) ctx.out.push(queueMsg(s));
        return true;
    },

    /**
     * `{ track, at }`: takes one song out of the queue. The host may take any; a guest only one they added (owner
     * decision 2). `at` is where the phone saw it; when the queue moved since, the first copy of that song is taken.
     */
    unqueue(ctx) {
        const { s, msg, event } = ctx;
        const want = cleanTrack(msg.track);
        if (!want) return false;
        const k = addKey(want);
        const at = Number.isSafeInteger(msg.at) && msg.at >= 0 && msg.at < s.queue.length && addKey(s.queue[msg.at]) === k
            ? msg.at
            : s.queue.findIndex((t) => addKey(t) === k);
        if (at < 0 || (event.from !== s.host && s.queue[at].by !== event.from)) return false;
        s.queue.splice(at, 1);
        s.qv++;
        s.removed.push({ k, qv: s.qv });
        if (s.removed.length > MAX_REMOVED) s.removed.shift();
        settle(s);
        s.rev++;
        ctx.out.push(queueMsg(s));
        return true;
    },

    /** `{ guestsAdd }`: the host's "Let guests add songs" switch. */
    settings(ctx) {
        const { s, msg, event } = ctx;
        if (typeof msg.guestsAdd !== "boolean") return false;
        const host = findConnected(s, event.from);
        if (host.ga === msg.guestsAdd) return false;
        const before = guestsCanAdd(s);
        host.ga = msg.guestsAdd;
        s.rev++;
        switchChanged(ctx, before, true);
        return true;
    },

    react(ctx) {
        const { s, msg, event, now } = ctx;
        if (!EMOJI.includes(msg.emoji)) return false;
        const last = s.reactAt[event.from];
        if (last !== undefined && now - last < REACT_GAP_MS) return false;
        s.reactAt[event.from] = now;
        ctx.out.push({ to: "all", msg: { t: "reaction", from: event.from, emoji: msg.emoji } });
        return true;
    },

    makeHost(ctx) {
        const { s, msg } = ctx;
        if (msg.memberId === s.host || !findConnected(s, msg.memberId)) return false;
        const before = guestsCanAdd(s);
        setHost(s, msg.memberId);
        s.rev++;
        switchChanged(ctx, before, false); // the new host's own switch (or an older app's approvals) rules from now
        // Mid-handshake the room was waiting for the old host: now it waits for the new one, or starts if that one is ready.
        startIfDue(ctx);
        ctx.out.push(stateMsg(s));
        return true;
    },

    end(ctx) {
        ctx.out.push({ to: "all", msg: { t: "ended", reason: "host_ended" } });
        ctx.closed = true;
        return true;
    },
};
