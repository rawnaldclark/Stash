/**
 * Listen Together room logic (spec docs/superpowers/specs/2026-09-24-listen-together-design.md §2–§4;
 * guests adding songs: docs/superpowers/specs/2026-11-04-listen-together-guest-adds-design.md).
 *
 * Pure: step(state, event, now) → { state, out, alarmAt, bind?, reject?, closed?, kick?, live?, regulars?, stats? }.
 * `stats`: a session's totals for the scoreboard, once, as it closes (see sessionStats).
 * `regulars`: `{ stationId, listeners }`, key hashes whose stay in this session reached REGULAR_STAY_MS, for the shell
 * to count at the station (once each a session). `kick`: members a
 * session's host booted, whose sockets the shell closes once `out` is sent. `live`: a session's live directory write,
 * `{ upsert: row }` or `{ remove: { stationId, room } }`, which the shell makes after storing (src/stations-api.js).
 * No I/O, clock or randomness: the ListenRoom Durable Object supplies `now`, fresh ids and the
 * host-key check, so `node --test` covers every rule without the Workers runtime.
 *
 * Events:
 *   { type: "create", code, hostName, keyHash, stationId? }  with stationId, a session (Sessions: a public room)
 *   { type: "hello", msg, hostKeyOk, listenerHash?, newId, newToken }  a socket's first message; listenerHash is the
 *                                                       SHA-256 of the Community key it sent, worked out by the shell
 *   { type: "msg", from, msg, newId? }                  any later message from member `from`
 *   { type: "close", from }                             member `from`'s socket closed
 *   { type: "alarm" }
 *   { type: "stationGone", reason }                     a session's station went off the air (from the shell)
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

/*
 * Sessions (spec docs/superpowers/specs/2026-11-20-sessions-design.md §4; build plan
 * docs/superpowers/plans/2026-11-20-sessions.md and its step 1): a room opened for a station is public. Everything a
 * session does differently is behind `s.pub`, so a private room behaves exactly as before.
 */
export const MAX_LISTENERS = 50;
export const MAX_COHOSTS = 3;
/** The host, 3 co-hosts and 50 listeners. */
export const MAX_PUBLIC_MEMBERS = 1 + MAX_COHOSTS + MAX_LISTENERS;
/** What a session speaks, and the least an app must say in its hello to join one (build plan, decision 5). */
export const PUBLIC_V = 3;
/**
 * A session's reactions and quick phrases (UX spec §4.3, design §5): ids only, never text. Reactions are old-school
 * text faces (owner 2026-11-27: "get rid of emoji reactions they look cheap"): `k:shrug` is ¯\_(ツ)_/¯, and each app
 * draws them from its own copy of the list, the last five big. A session takes only these, a private room only the six
 * EMOJI (the store app sends them). Keep in sync with RoomProtocol.FACES and RoomProtocol.PHRASES in core/model, the
 * web player's and the desktop's lists. An app that doesn't know an id shows nothing, so later sets can be added here
 * first.
 */
export const FACES = [
    "k:shrug", "k:lenny", "k:yay", "k:love", "k:cool", "k:look", "k:wow", "k:cry", "k:wave", "k:notes", "k:heart",
    "k:bear", "k:tableflip", "k:unflip", "k:dance", "k:rockon", "k:sparkle",
];
export const PHRASES = ["hi", "who", "tune", "again", "louder", "more", "chills", "thanks", "morning", "night"];
/** One phrase a person every this long: held by the shell, a socket at a time, as reactions are. */
export const SAY_GAP_MS = 3_000;
/** A song's counts go out when they change, at most this often. */
export const STATS_GAP_MS = 2_000;
/**
 * Regulars (UX spec §7): a listener who stays this long in a session, in total, has that session counted for them at
 * its station, and after REGULAR_SESSIONS of them is a regular there (a star on their face, "Ben is back").
 */
export const REGULAR_STAY_MS = 10 * 60_000;
export const REGULAR_SESSIONS = 3;
/** Listeners whose time a session remembers after they left, in case they come back: past this, a short stay is forgotten. */
export const MAX_STAYS = 500;
/**
 * Open decks (UX spec §5): besides the host, this many listeners take turns playing one song each; more wait in a
 * line of at most DECK_LINE. A DJ whose turn comes with no song ready is skipped, and off the decks after
 * DECK_MISSES in a row.
 */
export const DECK_SEATS = 4;
export const DECK_LINE = 20;
export const DECK_MISSES = 2;
/** A crowd favourite: this many people at least, and a third of the room, reacted while the song played. */
export const FAVOURITE_MIN = 3;
/** Paused this long, a session ends. */
export const PAUSE_END_MS = 15 * 60_000;
/** How often a session on the air rewrites its live directory row when nothing else has. */
export const LIVE_BEAT_MS = 60_000;
/**
 * A change (a new song, pause or play) reaches the directory at most this soon after its last write, so a host's phone
 * can't make the Community database write at its message rate (20 a second); a later change waits for the gap's end.
 */
export const LIVE_GAP_MS = 5_000;
/** Requests one listener may have waiting, and a session in all. */
export const MAX_REQUESTS_EACH = 3;
export const MAX_REQUESTS = 30;
/** Key hashes a session remembers as booted. */
export const MAX_BOOTED = 200;
export const capacity = (s) => (s.pub ? MAX_PUBLIC_MEMBERS : MAX_MEMBERS);

const HOST_ONLY = new Set([
    "load", "play", "pause", "seek", "queue", "suggestion", "makeHost", "end", "settings",
    "autoAccept", "cohost", "boot", "presence", "lastSong", "decks", "deckRemove", // sessions
]);
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

