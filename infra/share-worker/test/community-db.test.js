import { test } from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { fakeD1 } from "./fake-d1.js";

const migration = (name) => readFileSync(new URL(`../migrations/${name}`, import.meta.url), "utf8");

const row = (over = {}) => ({
    id: "AAAAAAAA", kind: "song", title: "garden", poster_name: "Sam", poster: "p".repeat(64), ip_hash: "n".repeat(64),
    summary: "{}", body: "{}", track_count: 1, created_at: 1, expires_at: 2, ...over,
});
const insert = (d, r) => d.prepare(
    "INSERT INTO posts (id, kind, title, poster_name, poster, ip_hash, summary, body, track_count, created_at, expires_at) VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)",
).bind(r.id, r.kind, r.title, r.poster_name, r.poster, r.ip_hash, r.summary, r.body, r.track_count, r.created_at, r.expires_at).run();

test("the migration creates posts, votes and blocked, and rows come back as plain objects", async () => {
    const d = fakeD1();
    await insert(d, row());
    const got = await d.prepare("SELECT id, up, down, removed_at FROM posts WHERE id = ?1").bind("AAAAAAAA").first();
    assert.deepEqual(got, { id: "AAAAAAAA", up: 0, down: 0, removed_at: null });
    assert.equal(await d.prepare("SELECT COUNT(*) AS n FROM votes").first("n"), 0);
    assert.equal(await d.prepare("SELECT COUNT(*) AS n FROM blocked").first("n"), 0);
});

test("the kind CHECK rejects anything but playlist, mix and song", async () => {
    await assert.rejects(() => insert(fakeD1(), row({ kind: "album" })));
});

test("the removed_by CHECK: set exactly when removed_at is, to poster or owner", async () => {
    const d = fakeD1();
    await insert(d, row());
    const set = (at, by) => d.prepare("UPDATE posts SET removed_at = ?1, removed_by = ?2").bind(at, by).run();
    await assert.rejects(() => set(1, null), /CHECK constraint failed/);
    await assert.rejects(() => set(null, "owner"), /CHECK constraint failed/);
    await assert.rejects(() => set(1, "admin"), /CHECK constraint failed/);
    await set(1, "poster");
    await set(null, null);
});

test("batch runs in one transaction and returns each statement's rows", async () => {
    const d = fakeD1();
    await insert(d, row());
    const [, second] = await d.batch([
        d.prepare("UPDATE posts SET up = 3 WHERE id = ?1").bind("AAAAAAAA"),
        d.prepare("SELECT up FROM posts WHERE id = ?1").bind("AAAAAAAA"),
    ]);
    assert.deepEqual(second.results, [{ up: 3 }]);
    await assert.rejects(() => d.batch([
        d.prepare("UPDATE posts SET up = 9 WHERE id = ?1").bind("AAAAAAAA"),
        d.prepare("INSERT INTO votes (post_id) VALUES (?1)").bind("AAAAAAAA"), // NOT NULL violation
    ]), /NOT NULL constraint failed: votes\.voter/);
    assert.equal(await d.prepare("SELECT up FROM posts").first("up"), 3, "the failed batch rolled back");
});

test("two batches at once both land, as on D1", async () => {
    const d = fakeD1();
    await Promise.all(["a", "b"].map((p) => d.batch([d.prepare("INSERT INTO blocked (poster, at) VALUES (?1, 1)").bind(p)])));
    assert.equal(await d.prepare("SELECT COUNT(*) AS n FROM blocked").first("n"), 2);
});

test("0002 keeps every row 0001 held, adds vouched = 0 just before body, and rebuilds 0001's indexes exactly", () => {
    const db = new DatabaseSync(":memory:");
    db.exec(migration("0001_community.sql"));
    db.exec("INSERT INTO posts (id, kind, title, poster_name, poster, ip_hash, summary, body, track_count, created_at, expires_at, up, down, removed_at, removed_by) VALUES ('AAAAAAAA', 'mix', 't', 'n', 'p', 'i', 's', 'b', 7, 1, 2, 3, 4, 5, 'owner')");
    const indexes = () => db.prepare("SELECT name, sql FROM sqlite_schema WHERE type = 'index' AND sql IS NOT NULL ORDER BY name").all().map((r) => ({ ...r }));
    const before = { ...db.prepare("SELECT * FROM posts").get() };
    const beforeIndexes = indexes();
    db.exec(migration("0002_vouched.sql"));
    const after = { ...db.prepare("SELECT * FROM posts").get() };
    assert.deepEqual(after, { ...before, vouched: 0 });
    assert.deepEqual(Object.keys(after), [...Object.keys(before).slice(0, -1), "vouched", "body"]);
    assert.deepEqual(indexes(), beforeIndexes);
});
