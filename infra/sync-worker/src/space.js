/**
 * One sync space (spec 2026-10-10 §6): 1 phone + up to 4 browsers, their key envelopes, the mirror log and snapshot,
 * the handoff and config slots, and one-off sends. Everything stored is ciphertext; the server sees sizes, times and
 * random device ids only.
 *
 * Pure logic over a SQLite handle (`sql.exec(query, ...bindings)` with `.toArray()`, the Durable Object API) and an
 * injectable clock, so `node --test` runs it against node:sqlite. src/sync-space.js is the thin Durable Object around it.
 * Every operation returns `{ status, body?, headers?, deleteSpace? }`; the wrapper deletes all storage on `deleteSpace`.
 */
import { err, ok, sameText } from "./http.js";
import { cleanBox, cleanEnv, isPairId, LIMITS } from "./validate.js";

/** A device not seen for this long is removed (spec §6.5); README and privacy text quote it. */
export const DEVICE_IDLE_DAYS = 90;
/** `now` and `queue` slots, and sends, are dropped this long after they were written. */
export const SLOT_KEEP_DAYS = 7;
export const SEND_KEEP_DAYS = 7;
/** An unfinished snapshot upload is dropped after this. */
export const STAGING_KEEP_MS = 24 * 3_600_000;
/** `lastSeenAt` is written at most this often per device. */
export const SEEN_WRITE_MS = 3_600_000;
/** The retention alarm runs this often while the space exists. */
export const RETENTION_EVERY_MS = 24 * 3_600_000;

const DAY_MS = 86_400_000;
const IDLE_MS = DEVICE_IDLE_DAYS * DAY_MS;
const SLOT_MS = SLOT_KEEP_DAYS * DAY_MS;
const SEND_MS = SEND_KEEP_DAYS * DAY_MS;

const SCHEMA = [
    `CREATE TABLE IF NOT EXISTS meta (id INTEGER PRIMARY KEY CHECK (id = 1), spaceId TEXT NOT NULL, createdAt INTEGER NOT NULL,
        epoch INTEGER NOT NULL, rotationDue INTEGER NOT NULL, bytesUsed INTEGER NOT NULL, writesToday INTEGER NOT NULL,
        day INTEGER NOT NULL, head INTEGER NOT NULL, snapUpto INTEGER NOT NULL)`,
    `CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, type TEXT NOT NULL, tokenHash TEXT NOT NULL, pub TEXT NOT NULL,
        labelCt TEXT NOT NULL, addedAt INTEGER NOT NULL, lastSeenAt INTEGER NOT NULL)`,
    `CREATE TABLE IF NOT EXISTS envelopes (deviceId TEXT NOT NULL, epoch INTEGER NOT NULL, ct TEXT NOT NULL, PRIMARY KEY (deviceId, epoch))`,
    `CREATE TABLE IF NOT EXISTS log (seq INTEGER PRIMARY KEY, deviceId TEXT NOT NULL, epoch INTEGER NOT NULL, serverAt INTEGER NOT NULL, body TEXT NOT NULL)`,
    `CREATE TABLE IF NOT EXISTS snapshot (staged INTEGER NOT NULL, part INTEGER NOT NULL, count INTEGER NOT NULL, uptoSeq INTEGER NOT NULL,
        epoch INTEGER NOT NULL, serverAt INTEGER NOT NULL, body TEXT NOT NULL, PRIMARY KEY (staged, part))`,
    `CREATE TABLE IF NOT EXISTS slots (name TEXT PRIMARY KEY, deviceId TEXT NOT NULL, epoch INTEGER NOT NULL, serverAt INTEGER NOT NULL, body TEXT NOT NULL)`,
    `CREATE TABLE IF NOT EXISTS inbox (sendId TEXT NOT NULL, toDevice TEXT NOT NULL, fromDevice TEXT NOT NULL, part INTEGER NOT NULL,
        count INTEGER NOT NULL, epoch INTEGER NOT NULL, serverAt INTEGER NOT NULL, body TEXT NOT NULL, PRIMARY KEY (sendId, part))`,
];

