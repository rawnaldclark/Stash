import { DatabaseSync } from "node:sqlite";
import { readdirSync, readFileSync } from "node:fs";

const DIR = new URL("../migrations/", import.meta.url);
/** Every migration, in name order, as `wrangler d1 migrations apply` runs them. */
const MIGRATIONS = readdirSync(DIR).filter((f) => f.endsWith(".sql")).sort().map((f) => readFileSync(new URL(f, DIR), "utf8"));

/**
 * Just enough of Cloudflare D1 for src/community.js, over a real in-memory SQLite running the real
 * migrations: prepare().bind().first(col?)/all()/run(), and batch() as one transaction.
 * Rows are copied into plain objects: node:sqlite rows have a null prototype, which
 * assert.deepStrictEqual treats as different from {}.
 * A missing bind silently becomes NULL here (node:sqlite can't report a statement's parameter
 * count), where D1 rejects a wrong parameter count.
 */
export function fakeD1() {
    const db = new DatabaseSync(":memory:");
    for (const sql of MIGRATIONS) db.exec(sql);
    const plain = (r) => (r ? { ...r } : null);
    const rows = (sql, args) => ({ results: db.prepare(sql).all(...args).map(plain), success: true });
    const statement = (sql, args = []) => ({
        sql,
        args,
        bind: (...a) => statement(sql, a),
        async first(col) {
            const r = plain(db.prepare(sql).get(...args));
            if (col === undefined || !r) return r;
            if (r[col] === undefined) throw new Error(`D1_COLUMN_NOTFOUND: Column not found (${col})`); // as D1
            return r[col];
        },
        async all() { return rows(sql, args); },
        async run() {
            const info = db.prepare(sql).run(...args);
            return { success: true, meta: { changes: Number(info.changes) } };
        },
    });
    return {
        db,
        prepare: (sql) => statement(sql),
        async batch(statements) {
            db.exec("BEGIN");
            try {
                // No await between BEGIN and COMMIT: D1 runs a batch in one go, and an await here would let
                // another in-flight call's statements land inside this transaction (and roll back with it).
                const out = statements.map((s) => rows(s.sql, s.args));
                db.exec("COMMIT");
                return out;
            } catch (e) {
                db.exec("ROLLBACK");
                throw e;
            }
        },
    };
}
