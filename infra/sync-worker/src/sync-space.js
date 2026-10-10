/**
 * SyncSpace: one SQLite Durable Object per sync space (`idFromName(spaceId)`), a thin shell around src/space.js
 * (as ListenRoom is around room.js). It holds no timers, promises or fetches between requests, so an idle space
 * hibernates and costs nothing (spec §6.6); retention runs on the alarm. The Worker calls it over RPC.
 */
import { RETENTION_EVERY_MS, Space } from "./space.js";

export class SyncSpace {
    constructor(ctx, env, opts = {}) {
        this.ctx = ctx;
        this.env = env;
        this.now = opts.now ?? (() => Date.now());
        this.space = new Space({
            sql: ctx.storage.sql,
            transaction: (fn) => ctx.storage.transactionSync(fn),
            now: this.now,
            claimPair: (pairId, caller, mode) => env.PAIRS.get(env.PAIRS.idFromName(pairId)).claim({ caller, mode }),
        });
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
        // Self-heal: a space whose alarm was lost gets it back when a device opens it.
        if (req.op === "get" && r.status === 200 && (await this.ctx.storage.getAlarm()) === null) {
            await this.ctx.storage.setAlarm(this.now() + RETENTION_EVERY_MS);
        }
        return r;
    }

    async alarm() {
        if (this.space.retain()) return this.wipe();
        if (this.space.meta()) await this.ctx.storage.setAlarm(this.now() + RETENTION_EVERY_MS);
    }

    /** Unlink everything: every table and the alarm go. */
    async wipe() {
        await this.ctx.storage.deleteAlarm();
        await this.ctx.storage.deleteAll();
    }
}
