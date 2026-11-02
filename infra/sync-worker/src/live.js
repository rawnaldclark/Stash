/**
 * Live control on a space (spec 2026-10-10 §6.7; sync-v1 §5.7 "Remote control"): `GET /v1/spaces/{sid}/ws`, one hibernating
 * WebSocket per device on its space's object. The object relays end-to-end encrypted frames between the members it holds and
 * never opens them: it reads only the clear routing fields (`t`, `to`) and checks each envelope's shape and epoch, as it does
 * for a slot write.
 *
 * - **Hello first.** A browser's WebSocket can't carry `Authorization`, so every socket starts with
 *   `{ "t": "hello", "device", "token" }` and is checked exactly like a REST call (Space.admit: the token in constant time, a
 *   browser only through the player, the 90-day idle rule, the per-device read count). A socket that says nothing for 10 s is
 *   closed; a second socket of the same device replaces the first (`4409 replaced`, which clients don't reconnect after).
 * - **Frames.** `state` (a device says what it plays) goes to every other member; `cmd` (a remote command) goes to the one
 *   device it names, or comes back as `{ t: "error", code: "offline" }` when that device has no socket. Nothing is stored.
 *   Members hear `peer` on/off as devices come and go, so a browser speaks only while a phone listens.
 * - **Limits.** Frames over 32 KiB close the socket (1009); more than 10 a second close it (1008); at most 10 sockets per space
 *   (one per device, as many again saying hello). `ping` is answered by the runtime (`pong`) without waking the object; a bound
 *   socket with neither a frame nor a ping for 90 s is closed when the object next runs.
 * - **Membership.** Removing a device closes its socket at once (`4401 revoked`), unlinking everything closes all of them, and a
 *   key rotation tells every socket the new epoch.
 *
 * Pure of the runtime: the object hands in its context (`acceptWebSocket`, `getWebSockets`, the auto-response timestamp), the
 * space and a clock, and how to make a socket pair and the 101 answer, so `node --test` drives it with fakes.
 */
import { tokenHashOf } from "./http.js";
import { cleanEnv, isDeviceId, LIMITS } from "./validate.js";

export const LIVE = {
    /** One socket per device (1 phone + 4 browsers), and as many again still saying hello. */
    maxSockets: 10,
    /** A socket that hasn't said hello this long is closed. */
    helloMs: 10_000,
    /** A bound socket with no frame and no ping for this long is closed (clients ping every 30 s). */
    idleMs: 90_000,
    /** Frames per socket per second (a state every few seconds and a slider's commands fit easily). */
    perSecond: 10,
    /** Largest frame read; anything bigger closes the socket before it is parsed. */
    maxFrame: 32 * 1024,
    /** An envelope's ciphertext, as a `now` slot's. */
    envChars: LIMITS.nowChars,
    /** The socket's `at` is rewritten at most this often (it marks the socket as alive). */
    touchMs: 5_000,
};

/** Close codes (4xxx: the application's). Clients reconnect after a drop, never after `replaced`, `revoked` or `forbidden`. */
export const CLOSE = {
    revoked: 4401,
    forbidden: 4403,
    idle: 4408,
    replaced: 4409,
    limited: 4429,
    flood: 1008,
    tooBig: 1009,
};

/** The header the Worker sets (never the client: it is overwritten) to say the socket came through the player. */
export const VIA_PLAYER = "X-Stash-Live-Player";

const shut = (ws, code, reason) => {
    try {
        ws.close(code, reason);
    } catch {
        /* already closing */
    }
};
const put = (ws, text) => {
    try {
        ws.send(text);
    } catch {
        /* closing */
    }
};
const TOKEN = /^[A-Za-z0-9_-]{43}$/;

