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
export const MAX_QUEUE = 200;
export const REACT_GAP_MS = 1_000;
/** The six reactions. Keep in sync with RoomProtocol.EMOJI in core/model. */
export const EMOJI = ["\u2764\uFE0F", "\u{1F525}", "\u{1F602}", "\u{1F62E}", "\u{1F44F}", "\u{1F3B6}"];

const HOST_ONLY = new Set(["load", "play", "pause", "seek", "queue", "suggestion", "makeHost", "end"]);
/** Phone status → stored member status (`ready` is stored as `ok`, spec §3). */
const STATUSES = { ready: "ok", buffering: "buffering", unavailable: "unavailable", drifting: "drifting" };

const cleanName = (n) => (typeof n === "string" && n.trim() ? n.trim().slice(0, 40) : null);
const nonNegInt = (v) => (Number.isInteger(v) && v >= 0 ? v : null);
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
        reactAt: {}, lastLeftAt: now,
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
        }
        // The host key proves only the original host, and only while nobody holds the room (spec §2).
        if (event.hostKeyOk && s.host === null) s.host = m.id;
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
            s.members = s.members.filter((m) => !expired.includes(m));
            s.rev++;
            if (expired.some((m) => m.id === s.host)) {
                // Host gone for 60 s: the longest-joined connected member takes over (spec §2).
                const heir = connected(s).sort((a, b) => a.joinedAt - b.joinedAt)[0];
                s.host = heir ? heir.id : null;
                ctx.out.push(stateMsg(s));
            } else {
                ctx.out.push(membersMsg(s));
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
    // Tasks 2–5 add the host and member messages here.
};