/** Operations that change the space; they count against the daily write cap. Everything else is a read. */
const WRITES = new Set(["label", "removeDevice", "deleteSpace", "rotate", "logAppend", "snapshotPut", "configPut", "nowPut", "queuePut", "inboxPut", "inboxDelete", "join"]);

const gone = () => err(404, "gone", "This link no longer exists");
const revoked = () => err(401, "revoked", "This device is no longer linked");
const epochErr = () => err(409, "epoch", "The key changed: fetch your key and try again");
const notFound = (what = "Nothing here") => err(404, "not_found", what);
const bad = (what) => err(400, "bad_request", what);
const spaceFull = () => err(413, "space_full", "This link holds too much: compact and try again");
const parse = (text) => JSON.parse(text);

export class Space {
    /**
     * @param {{ sql: { exec(q: string, ...b: unknown[]): { toArray(): object[] } }, transaction?: (fn: () => unknown) => unknown,
     *           now?: () => number, claimPair?: (pairId: string, caller: object, mode: string) => Promise<object> }} deps
     */
    constructor({ sql, transaction, now, claimPair }) {
        this.sql = sql;
        this.tx = transaction ?? ((fn) => fn());
        this.now = now ?? (() => Date.now());
        this.claimPair = claimPair;
        /** Reads per day are counted in memory: a flood keeps the object awake, which is when the count matters. */
        this.reads = { day: -1, n: 0 };
    }

    rows(q, ...b) {
        return this.sql.exec(q, ...b).toArray();
    }

    one(q, ...b) {
        return this.rows(q, ...b)[0] ?? null;
    }

    /** The meta row, or null when this object holds no space (never created, or deleted). Creates nothing. */
    meta() {
        try {
            return this.one("SELECT * FROM meta WHERE id = 1");
        } catch {
            return null; // no such table
        }
    }

    // ---------------------------------------------------------------- creation

    /** A new space from a claimed pairing: the phone and the browser, epoch 1. */
    create({ spaceId, phone, browser }) {
        const now = this.now();
        return this.tx(() => {
            if (this.meta()) return err(409, "exists", "This link id is taken");
            for (const q of SCHEMA) this.sql.exec(q);
            this.sql.exec("INSERT INTO meta VALUES (1, ?, ?, 1, 0, 0, 1, ?, 0, 0)", spaceId, now, Math.floor(now / DAY_MS));
            for (const d of [phone, browser]) this.insertDevice(d, now);
            return { status: 201, body: { spaceId, epoch: 1 } };
        });
    }

    insertDevice(d, now) {
        this.sql.exec("INSERT INTO devices VALUES (?, ?, ?, ?, ?, ?, ?)", d.id, d.type, d.tokenHash, d.pub, JSON.stringify(d.labelCt), now, now);
    }

    // ---------------------------------------------------------------- the front door

