import { test } from "node:test";
import assert from "node:assert/strict";
import { env } from "./fake-kv.js";
import { handle } from "../src/index.js";
import { sha256Hex } from "../src/store.js";
import { call, laggy, seed, song, songPost, BASE, KEY_A, KEY_B, DAY } from "./community-helpers.js";

test("a post is stored with the key's hash and a salted network hash, never the key or the IP", async () => {
    const e = env();
    const r = await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost() });
    assert.equal(r.status, 201);
    const { id } = await r.json();
    assert.match(id, /^[A-Za-z0-9]{8}$/);
    const row = await e.COMMUNITY_DB.prepare("SELECT * FROM posts WHERE id = ?1").bind(id).first();
    assert.deepEqual([row.kind, row.title, row.poster_name, JSON.parse(row.summary), JSON.parse(row.body), row.track_count],
        ["song", "garden", "Sam", { artist: "Death Plus" }, { track: song }, 1]);
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
    const none = await call(e, "POST", "/v1/community/posts", { body: songPost() });
    assert.equal(none.status, 401);
    assert.deepEqual(await none.json(), { error: "bad_key" });
    const bad = await call(e, "POST", "/v1/community/posts", { key: "short", body: songPost() });
    assert.equal(bad.status, 401);
    assert.deepEqual(await bad.json(), { error: "bad_key" });
    assert.equal((await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost({ kind: "album" }) })).status, 400);
    const big = songPost({ kind: "playlist", body: { tracks: Array.from({ length: 500 }, () => ({ t: "T".repeat(500), a: "A".repeat(100) })) } });
    const tooBig = await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: big });
    assert.equal(tooBig.status, 413);
    assert.deepEqual(await tooBig.json(), { error: "too_large" });
});

test("a content-length over 256 KB is 413 before the body is read", async () => {
    const e = env();
    // A small, valid body: only the header says it's too big.
    const headers = { "CF-Connecting-IP": "203.0.113.9", "X-Stash-Community-Key": KEY_A, "content-type": "application/json", "content-length": String(256 * 1024 + 1) };
    const req = new Request(`${BASE}/v1/community/posts`, { method: "POST", headers, body: JSON.stringify(songPost()) });
    const r = await handle(req, e);
    assert.equal(r.status, 413);
    assert.deepEqual(await r.json(), { error: "too_large" });
    assert.equal(req.bodyUsed, false);
});

test("2 posts a day per phone, and a taken-down post still counts", async () => {
    const e = env();
    const first = await (await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost() })).json();
    assert.equal((await call(e, "DELETE", `/v1/community/posts/${first.id}`, { key: KEY_A })).status, 204);
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

test("the reason is the first limit hit: blocked, then daily, then live", async () => {
    const e = env();
    // KEY_A is blocked and has 2 posts today.
    await e.COMMUNITY_DB.prepare("INSERT INTO blocked (poster, at) VALUES (?1, 1)").bind(await sha256Hex(KEY_A)).run();
    for (let i = 0; i < 2; i++) await seed(e, { id: `BLOCKED${i}`, key: KEY_A });
    const blocked = await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost() });
    assert.equal(blocked.status, 403);
    assert.deepEqual(await blocked.json(), { error: "blocked" });
    // KEY_B has 2 posts today and 3 older ones: 5 live.
    for (let i = 0; i < 5; i++) await seed(e, { id: `DAILYLV${i}`, key: KEY_B, created: Date.now() - (i < 2 ? 0 : 3 * DAY) });
    const daily = await call(e, "POST", "/v1/community/posts", { key: KEY_B, body: songPost() });
    assert.equal(daily.status, 429);
    assert.deepEqual(await daily.json(), { error: "daily_limit" });
});

test("the per-minute limits count by network, an IPv6 address by its /56", async () => {
    const seen = [];
    const rl = (name) => ({ limit: async ({ key }) => (seen.push(`${name} ${key}`), { success: false }) });
    const e = env({ COMMUNITY_READ_RL: rl("read"), COMMUNITY_WRITE_RL: rl("write"), COMMUNITY_VOTE_RL: rl("vote") });
    const from = "2001:db8:aa:bb01::1";
    assert.equal((await call(e, "GET", "/v1/community/feed", { from })).status, 429);
    assert.equal((await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost(), from })).status, 429);
    assert.equal((await call(e, "PUT", "/v1/community/posts/POST0001/vote", { key: KEY_B, body: { value: 1 }, from })).status, 429);
    assert.equal((await call(e, "DELETE", "/v1/community/posts/POST0001", { key: KEY_A, from })).status, 429);
    assert.deepEqual(seen, ["read 2001:db8:aa:bb/56", "write 2001:db8:aa:bb/56", "vote 2001:db8:aa:bb/56", "write 2001:db8:aa:bb/56"]);
});

test("posts at the same moment can't both slip under a limit", async () => {
    const e = laggy(env());
    const rs = await Promise.all([0, 1, 2].map(() => call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost() })));
    assert.deepEqual(rs.map((r) => r.status).sort(), [201, 201, 429]);
});

test("an id that's already taken is retried with a new one", async (t) => {
    const e = env();
    await seed(e, { id: "AAAAAAAA" });
    // newId's first id is AAAAAAAA (all-zero bytes), which is taken; its next is BBBBBBBB.
    const random = t.mock.method(crypto, "getRandomValues", (bytes) => bytes.fill(1));
    random.mock.mockImplementationOnce((bytes) => bytes.fill(0));
    const r = await call(e, "POST", "/v1/community/posts", { key: KEY_A, body: songPost() });
    assert.equal(r.status, 201);
    assert.deepEqual(await r.json(), { id: "BBBBBBBB" });
    // The taken post is left as it was.
    assert.equal(await e.COMMUNITY_DB.prepare("SELECT poster_name FROM posts WHERE id = ?1").bind("AAAAAAAA").first("poster_name"), "Seed");
});
