import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";

const MIGRATION = readFileSync(new URL("../migrations/0001_community.sql", import.meta.url), "utf8");

/**
 * Just enough of Cloudflare D1 for src/community.js, over a real in-memory SQLite running the real
 * migration: prepare().bind().first(col?)/all()/run(), and batch() as one transaction.
 * Rows are copied into plain objects: node:sqlite rows have a null prototype, which
 * assert.deepStrictEqual treats as different from {}.
 */
export function fakeD1() {
    const db = new DatabaseSync(":memory:");
    db.exec(MIGRATION);
    const plain = (r) => (r ? { ...r } : null);
    const statement = (sql, args = []) => ({
        bind: (...a) => statement(sql, a),
        async first(col) {
            const r = plain(db.prepare(sql).get(...args));
            return col === undefined ? r : (r?.[col] ?? null);
        },
        async all() {
            return { results: db.prepare(sql).all(...args).map(plain), success: true };
        },
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
                const out = [];
                for (const s of statements) out.push(await s.all());
                db.exec("COMMIT");
                return out;
            } catch (e) {
                db.exec("ROLLBACK");
                throw e;
            }
        },
    };
}