    /**
     * One authenticated request: `{ op, caller: { deviceId, tokenHash }, params, body, ifMatch }`.
     * Checks the space, the device (constant-time), its idleness and the daily caps, then runs the operation.
     */
    async call({ op, caller, params = {}, body, ifMatch }) {
        const now = this.now();
        const pre = this.tx(() => this.admit(op, caller, now));
        if (pre.status) return pre;
        const me = pre.device;
        if (op === "join") return this.join(me, body);
        return this.tx(() => {
            const m = this.meta();
            if (!m) return gone();
            switch (op) {
                case "get": return this.info(m, me, now);
                case "label": return this.setLabel(me, body);
                case "removeDevice": return this.removeDeviceOp(m, me, params.did, now);
                case "deleteSpace": return { status: 204, body: null, deleteSpace: true };
                case "key": return this.key(me, params.epoch);
                case "rotate": return this.rotate(m, me, body, now);
                case "logAfter": return this.logAfter(m, params.seq);
                case "logAppend": return this.logAppend(m, me, body, now);
                case "snapshotPut": return this.snapshotPut(m, params, body, now);
                case "snapshotGet": return this.snapshotGet(params.part);
                case "configGet": return this.slotGet("config", now, false);
                case "configPut": return this.configPut(m, me, body, ifMatch, now);
                case "nowGet": return this.nowGet(now);
                case "nowPut": return this.slotPut(m, me, `now:${me.id}`, body, LIMITS.nowChars, now);
                case "queuePut": return this.slotPut(m, me, `queue:${me.id}`, body, LIMITS.blobChars, now);
                case "queueGet": return this.slotGet(`queue:${params.did}`, now, true);
                case "inboxPut": return this.inboxPut(m, me, params, body, now);
                case "inboxList": return this.inboxList(me, now);
                case "inboxGet": return this.inboxGet(me, params, now);
                case "inboxDelete": return this.inboxDelete(me, params.sendId);
                default: return notFound("No such route");
            }
        });
    }

    /** `{ device }` when the caller may go on, else a ready error. */
    admit(op, caller, now) {
        const m = this.meta();
        if (!m) return gone();
        const d = caller && this.one("SELECT * FROM devices WHERE id = ?", caller.deviceId);
        if (!d || !sameText(caller.tokenHash, d.tokenHash)) return revoked();
        if (now - d.lastSeenAt > IDLE_MS) {
            // Retention may not have run yet: an idle device is cut off exactly at 90 days (tombstones are only kept that long).
            return this.removeDevice(d.id) ? { ...revoked(), deleteSpace: true } : revoked();
        }
        const day = Math.floor(now / DAY_MS);
        const retryAfter = { "Retry-After": String(Math.ceil(((day + 1) * DAY_MS - now) / 1000)) };
        if (WRITES.has(op)) {
            const n = m.day === day ? m.writesToday : 0;
            if (n >= LIMITS.writesPerDay) return err(429, "daily_limit", "This link made too many changes today", retryAfter);
            this.sql.exec("UPDATE meta SET writesToday = ?, day = ? WHERE id = 1", n + 1, day);
        } else {
            if (this.reads.day !== day) this.reads = { day, n: 0 };
            if (++this.reads.n > LIMITS.readsPerDay) return err(429, "daily_limit", "This link made too many requests today", retryAfter);
        }
        if (now - d.lastSeenAt >= SEEN_WRITE_MS) {
            this.sql.exec("UPDATE devices SET lastSeenAt = ? WHERE id = ?", now, d.id);
            d.lastSeenAt = now;
        }
        return { device: d };
    }

    // ---------------------------------------------------------------- devices

    info(m, me, now) {
        const devices = this.rows("SELECT id, type, pub, labelCt, addedAt, lastSeenAt FROM devices ORDER BY addedAt, id")
            .map((d) => ({ id: d.id, type: d.type, pub: d.pub, labelCt: parse(d.labelCt), addedAt: d.addedAt, lastSeenAt: d.lastSeenAt }));
        const s = this.one("SELECT uptoSeq, count, epoch FROM snapshot WHERE staged = 0 LIMIT 1");
        return ok({
            spaceId: m.spaceId,
            me: me.id,
            epoch: m.epoch,
            rotationDue: m.rotationDue === 1,
            head: m.head,
            snapshot: s ? { uptoSeq: s.uptoSeq, parts: s.count, epoch: s.epoch } : null,
            devices,
            serverTime: now,
        });
    }

    setLabel(me, body) {
        const labelCt = cleanBox(body?.labelCt, LIMITS.labelChars);
        if (!labelCt) return bad("Not a label");
        this.sql.exec("UPDATE devices SET labelCt = ? WHERE id = ?", JSON.stringify(labelCt), me.id);
        return { status: 204, body: null };
    }

