/**
 * Live control on a space (spec 2026-10-10 §6.7; sync-v1 §5.7 "Remote control"): `GET /v1/spaces/{sid}/ws`, one hibernating
 * WebSocket per device on its space's object. The object relays end-to-end encrypted frames between the members it holds and
 * never opens them: it reads only the clear routing fields (`t`, `to`) and checks each envelope's shape and epoch, as it does
 * for a slot write.
 *
 * - **Hello first.** A browser's WebSocket can't carry `Authorization`, so every socket starts with
 *   `{ "t": "hello", "device", "token" }` and is checked exactly like a REST call (Space.admit: the token in constant time, a
 *   browser only through the player, the 90-day idle rule, the per-device read count). A socket that hasn't said hello wakes the
 *   object at most once: its first frame must be a well-formed hello within 10 s, or it is closed (`4401`). On a space that
 *   doesn't exist, the first frame closes it `4401` (as a revoked device's hello is answered). A second socket of the same device replaces
 *   the first (`4409 replaced`, which clients don't reconnect after) and the others hear the device (re)join.
 * - **Frames.** `state` (a browser says what it plays) goes to the phones; `cmd` (a phone's remote command) goes to the one
 *   browser it names, or comes back as `{ t: "error", code: "offline" }` when it has no socket. Nothing is stored. Members hear
 *   `peer` on/off as devices come and go, so a browser speaks only while a phone listens.
 * - **Limits.** Frames over 32 KiB close the socket (1009); more than 10 a second close it (1008); at most 10 open sockets per
 *   space (one per device, the rest saying hello): a new socket on a full space takes the place of the oldest one still saying
 *   hello, so sockets that never authenticate can't lock the members out. `ping` is answered by the runtime (`pong`) without
 *   waking the object; a bound socket with neither a frame nor a ping for 90 s is closed by the next sweep (on any upgrade, and
 *   from a frame at most every 10 s).
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
    /** A frame runs the idle sweep at most this often (an upgrade always does). */
    sweepMs: 10_000,
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
        /** When a frame last ran the sweep (in memory: the first frame after a wake sweeps). */
        this.sweptAt = -Infinity;
    }

    /** The upgrade, after the Worker checked the route, the method and the caller's rate limit. */
    accept(request) {
        if ((request.headers.get("Upgrade") ?? "").toLowerCase() !== "websocket") return new Response(null, { status: 426 });
        const player = request.headers.get(VIA_PLAYER) === "1";
        // No such space: the socket is accepted as one already overdue for its hello, so its first frame closes it `4401`, as a
        // revoked device's hello is answered (no probing which ids exist), and the next upgrade's sweep closes it if it never
        // speaks. (Closing it here, before the 101 is out, reaches the client but the runtime never finishes the close.)
        const ghost = !this.space.meta();
        this.sweep();
        const open = this.open();
        if (open.length >= LIVE.maxSockets) {
            // Full: the oldest socket still saying hello makes room. Only members' sockets filling it means a 409.
            const waiting = open.filter((ws) => !ws.deserializeAttachment()?.device);
            if (!waiting.length) return new Response(null, { status: 409 });
            waiting.sort((x, y) => (x.deserializeAttachment()?.at ?? 0) - (y.deserializeAttachment()?.at ?? 0));
            this.close(waiting[0], CLOSE.idle, "no hello");
        }
        const [client, server] = this.pair();
        this.ctx.acceptWebSocket(server);
        server.serializeAttachment(ghost ? { device: null, type: null, at: 0, player, ghost: true } : { device: null, type: null, at: this.now(), player });
        return this.upgraded(client);
    }

    /**
     * The sockets still open. The runtime keeps a socket it was told to close in getWebSockets() until the client answers the
     * close; such a socket is marked `done` and counts for nothing (not bound, not in the cap, not swept again).
     */
    open() {
        return this.ctx.getWebSockets().filter((ws) => !ws.deserializeAttachment()?.done);
    }

    /** The sockets that said hello, with their attachment. */
    bound() {
        const out = [];
        for (const ws of this.open()) {
            const a = ws.deserializeAttachment();
            if (a?.device) out.push({ ws, a });
        }
        return out;
    }

    async message(ws, data) {
        const a = ws.deserializeAttachment() ?? {};
        if (a.done) return; // closed: the client's late frames change nothing
        if (!a.device) return this.unbound(ws, a, data);
        if (this.flooding(ws)) return this.close(ws, CLOSE.flood, "too many messages");
        if ((typeof data === "string" ? data.length : data.byteLength) > LIVE.maxFrame) return this.close(ws, CLOSE.tooBig, "too big");
        const t = this.now();
        if (t - this.sweptAt >= LIVE.sweepMs) this.sweep();
        if (ws.deserializeAttachment()?.done) return; // the sweep closed this one
        const msg = parseFrame(data);
        if (!msg) return;
        if (t - a.at >= LIVE.touchMs) ws.serializeAttachment({ ...a, at: t });
        // Direction: a browser says what it plays, a phone sends commands. Anything else is a newer client's frame: ignored.
        if (msg.t === "state") return a.type === "web" ? this.relay(ws, a, msg, null) : refuse(ws);
        if (msg.t === "cmd") return a.type === "phone" ? this.relay(ws, a, msg, msg.to) : refuse(ws);
    }

    /**
     * A socket that hasn't said hello wakes the object at most once: its first frame must be a well-formed hello, in time;
     * anything else (junk, a state, a second frame before the answer, a late hello) closes it as revoked.
     */
    unbound(ws, a, data) {
        if (a.ghost) return this.close(ws, CLOSE.revoked, "revoked");
        const late = this.now() - (a.at ?? 0) > LIVE.helloMs;
        const size = typeof data === "string" ? data.length : data.byteLength;
        const msg = !late && !a.hello && size <= LIVE.maxFrame ? parseFrame(data) : null;
        const hello = msg?.t === "hello" && isDeviceId(msg.device) && typeof msg.token === "string" && TOKEN.test(msg.token);
        if (!hello) return this.close(ws, CLOSE.revoked, late ? "no hello" : "revoked");
        ws.serializeAttachment({ ...a, hello: true }); // a second frame before the answer is junk
        return this.hello(ws, a, msg);
    }

    /** Checks the device like a REST call and binds the socket to it. */
    async hello(ws, a, msg) {
        const tokenHash = await tokenHashOf(msg.token);
        if (ws.deserializeAttachment()?.done) return; // closed while hashing
        const r = this.space.admitLive({ deviceId: msg.device, tokenHash }, a.player === true);
        if (r.deleteSpace) {
            await this.wipe();
            return this.close(ws, CLOSE.revoked, "revoked");
        }
        if (!r.device) {
            const code = r.status === 403 ? CLOSE.forbidden : r.status === 429 ? CLOSE.limited : CLOSE.revoked;
            return this.close(ws, code, r.body?.error?.code ?? "revoked");
        }
        const me = r.device;
        // Bound first, then the older socket of this device goes: the others never hear it leave.
        ws.serializeAttachment({ device: me.id, type: me.type, at: this.now(), player: a.player === true });
        for (const o of this.bound()) {
            if (o.ws !== ws && o.a.device === me.id) this.close(o.ws, CLOSE.replaced, "replaced");
        }
        const others = this.bound().filter((o) => o.ws !== ws);
        put(ws, JSON.stringify({ t: "ready", me: me.id, epoch: r.epoch, peers: others.map((o) => ({ device: o.a.device, type: o.a.type })) }));
        // Also after a replacement: a phone that came back starts a new session with the browser, which then sends its state.
        const note = JSON.stringify({ t: "peer", device: me.id, type: me.type, on: true });
        for (const o of others) put(o.ws, note);
    }

    /** A browser's `state` (to the phones) or a phone's `cmd` (to the browser `to`). The envelope is checked, never opened. */
    relay(ws, a, msg, to) {
        const env = cleanEnv(msg.env, LIVE.envChars);
        if (!env || (to !== null && (!isDeviceId(to) || to === a.device))) return refuse(ws);
        const m = this.space.meta();
        if (!m) return this.closeAll(CLOSE.revoked, "revoked");
        if (m.rotationDue) return put(ws, JSON.stringify({ t: "error", code: "rotation_due" }));
        if (env.e !== m.epoch) return put(ws, JSON.stringify({ t: "error", code: "epoch", epoch: m.epoch }));
        const others = this.bound().filter((o) => o.ws !== ws);
        if (to === null) {
            const text = JSON.stringify({ t: "state", from: a.device, env });
            for (const o of others) if (o.a.type === "phone") put(o.ws, text);
            return;
        }
        const target = others.find((o) => o.a.device === to);
        if (!target) return put(ws, JSON.stringify({ t: "error", code: "offline", to }));
        if (target.a.type !== "web") return refuse(ws);
        put(target.ws, JSON.stringify({ t: "cmd", from: a.device, env }));
    }

    /** The runtime says a socket closed (or failed): it is answered, and the others hear its device left (unless replaced). */
    closed(ws, code = 1000, reason = "") {
        this.close(ws, code, reason);
    }

    /**
     * Closes a socket for good: marked done (it counts for nothing while the runtime waits for the client's answer), and if it
     * was bound the others hear its device left, unless another socket of that device is open.
     */
    close(ws, code, reason) {
        const a = ws.deserializeAttachment() ?? {};
        if (!a.done) ws.serializeAttachment({ ...a, device: null, done: true });
        shut(ws, code, reason);
        if (a.device && !a.done) this.left(ws, a);
    }

    left(ws, a) {
        const rest = this.bound().filter((o) => o.ws !== ws);
        if (rest.some((o) => o.a.device === a.device)) return;
        const note = JSON.stringify({ t: "peer", device: a.device, type: a.type, on: false });
        for (const o of rest) put(o.ws, note);
    }

    /** Sockets that never said hello in time, and bound ones gone quiet (no frame, no ping) for LIVE.idleMs. */
    sweep() {
        const t = this.now();
        this.sweptAt = t;
        for (const ws of this.open()) {
            const a = ws.deserializeAttachment() ?? {};
            if (!a.device) {
                if (t - (a.at ?? 0) > LIVE.helloMs) this.close(ws, CLOSE.idle, "no hello");
                continue;
            }
            const pinged = this.ctx.getWebSocketAutoResponseTimestamp?.(ws)?.getTime?.() ?? 0;
            if (t - Math.max(a.at ?? 0, pinged) > LIVE.idleMs) this.close(ws, CLOSE.idle, "idle");
        }
    }

    /** After a removal (or the idle sweep): a socket whose device left the space is cut off at once. */
    sweepMembers() {
        for (const { ws, a } of this.bound()) {
            if (!this.space.isMember(a.device)) this.close(ws, CLOSE.revoked, "revoked");
        }
    }

    /** After a rotation: every member fetches its new key before its next frame. */
    epochChanged(epoch) {
        const text = JSON.stringify({ t: "epoch", epoch });
        for (const { ws } of this.bound()) put(ws, text);
    }

    /** Every socket closes together (no peer notes: nobody is left to hear them). */
    closeAll(code, reason) {
        for (const ws of this.open()) {
            ws.serializeAttachment({ ...(ws.deserializeAttachment() ?? {}), device: null, done: true });
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

/** A frame as an object with a string `t`, or null. */
function parseFrame(data) {
    let msg;
    try {
        msg = JSON.parse(typeof data === "string" ? data : new TextDecoder().decode(data));
    } catch {
        return null;
    }
    return msg && typeof msg === "object" && typeof msg.t === "string" ? msg : null;
}

const refuse = (ws) => put(ws, JSON.stringify({ t: "error", code: "bad_request" }));
