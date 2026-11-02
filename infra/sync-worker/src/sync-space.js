/**
 * SyncSpace: one SQLite Durable Object per sync space (`idFromName(spaceId)`), a thin shell around src/space.js
 * (as ListenRoom is around room.js). It holds no timers, promises or fetches between requests, so an idle space
 * hibernates and costs nothing (spec §6.6); retention runs on the alarm. The Worker calls it through rpc() (src/http.js).
 *
 * Live control (spec §6.7, src/live.js) uses the WebSocket Hibernation API: sockets are accepted with
 * `ctx.acceptWebSocket`, `ping` is answered by the runtime without waking the object, and an object holding only idle
 * sockets is evicted from memory like any other (it costs no duration).
 */
import { RETENTION_EVERY_MS, Space } from "./space.js";
import { rpc, serveRpc } from "./http.js";
import { CLOSE, Live } from "./live.js";

export class SyncSpace {
    /**
     * @param {object} ctx  the Durable Object state
     * @param {object} env  the Worker's bindings
     * @param {{ now?: () => number, pair?: () => [object, object], upgraded?: (client: object) => Response }} opts
     *        test seams: the clock, and how a WebSocket pair and the 101 answer are made (Node has neither).
     */
    constructor(ctx, env, opts = {}) {
        this.ctx = ctx;
        this.env = env;
        this.now = opts.now ?? (() => Date.now());
        this.space = new Space({
            sql: ctx.storage.sql,
            transaction: (fn) => ctx.storage.transactionSync(fn),
            now: this.now,
            claimPair: (pairId, caller, mode) => rpc(env.PAIRS.get(env.PAIRS.idFromName(pairId)), "claim", { caller, mode }),
            releasePair: (pairId) => rpc(env.PAIRS.get(env.PAIRS.idFromName(pairId)), "release"),
        });
        this.live = new Live({
            ctx,
            space: this.space,
            now: this.now,
            pair: opts.pair ?? (() => Object.values(new WebSocketPair())),
            upgraded: opts.upgraded ?? ((client) => new Response(null, { status: 101, webSocket: client })),
            wipe: () => this.wipe(),
        });
        // Keep-alive pings are answered at the edge without waking the object (and without billing a message).
        if (typeof WebSocketRequestResponsePair === "function") ctx.setWebSocketAutoResponse?.(new WebSocketRequestResponsePair("ping", "pong"));
    }

    /** The Worker's calls (src/http.js rpc), and the live socket's upgrade. */
    fetch(request) {
        if (new URL(request.url).pathname === "/ws") return this.live.accept(request);
        return serveRpc(request, this, ["create", "call"]);
    }

    /** A new space from a claimed pairing; arms the retention alarm. */
    async create(args) {
        const r = this.space.create(args);
        if (r.status === 201) await this.ctx.storage.setAlarm(this.now() + RETENTION_EVERY_MS);
        return r;
    }

    /** Every member request (see Space.call). */
    async call(req) {
        const r = await this.space.call(req);
        if (r.deleteSpace) {
            await this.wipe();
            const { deleteSpace: _, ...rest } = r;
            return rest;
        }
        // A removed device's live socket goes with its token; a new key is announced to the sockets still open.
        if (req.op === "removeDevice" && r.status === 204) this.live.sweepMembers();
        if (req.op === "rotate" && r.status === 200) this.live.epochChanged(r.body.epoch);
        // Self-heal: a space whose alarm was lost gets it back when a device opens it.
        if (req.op === "get" && r.status === 200 && (await this.ctx.storage.getAlarm()) === null) {
            await this.ctx.storage.setAlarm(this.now() + RETENTION_EVERY_MS);
        }
        return r;
    }

    async alarm() {
        if (this.space.retain()) return this.wipe();
        this.live.sweepMembers(); // a device the idle rule removed
        if (this.space.meta()) await this.ctx.storage.setAlarm(this.now() + RETENTION_EVERY_MS);
    }

    /** Unlink everything: every table and the alarm go, and every live socket is closed. */
    async wipe() {
        this.live.closeAll(CLOSE.revoked, "revoked");
        await this.ctx.storage.deleteAlarm();
        await this.ctx.storage.deleteAll();
    }

    // ---------------------------------------------------------------- the Hibernation API's handlers

    webSocketMessage(ws, data) {
        return this.live.message(ws, data);
    }

    webSocketClose(ws, code, reason) {
        this.live.closed(ws, code === 1005 || code === 1006 ? 1000 : code, reason);
    }

    webSocketError(ws) {
        this.live.closed(ws, 1011, "error");
    }
}
