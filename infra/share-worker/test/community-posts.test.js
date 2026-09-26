import { test } from "node:test";
import assert from "node:assert/strict";
import { env } from "./fake-kv.js";
import { sha256Hex } from "../src/store.js";
import { call, seed, songPost, KEY_A, KEY_B, DAY } from "./community-helpers.js";

test("a post is stored with the key's hash and a salted network hash, never the key or the IP", async () => {
    const e = env();
    const r = await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost() });
    assert.equal(r.status, 201);
    const { id } = await r.json();
    assert.match(id, /^[A-Za-z0-9]{8}$/);
    const row = await e.COMMUNITY_DB.prepare("SELECT * FROM posts WHERE id = ?1").bind(id).first();
    assert.equal(row.poster, await sha256Hex(KEY_A));
    assert.equal(row.ip_hash, await sha256Hex("test-salt|203.0.113.9"));
    assert.equal(row.expires_at - row.created_at, 30 * DAY);
    assert.ok(!JSON.stringify(row).includes(KEY_A) && !JSON.stringify(row).includes("203.0.113.9"));
});

test("without COMMUNITY_SALT a post is refused and nothing is stored", async () => {
    const e = env({ COMMUNITY_SALT: undefined });
    // handle() rejects; the fetch wrapper in index.js turns that into a 503.
    await assert.rejects(call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost() }), /COMMUNITY_SALT/);
    assert.equal(await e.COMMUNITY_DB.prepare("SELECT COUNT(*) AS n FROM posts").first("n"), 0);
});

test("a missing or malformed key is 401 bad_key; a bad body 400; an oversized one 413", async () => {
    const e = env();
    assert.equal((await call(e, "POST", "/v1/community/posts", { body: songPost() })).status, 401);
    const bad = await call(e, "POST", "/v1/community/posts", { key: "short", body: songPost() });
    assert.equal(bad.status, 401);
    assert.deepEqual(await bad.json(), { error: "bad_key" });
    assert.equal((await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost({ kind: "album" }) })).status, 400);
    const big = songPost({ kind: "playlist", body: { tracks: Array.from({ length: 500 }, () => ({ t: "T".repeat(500), a: "A".repeat(100) })) } });
    const tooBig = await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: big });
    assert.equal(tooBig.status, 413);
    assert.deepEqual(await tooBig.json(), { error: "too_large" });
});

test("2 posts a day per phone, and a taken-down post still counts", async () => {
    const e = env();
    const first = await (await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost() })).json();
    // Taken down (Task 7 adds the route; here the row is marked directly).
    await e.COMMUNITY_DB.prepare("UPDATE posts SET removed_at = ?2 WHERE id = ?1").bind(first.id, Date.now()).run();
    assert.equal((await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost() })).status, 201);
    const third = await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost() });
    assert.equal(third.status, 429);
    assert.deepEqual(await third.json(), { error: "daily_limit" });
});

test("5 live posts per phone", async () => {
    const e = env();
    for (let i = 0; i < 5; i++) await seed(e, { id: `LIVE000${i}`, key: KEY_A, created: Date.now() - 3 * DAY });
    const r = await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost() });
    assert.equal(r.status, 429);
    assert.deepEqual(await r.json(), { error: "live_limit" });
});

test("10 posts a day per network, whoever posts them", async () => {
    const e = env();
    const net = await sha256Hex("test-salt|203.0.113.9");
    for (let i = 0; i < 10; i++) await seed(e, { id: `NETW000${i}`, poster: `${i}`.repeat(64), ip: net });
    const r = await call(e, "POST", "/v1/community/posts", { key: KEY_B, body: songPost() });
    assert.equal(r.status, 429);
    assert.deepEqual(await r.json(), { error: "network_limit" });
    assert.equal((await call(e, "POST", "/v1/community/posts", { key: KEY_B, body: songPost(), from: "198.51.100.7" })).status, 201);
});

test("a blocked phone can't post; the write limit answers 429 rate_limited", async () => {
    const e = env();
    await e.COMMUNITY_DB.prepare("INSERT INTO blocked (poster, at) VALUES (?1, 1)").bind(await sha256Hex(KEY_A)).run();
    const r = await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost() });
    assert.equal(r.status, 403);
    assert.deepEqual(await r.json(), { error: "blocked" });
    const limited = env({ COMMUNITY_WRITE_RL: { limit: async () => ({ success: false }) } });
    const rl = await call(limited, "POST", "/v1/community/posts", { key: KEY_B, body: songPost() });
    assert.equal(rl.status, 429);
    assert.deepEqual(await rl.json(), { error: "rate_limited" });
});

test("the per-minute limits count by network, an IPv6 address by its /56", async () => {
    const keys = [];
    const e = env({ COMMUNITY_READ_RL: { limit: async ({ key }) => (keys.push(key), { success: false }) } });
    assert.equal((await call(e, "GET", "/v1/community/feed", { from: "2001:db8:aa:bb01::1" })).status, 429);
    assert.deepEqual(keys, ["2001:db8:aa:bb/56"]);
});
