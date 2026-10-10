/**
 * PairSlot: one short-lived Durable Object per pairing code (`idFromName(pairId)`). A thin shell around src/pair.js:
 * the slot is one storage value, the alarm deletes everything when the slot's time is up, and the two long-polls
 * (browser waiting for the answer, phone waiting for the reply) wait in memory for the write that wakes them.
 * Only this object ever holds a request open, and only while a code is on screen (spec §6.6).
 */
import { CLAIM_GRACE_MS, POLL_MS, answerSlot, claimSlot, openSlot, pollSlot, readLabel, readReply, replySlot } from "./pair.js";
import { err } from "./http.js";

/** Resolves after `ms`, or as soon as `signal` aborts (the timer is then cleared, so nothing outlives the request). */
function sleep(ms, signal) {
    return new Promise((resolve) => {
        const t = setTimeout(resolve, ms);
        signal.addEventListener("abort", () => { clearTimeout(t); resolve(); }, { once: true });
    });
}

export class PairSlot {
    constructor(ctx, env, opts = {}) {
        this.ctx = ctx;
        this.env = env;
        this.now = opts.now ?? (() => Date.now());
        this.sleep = opts.sleep ?? sleep;
        this.waiters = new Set();
    }

    async load() {
        return (await this.ctx.storage.get("slot")) ?? null;
    }

    /** Stores the new slot (if any) and wakes every long-poll so it looks again. */
    async commit(r) {
        if (r.state) {
            await this.ctx.storage.put("slot", r.state);
            for (const wake of this.waiters) wake();
            this.waiters.clear();
        }
        return { status: r.status, body: r.body ?? null, ...(r.headers ? { headers: r.headers } : {}) };
    }

    async open({ pairId, device }) {
        if (await this.load()) return err(409, "exists", "Slot exists");
        const r = openSlot(pairId, device, this.now());
        if (r.state) await this.ctx.storage.setAlarm(r.state.expiresAt + CLAIM_GRACE_MS);
        return this.commit(r);
    }

    async label() {
        return this.commit(readLabel(await this.load(), this.now()));
    }

    async answer({ body }) {
        return this.commit(answerSlot(await this.load(), body, this.now()));
    }

    async reply({ caller, body }) {
        return this.commit(replySlot(await this.load(), caller, body, this.now()));
    }

    async claim({ caller, mode }) {
        return this.commit(claimSlot(await this.load(), caller, mode, this.now()));
    }

    /** The browser's long-poll for the answer. */
    async poll({ caller }) {
        return this.longPoll((s, now) => pollSlot(s, caller, now));
    }

    /** The phone's long-poll for the browser's reply. */
    async readReply({ caller }) {
        return this.longPoll((s, now) => readReply(s, caller, now));
    }

    /** Asks `check` until it answers something other than 204, the slot changes, or POLL_MS (or the code's life) runs out. */
    async longPoll(check) {
        const deadline = this.now() + POLL_MS;
        for (;;) {
            const s = await this.load();
            const r = check(s, this.now());
            if (r.status !== 204) return this.commit(r);
            const until = s && !s.answer ? Math.min(deadline, s.expiresAt) : deadline;
            const left = until - this.now();
            if (left <= 0) return this.commit(check(await this.load(), this.now()));
            const stop = new AbortController();
            const wake = () => stop.abort();
            this.waiters.add(wake);
            await this.sleep(left, stop.signal);
            this.waiters.delete(wake);
        }
    }

    async alarm() {
        await this.ctx.storage.deleteAll();
    }
}
