import { test } from "node:test";
import assert from "node:assert/strict";
import { fakeD1 } from "./fake-d1.js";

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