    removeDeviceOp(m, me, did, now) {
        const id = did === "me" ? me.id : did;
        if (!this.one("SELECT id FROM devices WHERE id = ?", id)) return notFound("No such device");
        const last = this.removeDevice(id);
        return { status: 204, body: null, ...(last ? { deleteSpace: true } : {}) };
    }

    /**
     * Cuts a device off at once: its row (so its token stops working), its key envelopes, its slots and its sends go.
     * The others are told to rotate the key (`rotationDue`). True when no device is left (the caller deletes the space).
     */
    removeDevice(id) {
        this.sql.exec("DELETE FROM devices WHERE id = ?", id);
        this.sql.exec("DELETE FROM envelopes WHERE deviceId = ?", id);
        this.sql.exec("DELETE FROM slots WHERE name = ? OR name = ?", `now:${id}`, `queue:${id}`);
        this.sql.exec("DELETE FROM inbox WHERE toDevice = ? OR fromDevice = ?", id, id);
        const left = this.one("SELECT COUNT(*) AS n FROM devices").n;
        if (left === 0) return true;
        this.sql.exec("UPDATE meta SET rotationDue = 1 WHERE id = 1");
        this.recount();
        return false;
    }

    /**
     * Adds the other device of a completed pairing (§5.1 step 5). The caller is already a member; the slot is burned by
     * the claim. The caps are checked before the claim (so a full space doesn't waste the code) and again after it.
     */
    async join(me, body) {
        const pairId = body?.pairId;
        if (!isPairId(pairId)) return bad("Not a pairing");
        const capped = this.tx(() => this.capFor(me.type === "phone" ? "web" : "phone"));
        if (capped) return capped;
        if (!this.claimPair) return err(503, "unavailable", "Pairing is not available");
        const r = await this.claimPair(pairId, { deviceId: me.id, tokenHash: me.tokenHash }, "join");
        if (r.status !== 200) return r;
        const add = r.body.add;
        return this.tx(() => {
            if (!this.meta()) return gone();
            if (!this.one("SELECT id FROM devices WHERE id = ?", me.id)) return revoked();
            if (this.one("SELECT id FROM devices WHERE id = ?", add.id)) return err(409, "member", "That device is already linked");
            const full = this.capFor(add.type);
            if (full) return full;
            this.insertDevice(add, this.now());
            return { status: 201, body: { device: add.id, type: add.type } };
        });
    }

    capFor(type) {
        const n = this.one("SELECT COUNT(*) AS n FROM devices WHERE type = ?", type).n;
        if (type === "phone" && n >= LIMITS.phones) return err(409, "full", "This link already has a phone. Remove it first.");
        if (type === "web" && n >= LIMITS.browsers) return err(409, "full", "You've linked 4 browsers. Remove one first.");
        return null;
    }

    // ---------------------------------------------------------------- keys

    key(me, epoch) {
        const row = this.one("SELECT epoch, ct FROM envelopes WHERE deviceId = ? AND epoch = ?", me.id, epoch);
        return row ? ok({ epoch: row.epoch, ct: parse(row.ct) }) : err(404, "no_key", "No key for this device");
    }