export class Live {
    /**
     * @param {{ ctx: { acceptWebSocket(ws: object): void, getWebSockets(): object[], getWebSocketAutoResponseTimestamp?(ws: object): Date | null },
     *           space: import("./space.js").Space, now: () => number, pair: () => [object, object],
     *           upgraded: (client: object) => Response, wipe: () => Promise<void> }} deps
     */
    constructor({ ctx, space, now, pair, upgraded, wipe }) {
        this.ctx = ctx;
        this.space = space;
        this.now = now;
        this.pair = pair;
        this.upgraded = upgraded;
        this.wipe = wipe;
        /** Frames per socket in the current second. In memory: it starts over when the object hibernates, which is fine for a brake. */
        this.rates = new WeakMap();
    }

    /** The upgrade, after the Worker checked the route, the method and the caller's rate limit. */
    accept(request) {
        if ((request.headers.get("Upgrade") ?? "").toLowerCase() !== "websocket") return new Response(null, { status: 426 });
        this.sweep();
        if (this.ctx.getWebSockets().length >= LIVE.maxSockets) return new Response(null, { status: 409 });
        const [client, server] = this.pair();
        this.ctx.acceptWebSocket(server);
        server.serializeAttachment({ device: null, type: null, at: this.now(), player: request.headers.get(VIA_PLAYER) === "1" });
        return this.upgraded(client);
    }

    /** The sockets that said hello, with their attachment. */
    bound() {
        const out = [];
        for (const ws of this.ctx.getWebSockets()) {
            const a = ws.deserializeAttachment();
            if (a?.device) out.push({ ws, a });
        }
        return out;
    }

    async message(ws, data) {
        if (this.flooding(ws)) return shut(ws, CLOSE.flood, "too many messages");
        if ((typeof data === "string" ? data.length : data.byteLength) > LIVE.maxFrame) return shut(ws, CLOSE.tooBig, "too big");
        let msg;
        try {
            msg = JSON.parse(typeof data === "string" ? data : new TextDecoder().decode(data));
        } catch {
            return;
        }
        if (!msg || typeof msg !== "object" || typeof msg.t !== "string") return;
        const a = ws.deserializeAttachment() ?? {};
        if (!a.device) return msg.t === "hello" ? this.hello(ws, a, msg) : undefined; // the first frame must be hello
        const t = this.now();
        if (t - a.at >= LIVE.touchMs) ws.serializeAttachment({ ...a, at: t });
        if (msg.t === "state") return this.relay(ws, a, msg, null);
        if (msg.t === "cmd") return this.relay(ws, a, msg, msg.to);
        // Anything else is a newer client's frame: ignored.
    }

    /** Checks the device like a REST call and binds the socket to it. */
    async hello(ws, a, msg) {
        const device = msg.device;
        const tokenHash = isDeviceId(device) && typeof msg.token === "string" && TOKEN.test(msg.token) ? await tokenHashOf(msg.token) : null;
        if (!tokenHash) return shut(ws, CLOSE.revoked, "revoked");
        const r = this.space.admitLive({ deviceId: device, tokenHash }, a.player === true);
        if (r.deleteSpace) {
            await this.wipe();
            return shut(ws, CLOSE.revoked, "revoked");
        }
        if (!r.device) {
            const code = r.status === 403 ? CLOSE.forbidden : r.status === 429 ? CLOSE.limited : CLOSE.revoked;
            return shut(ws, code, r.body?.error?.code ?? "revoked");
        }
        const me = r.device;
        let replaced = false;
        for (const o of this.bound()) {
            if (o.ws === ws || o.a.device !== me.id) continue;
            o.ws.serializeAttachment({ ...o.a, device: null }); // its late close changes nothing
            shut(o.ws, CLOSE.replaced, "replaced");
            replaced = true;
        }
        ws.serializeAttachment({ device: me.id, type: me.type, at: this.now(), player: a.player === true });
        const others = this.bound().filter((o) => o.ws !== ws);
        put(ws, JSON.stringify({ t: "ready", me: me.id, epoch: r.epoch, peers: others.map((o) => ({ device: o.a.device, type: o.a.type })) }));
        if (!replaced) {
            const note = JSON.stringify({ t: "peer", device: me.id, type: me.type, on: true });
            for (const o of others) put(o.ws, note);
        }
    }

