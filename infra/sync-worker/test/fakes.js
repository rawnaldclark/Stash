/**
 * Just enough of the Durable Object runtime for the tests: SQLite storage (node:sqlite behind the `sql.exec` cursor API),
 * the key-value API, transactionSync, the alarm, namespaces whose RPC results are structured-cloned like the real ones,
 * and ratelimits bindings. Plus devices, boxes and envelopes shaped like the clients' (random bytes stand in for ciphertext).
 */
import { DatabaseSync } from "node:sqlite";
import { webcrypto } from "node:crypto";
import { base64url, sha256Hex } from "../src/http.js";
import { SyncSpace } from "../src/sync-space.js";
import { PairSlot } from "../src/pair-slot.js";
import worker from "../src/index.js";

export function sqlStorage() {
    const db = new DatabaseSync(":memory:");
    let depth = 0;
    return {
        db,
        exec(query, ...bindings) {
            const rows = db.prepare(query).all(...bindings).map((r) => ({ ...r }));
            return { toArray: () => rows, one: () => rows[0], [Symbol.iterator]: () => rows[Symbol.iterator]() };
        },
        transactionSync(fn) {
            if (depth > 0) return fn();
            depth++;
            db.exec("BEGIN");
            try {
                const r = fn();
                db.exec("COMMIT");
                return r;
            } catch (e) {
                db.exec("ROLLBACK");
                throw e;
            } finally {
                depth--;
            }
        },
        tables: () => db.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").all().map((r) => r.name),
    };
}

export function fakeCtx() {
    const sql = sqlStorage();
    const kv = new Map();
    const ctx = {
        sql,
        kv,
        alarm: null,
        storage: {
            sql,
            transactionSync: (fn) => sql.transactionSync(fn),
            async get(k) { return kv.has(k) ? structuredClone(kv.get(k)) : undefined; },
            async put(k, v) { kv.set(k, structuredClone(v)); },
            async deleteAll() {
                kv.clear();
                for (const t of sql.tables()) sql.db.exec(`DROP TABLE "${t}"`);
            },
            async setAlarm(at) { ctx.alarm = at; },
            async getAlarm() { return ctx.alarm; },
            async deleteAlarm() { ctx.alarm = null; },
        },
    };
    return ctx;
}

/** A namespace: idFromName is the identity, get() makes one object per name and clones what crosses, as RPC does. */
export function namespace(make) {
    const objects = new Map();
    return {
        objects,
        ctxs: new Map(),
        idFromName: (name) => name,
        get(id) {
            if (!objects.has(id)) {
                const ctx = fakeCtx();
                this.ctxs.set(id, ctx);
                objects.set(id, make(ctx));
            }
            const o = objects.get(id);
            return new Proxy(o, {
                get(target, prop) {
                    const f = target[prop];
                    if (typeof f !== "function") return f;
                    return async (...args) => structuredClone(await f.apply(target, args.map((a) => structuredClone(a))));
                },
            });
        },
    };
}

/** A ratelimits binding with a fixed limit per key over the whole test. */
export function limiter(limit) {
    const counts = new Map();
    return {
        counts,
        async limit({ key }) {
            const n = (counts.get(key) ?? 0) + 1;
            counts.set(key, n);
            return { success: n <= limit };
        },
    };
}

export const PLAYER_KEY = "player-key-for-tests-0123456789";

/** A whole Worker: both namespaces on one clock, pairing long-polls that wait for `timers.fire()` or a change. */
export function world({ t = 1_760_000_000_000, limits = {} } = {}) {
    const clock = { t };
    const now = () => clock.t;
    const timers = [];
    const sleep = (ms, signal) => new Promise((resolve) => {
        const timer = { ms, fire() { clock.t += ms; resolve(); } };
        timers.push(timer);
        signal.addEventListener("abort", () => { timers.splice(timers.indexOf(timer), 1); resolve(); }, { once: true });
    });
    const env = { PLAYER_KEY };
    env.PAIRS = namespace((ctx) => new PairSlot(ctx, env, { now, sleep }));
    env.SPACES = namespace((ctx) => new SyncSpace(ctx, env, { now }));
    env.PAIR_RL = limiter(limits.pair ?? 1000);
    env.PAIR_OPEN_RL = limiter(limits.open ?? 1000);
    env.API_RL = limiter(limits.api ?? 100_000);
    const fire = () => { for (const tm of timers.splice(0)) tm.fire(); };
    return { clock, env, timers, fire, fetch: (req) => worker.fetch(req, env, {}) };
}

const random = (n) => webcrypto.getRandomValues(new Uint8Array(n));

/** An encrypted value of `bytes` random bytes: `{ n, c }`, plus `e` when given. */
export function box(e, bytes = 48) {
    return { ...(e === undefined ? {} : { e }), n: base64url(random(12)), c: base64url(random(bytes)) };
}

export async function makeDevice(prefix = "d") {
    const token = base64url(random(32));
    const pair = await webcrypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"]);
    const pub = base64url(new Uint8Array(await webcrypto.subtle.exportKey("raw", pair.publicKey)));
    const id = `${prefix}_${base64url(random(9))}`;
    const tokenHash = await sha256Hex(token);
    return { id, token, tokenHash, pub, labelCt: box(), auth: `Stash-Device ${id}:${token}`, caller: { deviceId: id, tokenHash } };
}

/** The record a device introduces itself with at pairing. */
export const record = (d) => ({ id: d.id, tokenHash: d.tokenHash, pub: d.pub, labelCt: d.labelCt });

export const ORIGIN = "https://sync.stashfm.app";

/** A request; `player: true` adds the player key, `device` signs it, `body` makes it JSON. */
export function req(method, path, { body, device, player = false, headers = {}, ip = "203.0.113.7" } = {}) {
    const h = { "CF-Connecting-IP": ip, ...headers };
    if (player) h["X-Stash-Player-Key"] = PLAYER_KEY;
    if (device) h.Authorization = device.auth;
    if (body !== undefined) h["content-type"] = "application/json";
    return new Request(ORIGIN + path, { method, headers: h, body: body === undefined ? undefined : typeof body === "string" ? body : JSON.stringify(body) });
}