    /**
     * Key rotation (§5.2): `{ epoch: current + 1, envelopes: { deviceId: box } for every device, labels?, config?, snapshot? }`.
     * The old envelopes, the `now`/`queue` slots and the sends go at once; the config comes re-encrypted in the same call.
     * The log and snapshot under the old key stay until a snapshot under the new key is complete: inline here when it fits
     * in one request (`snapshot: { uptoSeq: head, parts: [env…] }`), else through the usual snapshot upload right after.
     * Remaining devices still hold the old key, so they can read what is left meanwhile; a removed device can't (no token).
     */
    rotate(m, me, body, now) {
        const epoch = body?.epoch;
        if (!Number.isSafeInteger(epoch)) return bad("No epoch");
        if (epoch !== m.epoch + 1) return epochErr();
        const ids = this.rows("SELECT id FROM devices").map((d) => d.id);
        const env = body.envelopes;
        if (!env || typeof env !== "object" || Array.isArray(env)) return bad("No envelopes");
        if (Object.keys(env).length !== ids.length || !ids.every((id) => Object.hasOwn(env, id))) return bad("One envelope per device, no more");
        const envelopes = ids.map((id) => [id, cleanBox(env[id], LIMITS.keyChars, { pub: true })]);
        if (envelopes.some(([, b]) => !b || b.e !== epoch)) return bad("Not a key envelope for the new epoch");
        let labels = [];
        if (body.labels !== undefined) {
            if (!body.labels || typeof body.labels !== "object" || Array.isArray(body.labels)) return bad("Not labels");
            labels = Object.entries(body.labels).map(([id, b]) => [id, ids.includes(id) ? cleanBox(b, LIMITS.labelChars) : null]);
            if (labels.some(([, b]) => !b)) return bad("Not a label");
        }
        const oldConfig = this.one("SELECT serverAt FROM slots WHERE name = 'config'");
        let config = null;
        if (body.config !== undefined) {
            config = cleanEnv(body.config, LIMITS.configChars);
            if (!config || config.e !== epoch) return bad("Not a config under the new key");
        } else if (oldConfig) return bad("The config must be re-encrypted with the new key");
        let snapshot = null;
        if (body.snapshot !== undefined) {
            const s = body.snapshot;
            const parts = Array.isArray(s?.parts) ? s.parts.map((p) => cleanEnv(p, LIMITS.blobChars)) : [];
            if (s?.uptoSeq !== m.head) return err(409, "stale", "The snapshot must cover the whole log");
            if (parts.length < 1 || parts.length > LIMITS.parts || parts.some((p) => !p || p.e !== epoch)) return bad("Not a snapshot under the new key");
            snapshot = { uptoSeq: s.uptoSeq, parts };
        }

        this.sql.exec("UPDATE meta SET epoch = ?, rotationDue = 0 WHERE id = 1", epoch);
        this.sql.exec("DELETE FROM envelopes");
        for (const [id, b] of envelopes) this.sql.exec("INSERT INTO envelopes VALUES (?, ?, ?)", id, epoch, JSON.stringify(b));
        for (const [id, b] of labels) this.sql.exec("UPDATE devices SET labelCt = ? WHERE id = ?", JSON.stringify(b), id);
        this.sql.exec("DELETE FROM slots WHERE name <> 'config'");
        if (config) {
            const at = Math.max(now, (oldConfig?.serverAt ?? 0) + 1);
            this.sql.exec("INSERT OR REPLACE INTO slots VALUES ('config', ?, ?, ?, ?)", me.id, epoch, at, JSON.stringify(config));
        }
        this.sql.exec("DELETE FROM inbox");
        this.sql.exec("DELETE FROM snapshot WHERE staged = 1");
        if (snapshot) {
            this.sql.exec("DELETE FROM snapshot");
            snapshot.parts.forEach((p, i) =>
                this.sql.exec("INSERT INTO snapshot VALUES (0, ?, ?, ?, ?, ?, ?)", i, snapshot.parts.length, snapshot.uptoSeq, epoch, now, JSON.stringify(p)));
            this.sql.exec("DELETE FROM log WHERE seq <= ?", snapshot.uptoSeq);
            this.sql.exec("UPDATE meta SET snapUpto = ? WHERE id = 1", snapshot.uptoSeq);
        }
        this.recount();
        return ok({ epoch });
    }

    // ---------------------------------------------------------------- log