    /** A `state` (to every other member) or a `cmd` (to the device `to`). The envelope is checked, never opened. */
    relay(ws, a, msg, to) {
        const env = cleanEnv(msg.env, LIVE.envChars);
        if (!env || (to !== null && (!isDeviceId(to) || to === a.device))) return put(ws, JSON.stringify({ t: "error", code: "bad_request" }));
        const m = this.space.meta();
        if (!m) return this.closeAll(CLOSE.revoked, "revoked");
        if (m.rotationDue) return put(ws, JSON.stringify({ t: "error", code: "rotation_due" }));
        if (env.e !== m.epoch) return put(ws, JSON.stringify({ t: "error", code: "epoch", epoch: m.epoch }));
        const others = this.bound().filter((o) => o.ws !== ws);
        if (to === null) {
            const text = JSON.stringify({ t: "state", from: a.device, env });
            for (const o of others) put(o.ws, text);
            return;
        }
        const target = others.find((o) => o.a.device === to);
        if (!target) return put(ws, JSON.stringify({ t: "error", code: "offline", to }));
        put(target.ws, JSON.stringify({ t: "cmd", from: a.device, env }));
    }

    /** The runtime says a socket closed (or failed): the others hear that its device left, unless another socket took it over. */
    closed(ws, code = 1000, reason = "") {
        const a = ws.deserializeAttachment();
        shut(ws, code, reason);
        if (a?.device) {
            ws.serializeAttachment({ ...a, device: null });
            this.left(ws, a);
        }
    }

    left(ws, a) {
        const rest = this.bound().filter((o) => o.ws !== ws);
        if (rest.some((o) => o.a.device === a.device)) return;
        const note = JSON.stringify({ t: "peer", device: a.device, type: a.type, on: false });
        for (const o of rest) put(o.ws, note);
    }

    /** Closes one bound socket and tells the others. */
    drop(ws, a, code, reason) {
        ws.serializeAttachment({ ...a, device: null });
        shut(ws, code, reason);
        this.left(ws, a);
    }

    /** Sockets that never said hello in time, and bound ones gone quiet (no frame, no ping) for LIVE.idleMs. */
    sweep() {
        const t = this.now();
        for (const ws of this.ctx.getWebSockets()) {
            const a = ws.deserializeAttachment() ?? {};
            if (!a.device) {
                if (t - (a.at ?? 0) > LIVE.helloMs) shut(ws, CLOSE.idle, "no hello");
                continue;
            }
            const pinged = this.ctx.getWebSocketAutoResponseTimestamp?.(ws)?.getTime?.() ?? 0;
            if (t - Math.max(a.at ?? 0, pinged) > LIVE.idleMs) this.drop(ws, a, CLOSE.idle, "idle");
        }
    }

    /** After a removal (or the idle sweep): a socket whose device left the space is cut off at once. */
    sweepMembers() {
        for (const { ws, a } of this.bound()) {
            if (!this.space.isMember(a.device)) this.drop(ws, a, CLOSE.revoked, "revoked");
        }
    }

    /** After a rotation: every member fetches its new key before its next frame. */
    epochChanged(epoch) {
        const text = JSON.stringify({ t: "epoch", epoch });
        for (const { ws } of this.bound()) put(ws, text);
    }

    closeAll(code, reason) {
        for (const ws of this.ctx.getWebSockets()) {
            const a = ws.deserializeAttachment();
            if (a) ws.serializeAttachment({ ...a, device: null });
            shut(ws, code, reason);
        }
    }

    flooding(ws) {
        const t = this.now();
        const r = this.rates.get(ws);
        if (!r || t - r.start >= 1000) {
            this.rates.set(ws, { start: t, count: 1 });
            return false;
        }
        return ++r.count > LIVE.perSecond;
    }
}
