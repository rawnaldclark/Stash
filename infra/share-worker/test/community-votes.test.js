import { test } from "node:test";
import assert from "node:assert/strict";
import { env } from "./fake-kv.js";
import { sha256Hex } from "../src/store.js";
import { call, seed, laggy, KEY_A, KEY_B, KEY_C } from "./community-helpers.js";

const vote = (e, id, value, key, from) => call(e, "PUT", `/v1/community/posts/${id}/vote`, { key, body: { value }, from });
const keyN = (n) => `${n}`.padStart(43, "k");

test("a vote counts once per phone, can change, and 0 takes it back", async () => {
    const e = env();
    await seed(e, { id: "POST0001", key: KEY_A });
    assert.deepEqual(await (await vote(e, "POST0001", 1, KEY_B)).json(), { up: 1, down: 0, myVote: 1 });
    assert.deepEqual(await (await vote(e, "POST0001", 1, KEY_B)).json(), { up: 1, down: 0, myVote: 1 });
    assert.deepEqual(await (await vote(e, "POST0001", -1, KEY_B)).json(), { up: 0, down: 1, myVote: -1 });
    assert.deepEqual(await (await vote(e, "POST0001", 0, KEY_B)).json(), { up: 0, down: 0, myVote: 0 });
    const [p] = (await (await call(e, "GET", "/v1/community/feed", { key: KEY_B })).json()).posts;
    assert.equal(p.myVote, 0);
});

test("one network counts for at most 2 votes each way, however many keys it makes", async () => {
    const e = env();
    await seed(e, { id: "POST0001", key: KEY_A });
    for (let i = 0; i < 6; i++) await vote(e, "POST0001", 1, keyN(i), "203.0.113.50");
    assert.equal((await (await vote(e, "POST0001", 1, KEY_C, "198.51.100.7")).json()).up, 3);
    // Two IPv6 addresses in one /56 are one network.
    await vote(e, "POST0001", -1, keyN(10), "2001:db8:aa:bb01::1");
    await vote(e, "POST0001", -1, keyN(11), "2001:db8:aa:bb02::1");
    const r = await (await vote(e, "POST0001", -1, keyN(12), "2001:db8:aa:bbff::9")).json();
    assert.equal(r.down, 2);
});

test("votes at the same moment from different networks all count", async () => {
    const e = laggy(env());
    await seed(e, { id: "POST0001", key: KEY_A });
    await Promise.all(["198.51.100.1", "198.51.100.2", "198.51.100.3"].map((from, i) => vote(e, "POST0001", 1, keyN(20 + i), from)));
    const [p] = (await (await call(e, "GET", "/v1/community/feed")).json()).posts;
    assert.equal(p.up, 3);
});

test("a vote is stored only together with its recount", async () => {
    const e = env();
    await seed(e, { id: "POST0001", key: KEY_A });
    // D1 rolls a whole batch back when one statement fails: make the recount fail.
    await e.COMMUNITY_DB.prepare("CREATE TRIGGER no_recount BEFORE UPDATE OF up, down ON posts BEGIN SELECT RAISE(ABORT, 'recount failed'); END").run();
    await assert.rejects(vote(e, "POST0001", 1, KEY_B), /recount failed/);
    assert.equal(await e.COMMUNITY_DB.prepare("SELECT COUNT(*) AS n FROM votes").first("n"), 0);
});

test("a post the cleanup deletes mid-vote is gone, and no vote row is left behind", async () => {
    const e = env();
    await seed(e, { id: "POST0001", key: KEY_A });
    const d1 = e.COMMUNITY_DB;
    // The daily cleanup deletes the post after the vote's checks, just before its batch.
    e.COMMUNITY_DB = { ...d1, batch: async (s) => { d1.db.exec("DELETE FROM posts WHERE id = 'POST0001'"); return d1.batch(s); } };
    const r = await vote(e, "POST0001", 1, KEY_B);
    assert.equal(r.status, 404);
    assert.deepEqual(await r.json(), { error: "gone" });
    assert.equal(await d1.prepare("SELECT COUNT(*) AS n FROM votes").first("n"), 0);
});

test("-3 from two networks hides a post: off the list, 404 to others, no more votes", async () => {
    const e = env();
    await seed(e, { id: "POST0001", key: KEY_A });
    await vote(e, "POST0001", -1, keyN(1), "203.0.113.50");
    await vote(e, "POST0001", -1, keyN(2), "203.0.113.50");
    assert.equal((await (await vote(e, "POST0001", -1, keyN(3), "198.51.100.7")).json()).down, 3);
    assert.equal((await (await call(e, "GET", "/v1/community/feed")).json()).posts.length, 0);
    assert.equal((await call(e, "GET", "/v1/community/posts/POST0001", { key: KEY_B })).status, 404);
    assert.equal((await vote(e, "POST0001", 1, keyN(4), "192.0.2.1")).status, 404);
});

test("no voting on your own post, while blocked, on a gone post, or with a bad value or key", async () => {
    const e = env();
    await seed(e, { id: "POST0001", key: KEY_A });
    await seed(e, { id: "GONE0001", removed: Date.now() });
    const own = await vote(e, "POST0001", 1, KEY_A);
    assert.equal(own.status, 403);
    assert.deepEqual(await own.json(), { error: "own_post" });
    await e.COMMUNITY_DB.prepare("INSERT INTO blocked (poster, at) VALUES (?1, 1)").bind(await sha256Hex(KEY_C)).run();
    assert.deepEqual(await (await vote(e, "POST0001", 1, KEY_C)).json(), { error: "blocked" });
    for (const id of ["GONE0001", "NOSUCHID"]) {
        const r = await vote(e, id, 1, KEY_B);
        assert.equal(r.status, 404, id);
        assert.deepEqual(await r.json(), { error: "gone" });
    }
    const badValue = await vote(e, "POST0001", 2, KEY_B);
    assert.equal(badValue.status, 400);
    assert.deepEqual(await badValue.json(), { error: "bad_request" });
    const noKey = await vote(e, "POST0001", 1, undefined);
    assert.equal(noKey.status, 401);
    assert.deepEqual(await noKey.json(), { error: "bad_key" });
});

test("no voting on an expired post", async () => {
    const e = env();
    await seed(e, { id: "EXPIRED0", key: KEY_A, expires: Date.now() - 1 });
    const r = await vote(e, "EXPIRED0", 1, KEY_B);
    assert.equal(r.status, 404);
    assert.deepEqual(await r.json(), { error: "gone" });
});

test("votes are rate-limited per network", async () => {
    const e = env({ COMMUNITY_VOTE_RL: { limit: async () => ({ success: false }) } });
    await seed(e, { id: "POST0001", key: KEY_A });
    const r = await vote(e, "POST0001", 1, KEY_B);
    assert.equal(r.status, 429);
    assert.deepEqual(await r.json(), { error: "rate_limited" });
});