    logAfter(m, seq) {
        if (!Number.isSafeInteger(seq) || seq < 0) return bad("Not a position");
        if (seq < m.snapUpto) return err(409, "snapshot", "Older batches were compacted: read the snapshot first");
        // Sizes first, so a page never loads more than logPageBytes of bodies into memory.
        const sizes = this.rows("SELECT seq, length(body) AS n FROM log WHERE seq > ? ORDER BY seq LIMIT ?", seq, LIMITS.logPage);
        let last = seq;
        let total = 0;
        for (const r of sizes) {
            if (total > 0 && total + r.n > LIMITS.logPageBytes) break;
            total += r.n;
            last = r.seq;
        }
        const entries = last === seq ? [] : this.rows("SELECT seq, deviceId, serverAt, body FROM log WHERE seq > ? AND seq <= ? ORDER BY seq", seq, last)
            .map((r) => ({ seq: r.seq, device: r.deviceId, serverAt: r.serverAt, env: parse(r.body) }));
        return ok({ entries, head: m.head, more: last < m.head });
    }

    logAppend(m, me, body, now) {
        const env = cleanEnv(body?.env, LIMITS.blobChars);
        if (!env) return bad("Not an envelope");
        if (env.e !== m.epoch) return epochErr();
        if (m.head - m.snapUpto >= LIMITS.logBatches) return err(409, "compact", "The log is full: compact it first");
        const text = JSON.stringify(env);
        if (m.bytesUsed + text.length > LIMITS.spaceBytes) return spaceFull();
        const seq = m.head + 1;
        this.sql.exec("INSERT INTO log VALUES (?, ?, ?, ?, ?)", seq, me.id, env.e, now, text);
        this.sql.exec("UPDATE meta SET head = ?, bytesUsed = bytesUsed + ? WHERE id = 1", seq, text.length);
        return { status: 201, body: { seq, serverAt: now } };
    }

    // ---------------------------------------------------------------- snapshot (compaction)

    /**
     * One part of a snapshot covering the log up to `upto`. Parts are staged; when all `count` are in, the staged snapshot
     * replaces the old one and the log rows it covers are deleted. A new upload (another `upto`, `count` or key) replaces
     * an unfinished one.
     */
    snapshotPut(m, { upto, part, count }, body, now) {
        const env = cleanEnv(body?.env, LIMITS.blobChars);
        if (!env) return bad("Not an envelope");
        if (!(count >= 1 && count <= LIMITS.parts) || !(part >= 0 && part < count)) return bad("Bad part");
        if (env.e !== m.epoch) return epochErr();
        if (upto > m.head) return bad("Past the end of the log");
        if (upto < m.snapUpto) return err(409, "stale", "A newer snapshot exists");
        this.sql.exec("DELETE FROM snapshot WHERE staged = 1 AND (uptoSeq <> ? OR count <> ? OR epoch <> ?)", upto, count, env.e);
        this.sql.exec("INSERT OR REPLACE INTO snapshot VALUES (1, ?, ?, ?, ?, ?, ?)", part, count, upto, env.e, now, JSON.stringify(env));
        const have = this.one("SELECT COUNT(*) AS n FROM snapshot WHERE staged = 1").n;
        if (have < count) return ok({ complete: false });
        this.sql.exec("DELETE FROM snapshot WHERE staged = 0");
        this.sql.exec("UPDATE snapshot SET staged = 0 WHERE staged = 1");
        this.sql.exec("DELETE FROM log WHERE seq <= ?", upto);
        this.sql.exec("UPDATE meta SET snapUpto = ? WHERE id = 1", upto);
        this.recount();
        return ok({ complete: true });
    }

    snapshotGet(part) {
        const r = this.one("SELECT part, count, uptoSeq, epoch, body FROM snapshot WHERE staged = 0 AND part = ?", part);
        return r ? ok({ uptoSeq: r.uptoSeq, part: r.part, count: r.count, epoch: r.epoch, env: parse(r.body) }) : notFound("No snapshot part");
    }

    // ---------------------------------------------------------------- slots

