/**
 * ListenRoom: one Durable Object per Listen Together room (spec 2026-09-24 §2). A thin shell around the
 * pure src/room.js: it keeps the room in storage (hibernation drops memory), binds each socket to its
 * member with serializeAttachment, sends what step() says to send, and arms the single alarm.
 */
import { step, preview, MAX_MEMBERS } from "./room.js";
import { sameHex, sha256Hex } from "./store.js";

/** 32 symbols with no 0/O or 1/I, so `byte & 31` is unbiased and codes read aloud cleanly. */
const ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
export const MSG_PER_SECOND = 20;
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
    }

    /** Internal routes, called by the Worker through the ROOMS binding. */
    async fetch(request) {
        const url = new URL(request.url);
        if (url.pathname === "/init" && request.method === "POST") {
            const { code, hostName, keyHash } = await request.json();
            const r = await this.apply({ type: "create", code, hostName, keyHash });
            return new Response(null, { status: r.reject ? 409 : 201 });
        }
        const state = (await this.ctx.storage.get("room")) ?? null;
        if (url.pathname === "/preview") return state ? Response.json(preview(state)) : new Response(null, { status: 404 });
        if (url.pathname === "/ws") {
            if (!state) return new Response(null, { status: 404 });
            if (request.headers.get("Upgrade") !== "websocket") return new Response(null, { status: 426 });
            // `r=1` = "I have a resume token": a returning member may rejoin a full room; hello checks the token.
            if (state.members.length >= MAX_MEMBERS && url.searchParams.get("r") !== "1") return new Response(null, { status: 409 });
            // Sockets that never send hello hold no member slot; this cap stops `?r=1` ones piling up.
            if (this.ctx.getWebSockets().length >= MAX_MEMBERS * 2) {
                this.evictIdleUnbound();
                if (this.ctx.getWebSockets().length >= MAX_MEMBERS * 2) return new Response(null, { status: 409 });
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
            const r = await this.apply(
                { type: "hello", msg, hostKeyOk, newId: randomCode(8), newToken: base64url(crypto.getRandomValues(new Uint8Array(16))) },
                (r) => { if (r.bind) this.bind(ws, r.bind); },
            );
            if (r.reject) ws.close(r.reject.code, r.reject.reason);
            return;
        }
        await this.apply({ type: "msg", from, msg, newId: msg.t === "suggest" ? randomCode(8) : undefined });
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

    async apply(event, beforeSend) {
        const state = (await this.ctx.storage.get("room")) ?? null;
        const r = step(state, event, this.now());
        beforeSend?.(r);
        for (const { to, msg } of r.out) this.send(to, msg);
        if (r.closed) {
            for (const ws of this.ctx.getWebSockets()) { try { ws.close(4000, "ended"); } catch { /* gone */ } }
            await this.ctx.storage.deleteAlarm();
            this.alarmAt = null;
            await this.ctx.storage.deleteAll();
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
        return r;
    }

    send(to, msg) {
        const text = JSON.stringify(msg);
        for (const ws of this.ctx.getWebSockets()) {
            const id = memberOf(ws);
            if (id && (to === "all" || id === to)) { try { ws.send(text); } catch { /* closing */ } }
        }
    }
}
