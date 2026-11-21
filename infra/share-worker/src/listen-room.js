/**
 * ListenRoom: one Durable Object per Listen Together room (spec 2026-09-24 §2). A thin shell around the
 * pure src/room.js: it keeps the room in storage (hibernation drops memory), binds each socket to its
 * member with serializeAttachment, sends what step() says to send, and arms the single alarm.
 */
import { step, preview, capacity, EMOJI, REACT_GAP_MS } from "./room.js";
import { removeLive, writeLive } from "./stations-api.js";
import { sameHex, sha256Hex } from "./store.js";
import { validEditKey } from "./validate.js";

/** 32 symbols with no 0/O or 1/I, so `byte & 31` is unbiased and codes read aloud cleanly. */
const ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
export const MSG_PER_SECOND = 20;
/**
 * `add` and `unqueue` each rewrite the stored room and send the whole queue to every phone, and so does a session's
 * `request` with Auto-accept on. The burst covers real use (a 200-song selection is 4 messages, a few quick swipes); a
 * client looping them is then held to the steady rate. Not `suggest`: an older app sends one per song, and the room's
 * own 3-waiting limit already holds it.
 */
export const EDIT_BURST = 10;
export const EDITS_PER_SECOND = 2;
/** Largest frame read; anything bigger closes with 1009 before it is decoded or parsed. */
export const MAX_FRAME = 262_144;
/** A socket that hasn't said hello this long is fair game when the socket cap is reached. */
const UNBOUND_IDLE_MS = 10_000;

/** Room codes are 8 symbols (32^8 ≈ 10^12) so they can't be found by guessing; JOIN_RL caps the guessing too. */
export function randomCode(length = 8) {
    return Array.from(crypto.getRandomValues(new Uint8Array(length)), (b) => ALPHABET[b & 31]).join("");
}