    /** Writes one of the caller's own slots; `serverAt` strictly increases per slot (it is the config's version too). */
    slotPut(m, me, name, body, maxChars, now) {
        const env = cleanEnv(body?.env, maxChars);
        if (!env) return bad("Not an envelope");
        if (env.e !== m.epoch) return epochErr();
        const text = JSON.stringify(env);
        const old = this.one("SELECT serverAt, length(body) AS n FROM slots WHERE name = ?", name);
        if (m.bytesUsed - (old?.n ?? 0) + text.length > LIMITS.spaceBytes) return spaceFull();
        const at = Math.max(now, (old?.serverAt ?? 0) + 1);
        this.sql.exec("INSERT OR REPLACE INTO slots VALUES (?, ?, ?, ?, ?)", name, me.id, env.e, at, text);
        this.sql.exec("UPDATE meta SET bytesUsed = bytesUsed + ? WHERE id = 1", text.length - (old?.n ?? 0));
        return ok({ serverAt: at });
    }

    slotGet(name, now, expires) {
        const r = this.one("SELECT deviceId, serverAt, body FROM slots WHERE name = ?", name);
        if (!r || (expires && now - r.serverAt > SLOT_MS)) return name === "config" ? { status: 204, body: null } : notFound("Nothing published");
        return ok({ device: r.deviceId, serverAt: r.serverAt, env: parse(r.body) });
    }

    nowGet(now) {
        const slots = this.rows("SELECT deviceId, serverAt, body FROM slots WHERE name LIKE 'now:%' AND serverAt >= ? ORDER BY serverAt DESC", now - SLOT_MS)
            .map((r) => ({ device: r.deviceId, serverAt: r.serverAt, env: parse(r.body) }));
        return ok({ slots, serverTime: now });
    }

    /** The shared mirror config, last writer wins: `If-Match: <serverAt>` of the version the writer read (none or 0 = there was none). */
    configPut(m, me, body, ifMatch, now) {
        const cur = this.one("SELECT serverAt FROM slots WHERE name = 'config'");
        const expect = ifMatch == null ? null : String(ifMatch).trim().replace(/^"(.*)"$/, "$1");
        const matches = cur ? expect === String(cur.serverAt) : expect === null || expect === "0";
        if (!matches) return err(412, "changed", "The config changed: read it and merge", { "X-Stash-Config-At": String(cur?.serverAt ?? 0) });
        return this.slotPut(m, me, "config", body, LIMITS.configChars, now);
    }

    // ---------------------------------------------------------------- one-off sends

    inboxPut(m, me, { to, sendId, part, count }, body, now) {
        const env = cleanEnv(body?.env, LIMITS.blobChars);
        if (!env) return bad("Not an envelope");
        if (!(count >= 1 && count <= LIMITS.parts) || !(part >= 0 && part < count)) return bad("Bad part");
        if (to === me.id) return bad("Not to yourself");
        if (!this.one("SELECT id FROM devices WHERE id = ?", to)) return notFound("No such device");
        if (env.e !== m.epoch) return epochErr();
        const first = this.one("SELECT toDevice, fromDevice, count FROM inbox WHERE sendId = ? LIMIT 1", sendId);
        if (first && (first.toDevice !== to || first.fromDevice !== me.id || first.count !== count)) return err(409, "exists", "Another send has this id");
        if (!first) {
            const pending = this.one("SELECT COUNT(DISTINCT sendId) AS n FROM inbox WHERE toDevice = ?", to).n;
            if (pending >= LIMITS.sendsPerDevice) return err(409, "inbox_full", "That device has too many sends waiting");
        }
        const text = JSON.stringify(env);
        const old = this.one("SELECT length(body) AS n FROM inbox WHERE sendId = ? AND part = ?", sendId, part);
        if (m.bytesUsed - (old?.n ?? 0) + text.length > LIMITS.spaceBytes) return err(413, "space_full", "Too big to send. Save it as a file instead.");
        this.sql.exec("INSERT OR REPLACE INTO inbox VALUES (?, ?, ?, ?, ?, ?, ?, ?)", sendId, to, me.id, part, count, env.e, now, text);
        this.sql.exec("UPDATE meta SET bytesUsed = bytesUsed + ? WHERE id = 1", text.length - (old?.n ?? 0));
        const have = this.one("SELECT COUNT(*) AS n FROM inbox WHERE sendId = ?", sendId).n;
        return ok({ complete: have === count });
    }

