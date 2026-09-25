/**
 * Listen Together room logic (spec docs/superpowers/specs/2026-09-24-listen-together-design.md §2–§4).
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
 */
import { cleanTrack } from "./validate.js";

export const MAX_MEMBERS = 10;
export const PREPARE_MS = 8_000;
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

const HOST_ONLY = new Set(["load", "play", "pause", "seek", "queue", "suggestion", "makeHost", "end"]);
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
        reactAt: {}, lastLeftAt: now, hostless: false,
    };
}

const publicMembers = (s) => connected(s).map(({ id, name, joinedAt, status }) => ({ id, name, joinedAt, status }));

/** What phones see: never the key hash, tokens, or members who have dropped. */
export function publicState(s) {
    return {
        rev: s.rev, host: s.host, track: s.track, trackKey: s.trackKey, timeline: s.timeline, queue: s.queue,
        members: publicMembers(s), suggestions: s.suggestions,
        phase: s.phase.kind === "preparing" ? { ...s.phase } : { kind: "playing" },
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

/** The earliest pending timer: room expiry, empty-room close, a 60 s handover/prune, the prepare deadline (§2). */
export function nextAlarm(s) {
    const times = [s.createdAt + MAX_AGE_MS];
    if (connected(s).length === 0) times.push(s.lastLeftAt + EMPTY_CLOSE_MS);
    for (const m of s.members) if (m.leftAt !== null) times.push(m.leftAt + HANDOVER_MS);
    if (s.phase.kind === "preparing") times.push(s.phase.deadlineMs);
    return Math.min(...times);
}

const unchanged = (state, out = []) => ({ state, out, alarmAt: nextAlarm(state) });
const membersMsg = (s) => ({ to: "all", msg: { t: "members", members: publicMembers(s) } });
const suggestionsMsg = (s) => ({ to: "all", msg: { t: "suggestions", suggestions: s.suggestions } });
const stateMsg = (s, to = "all") => ({ to, msg: { t: "state", state: publicState(s) } });
/** Every connected phone has either buffered the song or can't get it, so nobody is worth waiting for. */
const allSettled = (s) => connected(s).every((m) => m.status === "ok" || m.status === "unavailable");

function setTimeline(ctx, timeline) {
    ctx.s.timeline = timeline;
    ctx.s.rev++;
    ctx.out.push({ to: "all", msg: { t: "timeline", rev: ctx.s.rev, trackKey: ctx.s.trackKey, ...timeline } });
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
    const changed = handler(ctx);
    if (ctx.closed) return { state: null, out: ctx.out, alarmAt: null, closed: true };
    const next = changed ? ctx.s : state;
    return { state: next, out: ctx.out, alarmAt: nextAlarm(next), bind: ctx.bind, reject: ctx.reject };
}

const EVENTS = {
    hello(ctx) {
        const { s, now, event, msg } = ctx;
        const name = cleanName(msg.name);
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
        // The host key proves only the original host, and only while nobody holds the room (spec §2).
        if (event.hostKeyOk && s.host === null) s.host = m.id;
        // A room whose host lapsed with nobody around goes to the longest-joined member back.
        if (s.host === null && s.hostless) s.host = connected(s).sort((a, b) => a.joinedAt - b.joinedAt)[0].id;
        if (s.host !== null) s.hostless = false;
        s.rev++;
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
        if (s.phase.kind === "preparing" && allSettled(s)) startPlayback(ctx);
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
            const gone = new Set(expired.map((m) => m.id));
            s.members = s.members.filter((m) => !gone.has(m.id));
            const pending = s.suggestions.length;
            s.suggestions = s.suggestions.filter((x) => !gone.has(x.from));
            for (const id of gone) delete s.reactAt[id];
            s.rev++;
            if (gone.has(s.host)) {
                // Host gone for 60 s: the longest-joined connected member takes over (spec §2).
                const heir = connected(s).sort((a, b) => a.joinedAt - b.joinedAt)[0];
                s.host = heir ? heir.id : null;
                s.hostless = !heir;
                ctx.out.push(stateMsg(s));
            } else {
                ctx.out.push(membersMsg(s));
                if (s.suggestions.length !== pending) ctx.out.push(suggestionsMsg(s));
            }
            changed = true;
        }
        if (s.phase.kind === "preparing" && now >= s.phase.deadlineMs) {
            startPlayback(ctx);
            changed = true;
        }
        return changed;
    },
};

const MESSAGES = {
    load(ctx) {
        const { s, now, msg } = ctx;
        const track = cleanTrack(msg.track);
        if (!track) return false;
        s.track = track;
        s.trackKey++;
        s.queue = cleanQueue(msg.queue);
        s.timeline = { positionMs: cleanPosition(msg.positionMs, track) ?? 0, atRoomMs: now, playing: false };
        s.phase = { kind: "preparing", trackKey: s.trackKey, deadlineMs: now + PREPARE_MS };
        for (const m of connected(s)) m.status = "buffering";
        s.rev++;
        ctx.out.push({ to: "all", msg: {
            t: "prepare", trackKey: s.trackKey, track, positionMs: s.timeline.positionMs, deadlineMs: s.phase.deadlineMs,
        } });
        ctx.out.push(membersMsg(s));
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
        if (s.phase.kind === "preparing" && allSettled(s)) startPlayback(ctx);
        return true;
    },

    // ponytail: play/pause/seek during the ≤8 s ready handshake are dropped; the handshake starts playback anyway.
    play(ctx) {
        const { s, now } = ctx;
        if (s.phase.kind !== "playing" || s.timeline.playing || !s.track) return false;
        setTimeline(ctx, { positionMs: s.timeline.positionMs, atRoomMs: now + COMMAND_LEAD_MS, playing: true });
        return true;
    },

    pause(ctx) {
        const { s, now } = ctx;
        if (s.phase.kind !== "playing" || !s.timeline.playing) return false;
        setTimeline(ctx, { positionMs: positionAt(s.timeline, now), atRoomMs: now, playing: false });
        return true;
    },

    seek(ctx) {
        const { s, now, msg } = ctx;
        const positionMs = cleanPosition(msg.positionMs, s.track);
        if (s.phase.kind !== "playing" || positionMs === null || !s.track) return false;
        setTimeline(ctx, s.timeline.playing
            ? { positionMs, atRoomMs: now + COMMAND_LEAD_MS, playing: true }
            : { positionMs, atRoomMs: now, playing: false });
        return true;
    },

    queue(ctx) {
        if (!Array.isArray(ctx.msg.queue)) return false;
        ctx.s.queue = cleanQueue(ctx.msg.queue);
        ctx.s.rev++;
        return true;
    },

    suggest(ctx) {
        const { s, msg, event } = ctx;
        const track = cleanTrack(msg.track);
        if (!track || !event.newId || s.host === event.from || s.suggestions.length >= MAX_SUGGESTIONS) return false;
        if (s.suggestions.filter((x) => x.from === event.from).length >= MAX_PENDING_SUGGESTIONS) return false;
        s.suggestions.push({ id: event.newId, from: event.from, track });
        s.rev++;
        ctx.out.push(suggestionsMsg(s));
        return true;
    },

    suggestion(ctx) {
        const { s, msg } = ctx;
        const i = s.suggestions.findIndex((x) => x.id === msg.id);
        if (i < 0 || (msg.action !== "add" && msg.action !== "dismiss")) return false;
        const [picked] = s.suggestions.splice(i, 1);
        if (msg.action === "add" && s.queue.length < MAX_QUEUE) s.queue.push(picked.track);
        s.rev++;
        ctx.out.push(suggestionsMsg(s));
        // The host's app mirrors the room's queue, so it gets the new one straight away.
        if (msg.action === "add") ctx.out.push(stateMsg(s, s.host));
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
        s.host = msg.memberId;
        s.rev++;
        ctx.out.push(stateMsg(s));
        return true;
    },

    end(ctx) {
        ctx.out.push({ to: "all", msg: { t: "ended", reason: "host_ended" } });
        ctx.closed = true;
        return true;
    },
};