export function createRoom({ code, hostName, keyHash, stationId }, now) {
    return {
        code, keyHash, createdAt: now, hostName: cleanName(hostName),
        rev: 0, host: null, track: null, trackKey: 0,
        timeline: { positionMs: 0, atRoomMs: now, playing: false },
        queue: [], members: [], suggestions: [], phase: { kind: "playing" },
        reactAt: {}, lastLeftAt: now, hostless: false, accepted: [],
        qv: 0, adds: [], removed: [],
        // A session: the station it's on the air for, and its own state. Co-hosts and the booted are kept by key hash,
        // never by member id, so both hold across a reconnect (build plan, decision 3). `pausedSince`: a session ends
        // after PAUSE_END_MS without playing, and it starts with nothing playing. `liveAt`/`liveKey`: its last live row.
        ...(stationId ? {
            pub: { stationId, cohosts: [], booted: [], autoAccept: false, presence: "booth", pausedSince: now, liveAt: null, liveKey: null },
        } : {}),
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
const publicMembers = (s) => connected(s).map(({ id, name, joinedAt, status, v, visits }) => ({
    id, name, joinedAt, status, ...(v ? { v } : {}),
    // A regular at this station: only the fact, never the count or the key hash it was read by.
    ...((visits ?? 0) >= REGULAR_SESSIONS ? { regular: true } : {}),
}));

/**
 * For the host alone (UX spec §7, "Ben · 5th time here"): how many of this station's sessions each connected listener
 * stayed for before this one, by member id. Listeners with none are left out. Nobody else is ever sent a count.
 */
const visitsMsg = (s) => ({
    to: s.host,
    msg: { t: "visits", visits: Object.fromEntries(connected(s).filter((m) => m.visits > 0).map((m) => [m.id, m.visits])) },
});

/** How long a member has been connected to this session in all, as of [now]. */
const stayOf = (m, now) => (m.inMs ?? 0) + (m.leftAt === null ? now - (m.sinceAt ?? m.joinedAt) : 0);
/**
 * A listener is leaving for good (their slot lapsed, or the session is ending): their time joins what the session
 * remembers for their key hash, and the first time that reaches REGULAR_STAY_MS they go out in `regulars`. -1 marks
 * one already counted, so coming back never counts the same session twice.
 */
function countStay(ctx, m) {
    const p = ctx.s.pub;
    if (!p || !m.kh) return;
    // The scoreboard's listener time: every listener's, however short (the host is no listener).
    if (m.id !== ctx.s.host) {
        p.time ??= {};
        if (m.kh in p.time || Object.keys(p.time).length < MAX_STAYS) p.time[m.kh] = (p.time[m.kh] ?? 0) + stayOf(m, ctx.now);
    }
    p.stays ??= {};
    const before = p.stays[m.kh];
    if (before === -1) return;
    const total = (before ?? 0) + stayOf(m, ctx.now);
    if (total >= REGULAR_STAY_MS) {
        p.stays[m.kh] = -1;
        (ctx.regulars ??= []).push(m.kh);
    } else if (before !== undefined || Object.keys(p.stays).length < MAX_STAYS) {
        p.stays[m.kh] = total;
    }
}

const memberById = (s, id) => s.members.find((m) => m.id === id);

/** What a listener did in a session, for the scoreboard: one bit each, kept by key hash and never sent to anyone. */
export const ACT = { react: 1, say: 2, save: 4, request: 8 };
function noteAct(s, memberId, bit) {
    const p = s.pub;
    const kh = memberById(s, memberId)?.kh;
    if (!p || !kh || memberId === s.host) return false;
    p.acts ??= {};
    if (p.acts[kh] & bit) return false;
    if (!(kh in p.acts) && Object.keys(p.acts).length >= MAX_STAYS) return false;
    p.acts[kh] = (p.acts[kh] ?? 0) | bit;
    return true;
}

/** Upper bounds, in minutes, of the scoreboard's stay buckets; the last bucket is everything longer. */
export const STAY_BUCKETS_MIN = [2, 10, 30];
/**
 * A session's totals for the scoreboard (build plan, decision 10), made as it ends: numbers only, no person, station
 * or song in them. Null for a session that never reached the air.
 */
function sessionStats(s) {
    const p = s.pub;
    if (!p || !p.everLive) return null;
    const times = Object.values(p.time ?? {});
    const acts = Object.values(p.acts ?? {});
    const did = (bit) => acts.filter((a) => a & bit).length;
    const stays = [0, 0, 0, 0];
    for (const ms of times) {
        const i = STAY_BUCKETS_MIN.findIndex((min) => ms < min * 60_000);
        stays[i < 0 ? STAY_BUCKETS_MIN.length : i]++;
    }
    return {
        listeners: times.length,
        minutes: Math.round(times.reduce((a, b) => a + b, 0) / 60_000),
        stays,
        reacted: did(ACT.react), said: did(ACT.say), saved: did(ACT.save), requested: did(ACT.request),
        requestsTaken: p.taken ?? 0,
    };
}
/** In a session, a member whose key hash the host made a co-host. */
function isCohost(s, id) {
    if (!s.pub) return false;
    const kh = memberById(s, id)?.kh;
    return !!kh && s.pub.cohosts.includes(kh);
}
/** The host or a co-host: who sees every request, and may add, take out and accept songs. */
const isStaff = (s, id) => id === s.host || isCohost(s, id);
/** Everyone connected but the host: a session's "12 listening". */
const listenerCount = (s) => connected(s).filter((m) => m.id !== s.host).length;
/** What every phone in a session knows about it, as `session` and in `state`. Member ids only, never a key hash. */
function sessionInfo(s) {
    return {
        station: s.pub.stationId, listeners: listenerCount(s),
        cohosts: connected(s).filter((m) => isCohost(s, m.id)).map((m) => m.id),
        autoAccept: s.pub.autoAccept, presence: s.pub.presence,
        // Only once the host has called it: the playing song is the session's last.
        ...(s.pub.lastSong === s.trackKey ? { lastSong: true } : {}),
    };
}
const sessionMsg = (s) => ({ to: "all", msg: { t: "session", ...sessionInfo(s) } });

/*
 * Open decks (UX spec §5). While the host has them open, up to DECK_SEATS listeners are DJs, each keeping one song
 * ready (`picks`), and the turns go round: the host, then each DJ in the order they sat down. The host's phone still
 * plays everything from the queue, as it does today: when a song starts, the room puts the next DJ's ready pick at
 * the front of the queue as their add (`pending` says whose), and the host's own turn is simply the host's queue.
 * DJs are kept by key hash, as co-hosts are, so a seat holds across a reconnect.
 *   last     whose song is playing: "host" or a DJ's key hash
 *   pending  whose turn is next, once decided: "host", a DJ's key hash (their pick is in the queue), or null
 */
const decksOf = (s) => s.pub?.decks ?? null;
/** The member id a key hash goes by now: the connected one, else one in its 60 s; null when they're gone. */
function idOfKh(s, kh) {
    return (s.members.find((m) => m.kh === kh && m.leftAt === null) ?? s.members.find((m) => m.kh === kh))?.id ?? null;
}
/** What every phone knows about the decks: member ids only. `ready`: that DJ has a song waiting or already queued. */
function decksInfo(s) {
    const d = decksOf(s);
    const who = (k) => (k === "host" ? s.host : k ? idOfKh(s, k) : null);
    return {
        open: true,
        djs: d.djs.map((kh) => ({ id: idOfKh(s, kh), ready: !!d.picks[kh] || d.pending === kh })).filter((x) => x.id),
        line: d.line.map((kh) => idOfKh(s, kh)).filter(Boolean),
        now: who(d.last), next: who(d.pending),
    };
}
const decksMsg = (s) => ({ to: "all", msg: { t: "decksState", ...(decksOf(s) ? decksInfo(s) : { open: false, djs: [], line: [], now: null, next: null }) } });
/** Off the decks or out of the line, their ready song with them; the first in line takes a free seat. */
function offDecks(s, kh) {
    const d = decksOf(s);
    if (!d || !(d.djs.includes(kh) || d.line.includes(kh))) return false;
    d.djs = d.djs.filter((k) => k !== kh);
    d.line = d.line.filter((k) => k !== kh);
    delete d.picks[kh];
    delete d.misses[kh];
    while (d.djs.length < DECK_SEATS && d.line.length) d.djs.push(d.line.shift());
    return true;
}
/**
 * Decides whose turn is next, if it isn't decided yet: the first after `last` in the round with a song ready has it
 * put at the front of the queue as their add; reaching the host means the host's own queue is next. `atLoad`: a song
 * just started, so a DJ passed over with nothing ready has missed their turn (DECK_MISSES in a row and they're off the
 * decks, told with `deckOut`). Between songs (someone sat down, or readied a song) nobody is counted as missing, and a
 * DJ who comes before the host in the round can still take the turn the host was about to have.
 */
function fillNext(ctx, atLoad) {
    const { s } = ctx;
    const d = decksOf(s);
    if (!d || (d.pending && d.pending !== "host")) return false;
    const order = ["host", ...d.djs];
    const at = Math.max(0, order.indexOf(d.last));
    const out = [];
    let changed = false;
    for (let i = 1; i <= order.length; i++) {
        const who = order[(at + i) % order.length];
        if (who === "host") {
            if (d.pending !== "host") { d.pending = "host"; changed = true; }
            break;
        }
        const pick = d.picks[who];
        const id = idOfKh(s, who);
        if (pick && id) {
            delete d.picks[who];
            d.misses[who] = 0;
            s.qv++;
            insertAdds(s, [{ ...pick, by: id }], true);
            settle(s);
            d.pending = who;
            changed = true;
            break;
        }
        if (atLoad) {
            d.misses[who] = (d.misses[who] ?? 0) + 1;
            changed = true;
            if (d.misses[who] >= DECK_MISSES) out.push(who);
        }
    }
    for (const kh of out) {
        const id = idOfKh(s, kh);
        offDecks(s, kh);
        if (id) ctx.out.push({ to: "all", msg: { t: "deckOut", memberId: id, reason: "skipped" } });
    }
    return changed;
}
/** A song just started: it was the pending DJ's, or the host's, and the next turn is decided. */
function decksOnLoad(ctx) {
    const { s } = ctx;
    const d = decksOf(s);
    if (!d) return;
    const pendingId = d.pending && d.pending !== "host" ? idOfKh(s, d.pending) : null;
    if (d.pending === "host" || d.pending == null) {
        d.last = "host";
        d.pending = null;
    } else if (pendingId && s.track.by === pendingId) {
        d.last = d.pending;
        d.pending = null;
    } else if (!pendingId || !s.queue.some((t) => t.by === pendingId)) {
        // The host played something of its own and the DJ's song is no longer waiting (taken out, or they left).
        d.last = "host";
        d.pending = null;
    } // else the DJ's song is still next in the queue: nothing to decide yet
    fillNext(ctx, true);
    ctx.out.push(decksMsg(s));
}

/*
 * A song's crowd counts (UX spec §4.4, §4.5): how many people reacted to it and how many saved it, never who. Kept
 * for the song that's playing only, and made on first use, so a session nobody reacts in stores nothing new. A
 * reaction is stored once a person a song (a 54-person room writes at most 54 times a song for them, where storing
 * every reaction would write 53 times a second: the load check).
 */
function statsOf(s) {
    const p = s.pub;
    if (!p.stats || p.stats.key !== s.trackKey) {
        p.stats = { key: s.trackKey, reacted: [], saved: [], fav: false, sentAt: p.stats?.sentAt ?? null, dirty: false };
    }
    return p.stats;
}
const publicStats = (st) => ({ trackKey: st.key, reactions: st.reacted.length, saves: st.saved.length, favourite: st.fav });
const statsMsg = (s) => ({ to: "all", msg: { t: "songStats", ...publicStats(s.pub.stats) } });
/** The counts changed: everyone hears now, or at the end of the gap since they last did (the alarm sends it). */
function statsChanged(ctx) {
    const st = ctx.s.pub.stats;
    if (st.sentAt === null || ctx.now - st.sentAt >= STATS_GAP_MS) {
        st.sentAt = ctx.now;
        st.dirty = false;
        ctx.out.push(statsMsg(ctx.s));
    } else {
        st.dirty = true;
    }
}

/*
 * The live directory (spec §3; build plan, task 18): a session on the air has a row in the Community database's `live`
 * table, written by the shell from what step() returns. The room decides when: its first song, then each new song,
 * pause or play (at most one write a LIVE_GAP_MS), and a heartbeat every LIVE_BEAT_MS that keeps the row fresh and the
 * counts close. A song on its way counts as playing, so a new song is one write, not one at the load and another when
 * it starts.
 */
const onAir = (s) => s.timeline.playing || s.phase.kind === "preparing";
const liveKeyOf = (s) => `${s.trackKey}|${onAir(s) ? 1 : 0}`;
/** When the session's next row is due; null for a private room or before the first song. */
function liveDueAt(s) {
    const p = s.pub;
    if (!p || !s.track) return null;
    if (p.liveAt === null) return 0;
    return p.liveAt + (liveKeyOf(s) === p.liveKey ? LIVE_BEAT_MS : LIVE_GAP_MS);
}
const graphemes = typeof Intl.Segmenter === "function" ? new Intl.Segmenter() : null;
/** A name's first letter as a face in the directory shows it (one grapheme: a flag stays a flag); null with no name. */
function initialOf(name) {
    if (!name) return null;
    const first = graphemes ? graphemes.segment(name)[Symbol.iterator]().next().value.segment : [...name][0];
    const up = first.toUpperCase();
    return [...up].length === 1 ? up : first; // "ß" would be "SS"
}
/** Up to 4 faces for the directory, the host's first, then by who joined first: initials and member ids, never names. */
function crowdOf(s) {
    const host = findConnected(s, s.host);
    const rest = connected(s).filter((m) => m.id !== s.host).sort((a, b) => a.joinedAt - b.joinedAt);
    return [...(host ? [host] : []), ...rest].slice(0, 4).map((m) => ({ i: initialOf(m.name), id: m.id }));
}
/** The session's row as of [now], marking it written. The song goes without `by`: a member id means nothing outside. */
function liveRow(s, now) {
    s.pub.liveAt = now;
    s.pub.everLive = true;
    s.pub.liveKey = liveKeyOf(s);
    const { by, ...track } = s.track;
    return {
        stationId: s.pub.stationId, room: s.code, track, listeners: listenerCount(s), paused: !onAir(s),
        crowd: crowdOf(s), startedAt: s.createdAt, seenAt: now,
    };
}

/** The waiting requests member [id] may see: all of them for the host and co-hosts, a listener's own for a listener. */
const requestsFor = (s, id) => (isStaff(s, id) ? s.suggestions : s.suggestions.filter((x) => x.from === id));
const requestsMsg = (s, id) => ({ to: id, msg: { t: "requests", requests: requestsFor(s, id) } });

/**
 * Guests' Play next and Add to queue go straight in: the host's app knows guest adds (`v` ≥ 2 in its hello) and its
 * "Let guests add songs" switch is on (`ga`, on unless it said otherwise). Read from whoever hosts now, so a new host's
 * own switch takes over with them; an older host keeps the approvals it knows. Never in a session: there, listeners'
 * songs are requests, which wait for the host or a co-host.
 */
export function guestsCanAdd(s) {
    if (s.pub) return false;
    const host = s.members.find((m) => m.id === s.host);
    return !!host && (host.v ?? 1) >= 2 && host.ga !== false;
}

/** What phones see: never the key hash, tokens, or members who have dropped. */
export function publicState(s) {
    return {
        rev: s.rev, host: s.host, track: s.track, trackKey: s.trackKey, timeline: s.timeline, queue: s.queue,
        // A session's requests go to the host and co-hosts alone (`requests`), never in what everyone gets.
        members: publicMembers(s), suggestions: s.pub ? [] : s.suggestions,
        // The guests' deadline only, as always: the host's is the room's business.
        phase: s.phase.kind === "preparing"
            ? { kind: "preparing", trackKey: s.phase.trackKey, deadlineMs: s.phase.deadlineMs }
            : { kind: "playing" },
        v: s.pub ? PUBLIC_V : PROTOCOL_V, guestsAdd: guestsCanAdd(s), qv: s.qv ?? 0,
        ...(s.pub ? { session: sessionInfo(s) } : {}),
        // The playing song's counts, for someone who joins mid-song; absent until anyone reacted to it or saved it.
        ...(s.pub?.stats && s.pub.stats.key === s.trackKey ? { songStats: publicStats(s.pub.stats) } : {}),
        ...(s.pub?.decks ? { decks: decksInfo(s) } : {}),
    };
}

/** The Join screen's view (GET /v1/rooms/{code}); a session's also names its station and audience. */
export function preview(s) {
    const host = s.members.find((m) => m.id === s.host);
    return {
        hostName: host?.name ?? s.hostName,
        memberCount: connected(s).length,
        full: s.members.length >= capacity(s),
        ...(s.pub ? { station: s.pub.stationId, listeners: listenerCount(s) } : {}),
        ...(s.track ? { track: s.track } : {}),
    };
}

/**
 * The earliest pending timer: room expiry, empty-room close, a 60 s handover/prune, the prepare deadline (§2): the
 * guests' while the host is ready, else the end of the wait for the host (the guests' alone would start nothing).
 * A session also ends PAUSE_END_MS into a pause, and wakes when its next live row is due.
 */
export function nextAlarm(s) {
    const times = [s.createdAt + MAX_AGE_MS];
    if (connected(s).length === 0) times.push(s.lastLeftAt + EMPTY_CLOSE_MS);
    for (const m of s.members) if (m.leftAt !== null) times.push(m.leftAt + HANDOVER_MS);
    if (s.phase.kind === "preparing") times.push(hostSettled(s) ? s.phase.deadlineMs : hostDeadline(s));
    if (s.pub?.pausedSince != null) times.push(s.pub.pausedSince + PAUSE_END_MS);
    const live = liveDueAt(s);
    if (live !== null) times.push(live);
    if (s.pub?.stats?.dirty) times.push(s.pub.stats.sentAt + STATS_GAP_MS);
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
 * joins late, the host is never left behind); or the room has waited for its host as long as it will. A session waits
 * for its host alone (build plan, decision 1): 50 strangers' phones must never add silence to every song, and a listener
 * who isn't ready joins mid-song, as a late joiner does.
 */
function startDue(s, now) {
    if (s.phase.kind !== "preparing") return false;
    if (now >= hostDeadline(s)) return true;
    return hostSettled(s) && (!!s.pub || allSettled(s) || now >= s.phase.deadlineMs);
}
/** Starts the song if its handshake is over: after anything that may settle it (a status, a leave, a new host, the alarm). */
function startIfDue(ctx) {
    if (!startDue(ctx.s, ctx.now)) return false;
    startPlayback(ctx);
    return true;
}

/**
 * [by] names the member who caused the change (play, pause, seek); the room's own start has none. A session counts
 * how long it hasn't played since: PAUSE_END_MS of that ends it, and a seek while paused doesn't reset it.
 */
function setTimeline(ctx, timeline, by) {
    ctx.s.timeline = timeline;
    if (ctx.s.pub) ctx.s.pub.pausedSince = timeline.playing ? null : (ctx.s.pub.pausedSince ?? ctx.now);
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

/*
 * A session's requests: listeners' songs waiting for the host or a co-host (UX spec §4.2). They're kept in
 * `s.suggestions`, which a session never broadcasts: the host and co-hosts get the whole list, a listener only their own
 * (`requests`).
 */

/** The waiting requests to the host and every co-host, and to each listener in [touched] (whose own list changed). */
function pushRequests(ctx, touched = []) {
    const { s } = ctx;
    for (const m of connected(s)) if (isStaff(s, m.id)) ctx.out.push(requestsMsg(s, m.id));
    for (const id of new Set(touched)) if (!isStaff(s, id) && findConnected(s, id)) ctx.out.push(requestsMsg(s, id));
}

/** One more waiting request, within MAX_REQUESTS_EACH a listener and MAX_REQUESTS in all; false when it doesn't fit. */
function pushRequest(s, x) {
    if (s.suggestions.length >= MAX_REQUESTS) return false;
    if (s.suggestions.filter((y) => y.from === x.from).length >= MAX_REQUESTS_EACH) return false;
    s.suggestions.push(x);
    return true;
}

/**
 * Whether request [x] fits in the queue now: the room's MAX_QUEUE adds and its MAX_QUEUE_CHARS (30 waiting requests
 * could otherwise carry the queue past what a frame holds). [byRoom]: nobody chose it (Auto-accept), so its requester's
 * MAX_GUEST_WAITING counts too, as for a guest's add; the host's or a co-host's pick isn't held to that, as a suggestion
 * the host accepts isn't.
 */
function fitsQueue(s, x, byRoom) {
    if (s.adds.length >= MAX_QUEUE || (byRoom && room(s, x.from) <= 0)) return false;
    return JSON.stringify(s.queue).length + JSON.stringify({ ...x.track, by: x.from }).length + 1 <= MAX_QUEUE_CHARS;
}

/** Request [x] into the queue, its requester's, by Play next ([next]) or Add to queue. The caller bumps qv and settles. */
function queueRequest(s, x, next) {
    if (s.pub) s.pub.taken = (s.pub.taken ?? 0) + 1; // the scoreboard's "requests taken"
    return insertAdds(s, [{ ...x.track, by: x.from }], next);
}

/** Closes the room, everyone told why. */
function endRoom(ctx, reason) {
    // Everyone still in the session, or inside their 60 s, has their stay counted now.
    if (ctx.s?.pub) for (const m of ctx.s.members) countStay(ctx, m);
    if (ctx.s?.pub) ctx.stats = sessionStats(ctx.s) ?? undefined;
    ctx.out.push({ to: "all", msg: { t: "ended", reason } });
    ctx.closed = true;
    return true;
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
    if (ctx.closed) {
        // A session takes its row out of the directory; one whose row went (stationGone) has nothing to take out.
        const p = ctx.s.pub;
        const live = p?.liveAt != null ? { remove: { stationId: p.stationId, room: ctx.s.code } } : undefined;
        return { state: null, out: ctx.out, alarmAt: null, closed: true, live, regulars: regularsOf(ctx), stats: ctx.stats };
    }
    let next = changed ? ctx.s : state;
    let live;
    const due = liveDueAt(next);
    if (due !== null && now >= due) {
        // A row due is a change to store (liveAt moves), even when the event itself changed nothing (the heartbeat).
        if (next === state) next = structuredClone(state);
        live = { upsert: liveRow(next, now) };
    }
    return { state: next, out: ctx.out, alarmAt: nextAlarm(next), bind: ctx.bind, reject: ctx.reject, kick: ctx.kick, live, regulars: regularsOf(ctx) };
}

const regularsOf = (ctx) => (ctx.regulars?.length ? { stationId: ctx.s.pub.stationId, listeners: ctx.regulars } : undefined);

const EVENTS = {
    hello(ctx) {
        const { s, now, event, msg } = ctx;
        if (s.pub) {
            // A session takes an app that speaks it (build plan, decision 5), known by its Community key's hash, which the
            // shell works out (decision 3), or the host by the host key. Whoever the host booted stays out.
            if (!(Number.isSafeInteger(msg.v) && msg.v >= PUBLIC_V)) { ctx.reject = { code: 4426, reason: "update" }; return false; }
            if (!event.hostKeyOk && !event.listenerHash) { ctx.reject = { code: 4401, reason: "key" }; return false; }
            if (event.listenerHash && s.pub.booted.includes(event.listenerHash)) { ctx.reject = { code: 4403, reason: "booted" }; return false; }
        }
        const name = cleanName(msg.name);
        const before = guestsCanAdd(s);
        // Only the private token rejoins a slot: every member can see every memberId (spec §3).
        let m = typeof msg.resumeToken === "string" ? s.members.find((x) => x.token === msg.resumeToken) : undefined;
        if (!m) {
            if (s.members.length >= capacity(s)) { ctx.reject = { code: 4409, reason: "full" }; return false; }
            m = { id: event.newId, token: event.newToken, name, joinedAt: now, status: "buffering", leftAt: null };
            // A session member's key hash: what co-hosting and booting go by. Kept on resume; it never leaves the room.
            if (s.pub) {
                m.kh = event.listenerHash ?? null;
                m.sinceAt = now;
                // How many of this station's sessions they stayed for before, as the shell read it: 3 makes a regular.
                if (m.kh && Number.isSafeInteger(event.visits) && event.visits > 0) m.visits = Math.min(event.visits, 9_999);
            }
            s.members.push(m);
        } else {
            m.leftAt = null;
            if (s.pub) m.sinceAt = now;
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
        // In a session, the requests this member may see, and everyone hears the new count.
        if (s.pub) ctx.out.push(requestsMsg(s, m.id), sessionMsg(s));
        // The host hears a returning listener's count when they arrive, and everyone's when the host itself (re)connects.
        if (s.pub && s.host && findConnected(s, s.host) && (m.visits > 0 || (m.id === s.host && connected(s).some((x) => x.visits > 0)))) {
            ctx.out.push(visitsMsg(s));
        }
        return true;
    },

    close(ctx) {
        const { s, now, event } = ctx;
        const m = findConnected(s, event.from);
        if (!m) return false;
        // Before leftAt moves: the time of this connection joins the member's total.
        if (s.pub) m.inMs = stayOf(m, now);
        m.leftAt = now;
        if (connected(s).length === 0) s.lastLeftAt = now;
        s.rev++;
        ctx.out.push(membersMsg(s));
        if (s.pub) ctx.out.push(sessionMsg(s));
        startIfDue(ctx); // the last phone waited for left, or the host did: the guests aren't held for it
        return true;
    },

    alarm(ctx) {
        const { s, now } = ctx;
        const empty = connected(s).length === 0;
        if (now >= s.createdAt + MAX_AGE_MS || (empty && now >= s.lastLeftAt + EMPTY_CLOSE_MS)) {
            return endRoom(ctx, s.pub && now >= s.createdAt + MAX_AGE_MS ? "max_age" : "closed");
        }
        if (s.pub) {
            // A session ends with its host and is never passed on (spec §4): gone 60 s, or not playing for 15 minutes.
            const host = memberById(s, s.host);
            if (host && host.leftAt !== null && now >= host.leftAt + HANDOVER_MS) return endRoom(ctx, "host_left");
            if (s.pub.pausedSince !== null && now >= s.pub.pausedSince + PAUSE_END_MS) return endRoom(ctx, "paused");
        }
        let changed = false;
        // Counts that changed inside the gap go out at its end.
        const stats = s.pub?.stats;
        if (stats?.dirty && now >= stats.sentAt + STATS_GAP_MS) {
            stats.sentAt = now;
            stats.dirty = false;
            ctx.out.push(statsMsg(s));
            changed = true;
        }
        const expired = s.members.filter((m) => m.leftAt !== null && now >= m.leftAt + HANDOVER_MS);
        if (expired.length) {
            const before = guestsCanAdd(s);
            for (const m of expired) countStay(ctx, m);
            // A DJ gone for good leaves the decks, unless they're back on another socket.
            if (decksOf(s)) {
                const staying = new Set(s.members.filter((m) => !expired.includes(m)).map((m) => m.kh).filter(Boolean));
                let left = false;
                for (const m of expired) if (m.kh && !staying.has(m.kh)) left = offDecks(s, m.kh) || left;
                if (left) ctx.out.push(decksMsg(s));
            }
            const gone = new Set(expired.map((m) => m.id));
            s.members = s.members.filter((m) => !gone.has(m.id));
            if (s.pub) {
                // A co-host gone for good frees the place; one back on another socket still holds their key hash.
                const held = new Set(s.members.map((m) => m.kh).filter(Boolean));
                s.pub.cohosts = s.pub.cohosts.filter((k) => held.has(k));
            }
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
                if (s.suggestions.length !== pending) {
                    if (s.pub) pushRequests(ctx); // a session's requests, to the host and co-hosts only
                    else ctx.out.push(suggestionsMsg(s));
                }
            }
            changed = true;
        }
        // The guests' deadline (with the host ready), or the end of the wait for the host. A new 60 s heir counts as host.
        if (startIfDue(ctx)) changed = true;
        return changed;
    },

    /**
     * From the shell: the session's station may not be on the air any more (its live row was refused: removed, hidden
     * or blocked), another session of the station took the row ("replaced"), or its owner deleted it.
     */
    stationGone(ctx) {
        const { s, event } = ctx;
        if (!s.pub) return false;
        s.pub.liveAt = null; // the row is gone, or another session's: nothing of ours to take out of the directory
        return endRoom(ctx, event.reason === "replaced" ? "replaced" : "station_removed");
    },
};

/** A DJ's song is ready: kept, and put in the queue at once if the next turn is theirs to take. */
function readied(ctx, d, kh, track) {
    const { s } = ctx;
    d.picks[kh] = track;
    const before = s.qv;
    fillNext(ctx, false);
    s.rev++;
    if (s.qv !== before) ctx.out.push(queueMsg(s));
    ctx.out.push(decksMsg(s));
    return true;
}

const MESSAGES = {
    load(ctx) {
        const { s, now, msg, event } = ctx;
        const track = cleanTrack(msg.track);
        if (!track) return false;
        // The host called the playing song the last one: its end, or a skip past it, is the session's.
        if (s.pub && s.pub.lastSong === s.trackKey && s.track) return endRoom(ctx, "last_song");
        const left = msg.why === "back" ? s.track : null;
        s.track = track.by ? track : { ...track, by: event.from };
        s.trackKey++;
        // The last song's counts end with it; only when they last went out is kept, for the gap.
        if (s.pub?.stats) s.pub.stats = { key: s.trackKey, reacted: [], saved: [], fav: false, sentAt: s.pub.stats.sentAt, dirty: false };
        setHostQueue(ctx, msg.why === "pick", left);
        decksOnLoad(ctx);
        s.timeline = { positionMs: cleanPosition(msg.positionMs, track) ?? 0, atRoomMs: now, playing: false };
        s.phase = { kind: "preparing", trackKey: s.trackKey, deadlineMs: now + PREPARE_MS, hostDeadlineMs: now + HOST_WAIT_CAP_MS };
        if (s.pub) s.pub.pausedSince = null; // a song on its way is playing, as far as a session's 15 minutes go
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
        // In a session only the host's status counts (decision 1), and listeners' stay quiet (decision 2): no write,
        // no `members` to 54 phones for each buffering change.
        if (s.pub && event.from !== s.host) return false;
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
        // In a session the host and co-hosts add straight in; a listener's songs go through `request`.
        if (s.pub) {
            if (!isStaff(s, event.from)) { answerAdd(ctx, next); return false; }
            return addSongs(ctx, tracks, next);
        }
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
        if (s.pub) return false; // a session's listeners request instead
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
        if (s.pub) return false; // requests are taken with `requestAction`
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
        // The host takes out any song, and in a session so do co-hosts; anyone else only one of theirs.
        const anySong = s.pub ? isStaff(s, event.from) : event.from === s.host;
        if (at < 0 || (!anySong && s.queue[at].by !== event.from)) return false;
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
        // A session takes its text faces, by id; a private room the six emoji.
        if (!(s.pub ? FACES : EMOJI).includes(msg.emoji)) return false;
        // A session keeps no reaction clock: 53 listeners reacting once a second would store the whole room 53 times a
        // second (the load check). The shell holds each socket to REACT_GAP_MS instead (src/listen-room.js).
        if (s.pub) {
            ctx.out.push({ to: "all", msg: { t: "reaction", from: event.from, emoji: msg.emoji } });
            // Counted once a person a song: only that first one changes what's stored.
            if (!s.track) return false;
            const st = statsOf(s);
            if (st.reacted.includes(event.from)) return false;
            st.reacted.push(event.from);
            noteAct(s, event.from, ACT.react);
            if (!st.fav && onAir(s) && st.reacted.length >= FAVOURITE_MIN && st.reacted.length * 3 >= connected(s).length) st.fav = true;
            statsChanged(ctx);
            return true;
        }
        const last = s.reactAt[event.from];
        if (last !== undefined && now - last < REACT_GAP_MS) return false;
        s.reactAt[event.from] = now;
        ctx.out.push({ to: "all", msg: { t: "reaction", from: event.from, emoji: msg.emoji } });
        return true;
    },

    makeHost(ctx) {
        const { s, msg } = ctx;
        if (s.pub) return false; // a session is its host's: co-hosts help, nobody takes over
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
        return endRoom(ctx, "host_ended");
    },

    // ── Sessions only: each of these does nothing in a private room ──────────

    /**
     * The host's "End after this song": everyone hears it is the last song (`session` carries `lastSong`), and the
     * host's next `load`, which its phone sends when the song ends or is skipped, ends the session with `last_song`.
     * `{ on: false }` takes it back. End now is still `end`.
     */
    lastSong(ctx) {
        const { s, msg } = ctx;
        if (!s.pub || !s.track) return false;
        const on = msg.on !== false;
        if (on === (s.pub.lastSong === s.trackKey)) return false;
        if (on) s.pub.lastSong = s.trackKey; else delete s.pub.lastSong;
        s.rev++;
        ctx.out.push(sessionMsg(s));
        return true;
    },

    // ── Open decks (UX spec §5) ──

    /** `{ open }`, the host: opens the decks, or closes them (every ready song is dropped; one already queued stays). */
    decks(ctx) {
        const { s, msg } = ctx;
        if (!s.pub || typeof msg.open !== "boolean" || msg.open === !!decksOf(s)) return false;
        if (msg.open) s.pub.decks = { open: true, djs: [], line: [], picks: {}, misses: {}, last: "host", pending: null };
        else delete s.pub.decks;
        s.rev++;
        ctx.out.push(decksMsg(s));
        return true;
    },

    /** `{ track }`, a listener: a seat on the decks with this song ready, or a place in the line when the seats are taken. */
    deckJoin(ctx) {
        const { s, msg, event } = ctx;
        const d = decksOf(s);
        const kh = memberById(s, event.from)?.kh;
        const track = cleanTrack(msg.track);
        if (!d || !kh || event.from === s.host || !track) return false;
        if (!d.djs.includes(kh) && !d.line.includes(kh)) {
            if (d.djs.length < DECK_SEATS) d.djs.push(kh);
            else if (d.line.length < DECK_LINE) d.line.push(kh);
            else { ctx.out.push({ to: event.from, msg: { t: "refused", what: "decks", reason: "full" } }); return false; }
        }
        return readied(ctx, d, kh, track);
    },

    /** `{ track }`, a DJ or someone in the line: their ready song, replacing the one before. */
    deckPick(ctx) {
        const { s, msg, event } = ctx;
        const d = decksOf(s);
        const kh = memberById(s, event.from)?.kh;
        const track = cleanTrack(msg.track);
        if (!d || !kh || !track || !(d.djs.includes(kh) || d.line.includes(kh))) return false;
        return readied(ctx, d, kh, track);
    },

    /** A DJ steps down, or someone leaves the line. */
    deckLeave(ctx) {
        const { s, event } = ctx;
        const kh = memberById(s, event.from)?.kh;
        if (!kh || !offDecks(s, kh)) return false;
        s.rev++;
        ctx.out.push(decksMsg(s));
        return true;
    },

    /** `{ memberId }`, the host: takes someone off the decks or out of the line. */
    deckRemove(ctx) {
        const { s, msg } = ctx;
        const kh = memberById(s, msg.memberId)?.kh;
        if (!kh || !offDecks(s, kh)) return false;
        s.rev++;
        ctx.out.push(decksMsg(s));
        return true;
    },

    /** `{ phrase }`: one of PHRASES, passed on to everyone as `said`. Nothing is stored: the shell holds the pace. */
    say(ctx) {
        const { s, msg, event } = ctx;
        if (!s.pub || !PHRASES.includes(msg.phrase)) return false;
        ctx.out.push({ to: "all", msg: { t: "said", from: event.from, phrase: msg.phrase } });
        return noteAct(s, event.from, ACT.say); // stored only the first time: a phrase otherwise changes nothing
    },

    /** `{ trackKey }`: this person saved the playing song. Counted once each; who saved is never sent. */
    saved(ctx) {
        const { s, msg, event } = ctx;
        if (!s.pub || !s.track || msg.trackKey !== s.trackKey) return false;
        const st = statsOf(s);
        if (st.saved.includes(event.from)) return false;
        st.saved.push(event.from);
        noteAct(s, event.from, ACT.save);
        statsChanged(ctx);
        return true;
    },

    /**
     * `{ track }`, a listener asking for a song (the host and co-hosts add songs instead). With Auto-accept on it goes
     * in as Add to queue, as a guest's add would; otherwise, or when it doesn't fit, it waits for the host or a co-host.
     * The requester always hears what became of it: `requestAnswer {state: "queued" | "waiting" | "full"}`.
     */
    request(ctx) {
        const { s, msg, event } = ctx;
        if (!s.pub || isStaff(s, event.from)) return false;
        const track = cleanTrack(msg.track);
        if (!track) return false;
        const x = { id: event.newId, from: event.from, track };
        noteAct(s, event.from, ACT.request);
        const answer = (state) => ctx.out.push({ to: event.from, msg: { t: "requestAnswer", state } });
        if (s.pub.autoAccept && fitsQueue(s, x, true)) {
            s.qv++;
            queueRequest(s, x, false);
            settle(s);
            s.rev++;
            ctx.out.push(queueMsg(s));
            answer("queued");
            return true;
        }
        if (!event.newId || !pushRequest(s, x)) { answer("full"); return false; }
        s.rev++;
        answer("waiting");
        pushRequests(ctx, [event.from]);
        return true;
    },

    /**
     * `{ id }`, a listener taking back one of their own waiting requests. Nobody is told but the host and co-hosts
     * (their list loses it) and the requester (theirs does). One already answered, or someone else's, does nothing.
     */
    requestCancel(ctx) {
        const { s, msg, event } = ctx;
        if (!s.pub || typeof msg.id !== "string") return false;
        const i = s.suggestions.findIndex((x) => x.id === msg.id && x.from === event.from);
        if (i < 0) return false;
        s.suggestions.splice(i, 1);
        s.rev++;
        pushRequests(ctx, [event.from]);
        return true;
    },

    /**
     * `{ id, action }` from the host or a co-host, on a waiting request: Play next ("next"), Add to queue ("queue"), or
     * "pass", which only its requester hears of (UX spec §4.2), with who passed. One that no longer fits the queue stays
     * waiting, and the sender hears `refused`.
     */
    requestAction(ctx) {
        const { s, msg, event } = ctx;
        if (!s.pub || !isStaff(s, event.from)) return false;
        const i = s.suggestions.findIndex((x) => x.id === msg.id);
        if (i < 0 || !["next", "queue", "pass"].includes(msg.action)) return false;
        const x = s.suggestions[i];
        if (msg.action !== "pass" && !fitsQueue(s, x, false)) {
            ctx.out.push({ to: event.from, msg: { t: "refused", what: "request", reason: "queue_full" } });
            return false;
        }
        s.suggestions.splice(i, 1);
        if (msg.action === "pass") {
            if (findConnected(s, x.from)) ctx.out.push({ to: x.from, msg: { t: "requestPassed", track: x.track, by: event.from } });
        } else {
            s.qv++;
            queueRequest(s, x, msg.action === "next");
            settle(s);
            ctx.out.push(queueMsg(s));
        }
        s.rev++;
        pushRequests(ctx, [x.from]);
        return true;
    },

    /**
     * `{ on }`, the host's Auto-accept: requests go straight in as Add to queue. Turning it on lets in the ones waiting, in
     * the order they came; one that doesn't fit stays waiting rather than being lost.
     */
    autoAccept(ctx) {
        const { s, msg } = ctx;
        if (!s.pub || typeof msg.on !== "boolean" || s.pub.autoAccept === msg.on) return false;
        s.pub.autoAccept = msg.on;
        const taken = [];
        if (msg.on) {
            const waiting = s.suggestions;
            s.suggestions = [];
            for (const x of waiting) {
                if (!fitsQueue(s, x, true)) { s.suggestions.push(x); continue; }
                if (!taken.length) s.qv++;
                queueRequest(s, x, false);
                taken.push(x.from);
            }
            if (taken.length) { settle(s); ctx.out.push(queueMsg(s)); }
        }
        s.rev++;
        ctx.out.push(sessionMsg(s));
        if (taken.length) pushRequests(ctx, taken);
        return true;
    },

    /**
     * `{ memberId, on }`, the host making a listener a co-host or not, at most MAX_COHOSTS. Kept by key hash, so a co-host
     * back on a new socket is still one; one gone for good frees the place (the alarm's prune).
     */
    cohost(ctx) {
        const { s, msg, event } = ctx;
        if (!s.pub || typeof msg.on !== "boolean") return false;
        const m = findConnected(s, msg.memberId);
        if (!m || m.id === s.host || !m.kh || s.pub.cohosts.includes(m.kh) === msg.on) return false;
        if (msg.on && s.pub.cohosts.length >= MAX_COHOSTS) {
            ctx.out.push({ to: event.from, msg: { t: "refused", what: "cohost", reason: "max_cohosts" } });
            return false;
        }
        s.pub.cohosts = msg.on ? [...s.pub.cohosts, m.kh] : s.pub.cohosts.filter((k) => k !== m.kh);
        s.rev++;
        ctx.out.push(sessionMsg(s), requestsMsg(s, m.id));
        return true;
    },

    /**
     * `{ action: "skip" }` from a co-host, passed on to the host's phone, which plays the room (build plan, decision 4),
     * with who asked and the song it was for, so a host already past that song ignores it. Refused at once while the
     * host's phone is away. The room itself doesn't change: it follows the host, as always.
     */
    command(ctx) {
        const { s, msg, event } = ctx;
        if (!s.pub || !isCohost(s, event.from) || msg.action !== "skip") return false;
        if (!findConnected(s, s.host)) {
            ctx.out.push({ to: event.from, msg: { t: "refused", what: "skip", reason: "host_reconnecting" } });
            return false;
        }
        ctx.out.push({ to: s.host, msg: { t: "command", action: "skip", from: event.from, trackKey: s.trackKey } });
        return false;
    },

    /**
     * `{ memberId }`, the host putting a listener out for the rest of the session. They hear it first; then their slot,
     * requests and reaction clock go, their key hash is remembered so a hello with it is refused (4403), and `kick` has
     * the shell close their socket. Every slot on that key goes, an older one from before a reconnect too. Never a
     * co-host (take that away first) or the host.
     */
    boot(ctx) {
        const { s, msg, event } = ctx;
        if (!s.pub) return false;
        const m = memberById(s, msg.memberId);
        if (!m || m.id === s.host) return false;
        if (isCohost(s, m.id)) {
            ctx.out.push({ to: event.from, msg: { t: "refused", what: "boot", reason: "cohost" } });
            return false;
        }
        if (m.kh && !s.pub.booted.includes(m.kh)) {
            s.pub.booted.push(m.kh);
            if (s.pub.booted.length > MAX_BOOTED) s.pub.booted.shift();
        }
        const out = new Set(s.members.filter((x) => x.id === m.id || (m.kh && x.kh === m.kh)).map((x) => x.id));
        for (const id of out) if (findConnected(s, id)) ctx.out.push({ to: id, msg: { t: "ended", reason: "booted" } });
        s.members = s.members.filter((x) => !out.has(x.id));
        if (m.kh && offDecks(s, m.kh)) ctx.out.push(decksMsg(s));
        const pending = s.suggestions.length;
        s.suggestions = s.suggestions.filter((x) => !out.has(x.from));
        for (const id of out) delete s.reactAt[id];
        ctx.kick = [...out];
        s.rev++;
        ctx.out.push(membersMsg(s), sessionMsg(s));
        if (s.suggestions.length !== pending) pushRequests(ctx);
        return true;
    },

    /** `{ state: "booth" | "away" }`, the host at the phone or not, so listeners know whether a request will be seen. */
    presence(ctx) {
        const { s, msg } = ctx;
        if (!s.pub || (msg.state !== "booth" && msg.state !== "away") || s.pub.presence === msg.state) return false;
        s.pub.presence = msg.state;
        s.rev++;
        ctx.out.push(sessionMsg(s));
        return true;
    },
};