export function base64url(bytes) {
    return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

const memberOf = (ws) => ws.deserializeAttachment()?.memberId ?? null;

export class ListenRoom {
    constructor(ctx, env, now = () => Date.now()) {
        this.ctx = ctx;
        this.env = env;
        this.now = now;
        // ponytail: per-socket message counters live in memory and reset when the room hibernates (spec §2 allows it).
        this.rates = new WeakMap();
        this.edits = new WeakMap();
        this.reacts = new WeakMap();
    }

    /** Internal routes, called by the Worker through the ROOMS binding. */
    async fetch(request) {
        const url = new URL(request.url);
        if (url.pathname === "/init" && request.method === "POST") {
            // With stationId, a session (the Worker checked the station is the host's own).
            const { code, hostName, keyHash, stationId } = await request.json();
            const r = await this.apply({ type: "create", code, hostName, keyHash, stationId });
            return new Response(null, { status: r.reject ? 409 : 201 });
        }
        if (url.pathname === "/station-gone" && request.method === "POST") {
            // The session's station was deleted (src/stations-api.js). Only the Worker reaches this: it forwards /ws alone.
            const { reason } = await request.json();
            await this.apply({ type: "stationGone", reason });
            return new Response(null, { status: 204 });
        }
        const state = (await this.ctx.storage.get("room")) ?? null;
        if (url.pathname === "/preview") return state ? Response.json(preview(state)) : new Response(null, { status: 404 });
        if (url.pathname === "/ws") {
            if (!state) return new Response(null, { status: 404 });
            // Case-insensitive like the Worker's check (RFC 6455): a strict match 426'd `Upgrade: WebSocket` here after
            // the Worker had passed it and charged a JOIN_RL token, so such a client could never connect.
            if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") return new Response(null, { status: 426 });
            // `r=1` = "I have a resume token": a returning member may rejoin a full room; hello checks the token.
            if (state.members.length >= capacity(state) && url.searchParams.get("r") !== "1") return new Response(null, { status: 409 });
            // Sockets that never send hello hold no member slot; this cap stops `?r=1` ones piling up.
            if (this.ctx.getWebSockets().length >= capacity(state) * 2) {
                this.evictIdleUnbound();
                if (this.ctx.getWebSockets().length >= capacity(state) * 2) return new Response(null, { status: 409 });
            }
            const pair = new WebSocketPair();
            this.ctx.acceptWebSocket(pair[1]);
            pair[1].serializeAttachment({ memberId: null, at: this.now() });
            return new Response(null, { status: 101, webSocket: pair[0] });
        }
        return new Response(null, { status: 404 });
    }

    async webSocketMessage(ws, data) {
        if (this.overLimit(ws)) { try { ws.close(1008, "too many messages"); } catch { /* gone */ } return; }
        if ((typeof data === "string" ? data.length : data.byteLength) > MAX_FRAME) {
            try { ws.close(1009, "too big"); } catch { /* gone */ }
            return;
        }
        let msg;
        try { msg = JSON.parse(typeof data === "string" ? data : new TextDecoder().decode(data)); } catch { return; }
        if (!msg || typeof msg !== "object" || typeof msg.t !== "string") return;
        const from = memberOf(ws);
        if (!from) {
            if (msg.t !== "hello") return; // the first message must be hello (spec §2)
            const state = (await this.ctx.storage.get("room")) ?? null;
            const hostKeyOk = !!state && typeof msg.hostKey === "string" && msg.hostKey.length <= 64 && sameHex(await sha256Hex(msg.hostKey), state.keyHash);
            // Sessions know a listener by their Community key (build plan, decision 3): the room gets its hash, the
            // phone's poster id, and the key itself is never stored, logged or sent on. A private room ignores it.
            const listenerHash = validEditKey(msg.communityKey) ? await sha256Hex(msg.communityKey) : null;
            delete msg.communityKey;
            const r = await this.apply(
                { type: "hello", msg, hostKeyOk, listenerHash, newId: randomCode(8), newToken: base64url(crypto.getRandomValues(new Uint8Array(16))) },
                (r) => { if (r.bind) this.bind(ws, r.bind); },
            );
            if (r.reject) ws.close(r.reject.code, r.reject.reason);
            return;
        }
        // A session's `request` too: with Auto-accept on, each one is an add, and sends every phone the queue.
        if ((msg.t === "add" || msg.t === "unqueue" || msg.t === "request") && this.overEditLimit(ws)) {
            // Ignored, not closed. An add or a request is still answered: the sender's app is waiting to say what
            // became of it.
            if (msg.t === "add") this.send(from, { t: "added", by: from, next: msg.next === true, count: 0 });
            if (msg.t === "request") this.send(from, { t: "requestAnswer", state: "full" });
            return;
        }
        // A reaction a REACT_GAP_MS a socket, held here in memory: a session's room keeps no clock for them (it would have to
        // store the room for every one). A private room still keeps its own, which allows exactly what this does.
        if (msg.t === "react") {
            if (!EMOJI.includes(msg.emoji)) return;
            const now = this.now();
            const last = this.reacts.get(ws);
            if (last !== undefined && now - last < REACT_GAP_MS) return;
            this.reacts.set(ws, now);
        }
        // A suggestion's id; an `add` may become suggestions too (guest adds off, or an older host); a session's request.
        const newId = msg.t === "suggest" || msg.t === "add" || msg.t === "request" ? randomCode(8) : undefined;
        await this.apply({ type: "msg", from, msg, newId });
    }

    async webSocketClose(ws, code, reason) {
        const from = memberOf(ws);
        try { ws.close(code, reason); } catch { /* already closed */ }
        // A socket replaced by a resume carries no member any more, so its close changes nothing.
        if (from) await this.apply({ type: "close", from });
    }

    async webSocketError(ws) {
        const from = memberOf(ws);
        if (from) await this.apply({ type: "close", from });
    }

    async alarm() {
        this.alarmAt = undefined; // the alarm that just fired is consumed, so the next apply sets it again
        await this.apply({ type: "alarm" });
    }

    /** Ties `ws` to `memberId`, closing any older socket of the same member (a resume after a drop). */
    bind(ws, memberId) {
        for (const other of this.ctx.getWebSockets()) {
            if (other !== ws && memberOf(other) === memberId) {
                other.serializeAttachment({ memberId: null, at: 0 });
                try { other.close(1000, "replaced"); } catch { /* gone */ }
            }
        }
        ws.serializeAttachment({ memberId, at: this.now() });
    }

    /** Members a session's host booted: told already (step's `out`), their sockets now unbound and closed. */
    kick(ids) {
        for (const ws of this.ctx.getWebSockets()) {
            if (!ids.includes(memberOf(ws))) continue;
            ws.serializeAttachment({ memberId: null, at: 0 });
            try { ws.close(4403, "booted"); } catch { /* gone */ }
        }
    }

    /** Closes sockets that never said hello within UNBOUND_IDLE_MS, freeing the socket cap. */
    evictIdleUnbound() {
        const now = this.now();
        for (const ws of this.ctx.getWebSockets()) {
            const a = ws.deserializeAttachment();
            if (a && a.memberId == null && now - a.at > UNBOUND_IDLE_MS) { try { ws.close(4408, "no hello"); } catch { /* gone */ } }
        }
    }

    overLimit(ws) {
        const now = this.now();
        const r = this.rates.get(ws);
        if (!r || now - r.start >= 1000) { this.rates.set(ws, { start: now, count: 1 }); return false; }
        return ++r.count > MSG_PER_SECOND;
    }

    overEditLimit(ws) {
        const now = this.now();
        const b = this.edits.get(ws) ?? { tokens: EDIT_BURST, at: now };
        b.tokens = Math.min(EDIT_BURST, b.tokens + ((now - b.at) / 1000) * EDITS_PER_SECOND);
        b.at = now;
        this.edits.set(ws, b);
        if (b.tokens < 1) return true;
        b.tokens--;
        return false;
    }

    async apply(event, beforeSend) {
        const state = (await this.ctx.storage.get("room")) ?? null;
        const r = step(state, event, this.now());
        beforeSend?.(r);
        for (const { to, msg } of r.out) this.send(to, msg);
        if (r.kick?.length) this.kick(r.kick);
        if (r.closed) {
            for (const ws of this.ctx.getWebSockets()) { try { ws.close(4000, "ended"); } catch { /* gone */ } }
            await this.ctx.storage.deleteAlarm();
            this.alarmAt = null;
            await this.ctx.storage.deleteAll();
            if (r.live) await this.directory(r.live);
            return r;
        }
        if (r.state && r.state !== state) await this.ctx.storage.put("room", r.state);
        // Write the alarm only when it moves. alarm() clears alarmAt first (alarms are one-shot), so an alarm
        // that changed nothing still re-arms, or an idle room would never close.
        // ponytail: alarmAt lives in memory; after hibernation the first apply rewrites it once.
        if (r.state && r.alarmAt !== this.alarmAt) {
            await this.ctx.storage.setAlarm(r.alarmAt);
            this.alarmAt = r.alarmAt;
        }
        // After the room is stored: other events may run while the database answers, and they read the stored room.
        if (r.live) {
            const reason = await this.directory(r.live);
            if (reason) await this.apply({ type: "stationGone", reason });
        }
        return r;
    }

    /**
     * A session's live directory write (src/stations-api.js), one at a time in the order the room made them: one landing
     * after the next, or after the session's own remove, would show the wrong song, or a session that has ended, until
     * the row is written again or goes stale. A database error is logged and swallowed: a hiccup never ends a session,
     * and the next heartbeat writes again. Resolves to why the station went off the air, or null.
     */
    directory(live) {
        const db = this.env.COMMUNITY_DB;
        const write = () => (live.upsert ? writeLive(db, live.upsert) : removeLive(db, live.remove).then(() => null))
            .catch((e) => { console.error(`live directory: ${e?.message ?? e}`); return null; });
        this.directoryTail = (this.directoryTail ?? Promise.resolve()).then(write);
        return this.directoryTail;
    }

    send(to, msg) {
        const text = JSON.stringify(msg);
        for (const ws of this.ctx.getWebSockets()) {
            const id = memberOf(ws);
            if (id && (to === "all" || id === to)) { try { ws.send(text); } catch { /* closing */ } }
        }
    }
}