    /** Complete sends addressed to the caller, newest first. */
    inboxList(me, now) {
        const sends = this.rows(
            `SELECT sendId, fromDevice, count, MIN(serverAt) AS serverAt, SUM(length(body)) AS bytes, COUNT(*) AS n FROM inbox
             WHERE toDevice = ? GROUP BY sendId HAVING n = count AND MIN(serverAt) >= ? ORDER BY serverAt DESC`, me.id, now - SEND_MS)
            .map((r) => ({ sendId: r.sendId, from: r.fromDevice, count: r.count, bytes: r.bytes, serverAt: r.serverAt }));
        return ok({ sends });
    }

    inboxGet(me, { sendId, part }, now) {
        const parts = this.rows("SELECT part, count, fromDevice, serverAt, epoch, body FROM inbox WHERE sendId = ? AND toDevice = ? ORDER BY part", sendId, me.id);
        const complete = parts.length > 0 && parts.length === parts[0].count && Math.min(...parts.map((p) => p.serverAt)) >= now - SEND_MS;
        const r = complete && parts.find((p) => p.part === part);
        if (!r) return notFound("No such send");
        return ok({ sendId, part: r.part, count: r.count, from: r.fromDevice, serverAt: r.serverAt, env: parse(r.body) });
    }

    /** The receiver (done with it) or the sender (taking it back). */
    inboxDelete(me, sendId) {
        const r = this.one("SELECT sendId FROM inbox WHERE sendId = ? AND (toDevice = ? OR fromDevice = ?) LIMIT 1", sendId, me.id, me.id);
        if (!r) return notFound("No such send");
        this.sql.exec("DELETE FROM inbox WHERE sendId = ?", sendId);
        this.recount();
        return { status: 204, body: null };
    }

    // ---------------------------------------------------------------- retention (spec §6.5)

    /**
     * The alarm's work: devices idle for 90 days go (the space with them, when none is left), old slots, sends and
     * abandoned snapshot uploads go. True when the whole space should be deleted.
     */
    retain() {
        const now = this.now();
        return this.tx(() => {
            if (!this.meta()) return false;
            for (const d of this.rows("SELECT id FROM devices WHERE lastSeenAt < ?", now - IDLE_MS)) this.removeDevice(d.id);
            if (this.one("SELECT COUNT(*) AS n FROM devices").n === 0) return true;
            this.sql.exec("DELETE FROM slots WHERE name <> 'config' AND serverAt < ?", now - SLOT_MS);
            this.sql.exec("DELETE FROM inbox WHERE sendId IN (SELECT sendId FROM inbox GROUP BY sendId HAVING MIN(serverAt) < ?)", now - SEND_MS);
            this.sql.exec("DELETE FROM snapshot WHERE staged = 1 AND serverAt < ?", now - STAGING_KEEP_MS);
            this.recount();
            return false;
        });
    }

    /** Recomputes the stored total after deletes (appends and slot writes keep it up to date as they go). */
    recount() {
        const n = this.one(`SELECT
            (SELECT IFNULL(SUM(length(body)), 0) FROM log) +
            (SELECT IFNULL(SUM(length(body)), 0) FROM snapshot WHERE staged = 0) +
            (SELECT IFNULL(SUM(length(body)), 0) FROM slots) +
            (SELECT IFNULL(SUM(length(body)), 0) FROM inbox) AS n`).n;
        this.sql.exec("UPDATE meta SET bytesUsed = ? WHERE id = 1", n);
    }
}
