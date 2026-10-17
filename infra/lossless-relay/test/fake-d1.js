import { DatabaseSync } from "node:sqlite";
import { readFileSync, readdirSync } from "node:fs";

/**
 * Just enough of D1's prepare/bind/first/all/run/batch over node:sqlite for the SQL in
 * src/db.js to run unchanged. Applies every migrations/*.sql, in order, to a fresh in-memory DB
 * (`dir`: another migrations folder, e.g. the shared quota DB's, QUOTA_MIGRATIONS).
 */
export const QUOTA_MIGRATIONS = new URL("../../lossless-quota/migrations/", import.meta.url);

export function fakeD1(dir = new URL("../migrations/", import.meta.url)) {
    const raw = new DatabaseSync(":memory:");
    for (const f of readdirSync(dir).filter((n) => n.endsWith(".sql")).sort()) raw.exec(readFileSync(new URL(f, dir), "utf8"));
    return {
        raw,
        prepare(sql) {
            const st = raw.prepare(sql);
            let args = [];
            const s = {
                bind: (...a) => { args = a; return s; },
                // node:sqlite rows are null-prototype objects; D1 hands back plain ones, and deepEqual can tell.
                first: async () => { const r = st.get(...args); return r ? { ...r } : null; },
                all: async () => ({ results: st.all(...args).map((r) => ({ ...r })) }),
                run: async () => { const r = st.run(...args); return { meta: { changes: r.changes } }; },
            };
            return s;
        },
        async batch(stmts) {
            const out = [];
            for (const s of stmts) out.push(await s.all());
            return out;
        },
    };
}
