import { test } from "node:test";
import assert from "node:assert/strict";
import { env } from "./fake-kv.js";
import { sha256Hex } from "../src/store.js";
import { call, seed, KEY_A, KEY_B, HOUR, DAY } from "./community-helpers.js";

const feed = async (e, opts = {}, q = "") => (await (await call(e, "GET", `/v1/community/feed${q}`, opts)).json()).posts;

test("the list is likes plus freshness: 10 more votes hold 35 hours, not 37", async () => {
    const now = Date.now();
    let e = env();
    await seed(e, { id: "OLDVOTED", created: now - 35 * HOUR, up: 10 });
    await seed(e, { id: "NEWPOSTS", created: now });
    assert.deepEqual((await feed(e)).map((p) => p.id), ["OLDVOTED", "NEWPOSTS"]);
    e = env();
    await seed(e, { id: "OLDVOTED", created: now - 37 * HOUR, up: 10 });
    await seed(e, { id: "NEWPOSTS", created: now });
    assert.deepEqual((await feed(e)).map((p) => p.id), ["NEWPOSTS", "OLDVOTED"]);
});

test("hidden (-3), removed and expired posts stay off the list; limit is 5 by default, 100 at most", async () => {
    const e = env();
    await seed(e, { id: "HIDDEN00", down: 3 });
    await seed(e, { id: "REMOVED0", removed: Date.now() });
    await seed(e, { id: "EXPIRED0", created: Date.now() - 31 * DAY });
    await seed(e, { id: "ALMOSTHI", up: 1, down: 3 }); // -2 still shows
    for (let i = 0; i < 6; i++) await seed(e, { id: `VISIBLE${i}`, created: Date.now() - i * HOUR });
    const ids = (await feed(e, {}, "?limit=100")).map((p) => p.id);
    assert.ok(!ids.includes("HIDDEN00") && !ids.includes("REMOVED0") && !ids.includes("EXPIRED0"));
    assert.ok(ids.includes("ALMOSTHI"));
    assert.equal((await feed(e)).length, 5);
    assert.equal((await feed(e, {}, "?limit=500")).length, 7);
});

test("limit is clamped to 1..100 (SQL's LIMIT -1 means no limit at all)", async () => {
    const e = env();
    for (let i = 0; i < 101; i++) await seed(e, { id: `P${String(i).padStart(7, "0")}` });
    assert.equal((await feed(e, {}, "?limit=101")).length, 100);
    assert.equal((await feed(e, {}, "?limit=-1")).length, 1);
    assert.equal((await feed(e, {}, "?limit=0")).length, 1);
});

test("the list never carries the poster id, the network or the songs; myVote and mine need a key", async () => {
    const e = env();
    await seed(e, { id: "MINE0000", key: KEY_A, kind: "playlist", summary: { covers: ["https://i.scdn.co/image/a"] }, body: { covers: [], tracks: [{ t: "T", a: "A" }] } });
    const [anon] = await feed(e);
    assert.deepEqual(Object.keys(anon).sort(), ["count", "covers", "createdAt", "down", "id", "kind", "name", "title", "up"]);
    const [mine] = await feed(e, { key: KEY_A });
    assert.equal(mine.mine, true);
    assert.equal(mine.myVote, 0);
    assert.equal((await feed(e, { key: KEY_B }))[0].mine, false);
    assert.equal((await call(e, "GET", "/v1/community/feed", { key: "short" })).status, 401);
});

test("mine lists your live posts, newest first, including hidden ones", async () => {
    const e = env();
    await seed(e, { id: "MINEOLD0", key: KEY_A, created: Date.now() - 2 * HOUR });
    await seed(e, { id: "MINEHIDE", key: KEY_A, down: 3 });
    await seed(e, { id: "MINEGONE", key: KEY_A, removed: Date.now() });
    await seed(e, { id: "SOMEONES", key: KEY_B });
    const posts = (await (await call(e, "GET", "/v1/community/mine", { key: KEY_A })).json()).posts;
    assert.deepEqual(posts.map((p) => [p.id, p.hidden]), [["MINEHIDE", true], ["MINEOLD0", false]]);
    assert.ok(posts[0].expiresAt > Date.now());
    assert.equal((await call(e, "GET", "/v1/community/mine")).status, 401);
});

test("a post the owner vouched for is never hidden by votes: at -5 it's on the list, opens for anyone, and mine says not hidden", async () => {
    const e = env();
    await seed(e, { id: "VOUCHED0", key: KEY_A, down: 5 });
    await e.COMMUNITY_DB.prepare("UPDATE posts SET vouched = 1").run();
    assert.deepEqual((await feed(e)).map((p) => p.id), ["VOUCHED0"]);
    assert.equal((await call(e, "GET", "/v1/community/posts/VOUCHED0", { key: KEY_B })).status, 200);
    const mine = (await (await call(e, "GET", "/v1/community/mine", { key: KEY_A })).json()).posts;
    assert.deepEqual(mine.map((p) => [p.id, p.hidden]), [["VOUCHED0", false]]);
});

test("the list and mine never read song lists: no query of theirs selects body", async () => {
    const e = env();
    await seed(e, { id: "MINE0000", key: KEY_A });
    const sqls = [];
    const prepare = e.COMMUNITY_DB.prepare;
    e.COMMUNITY_DB.prepare = (sql) => (sqls.push(sql), prepare(sql));
    await feed(e);
    await feed(e, { key: KEY_A });
    await call(e, "GET", "/v1/community/mine", { key: KEY_A });
    assert.equal(sqls.length, 3); // one query per request, so none went unseen
    for (const sql of sqls) assert.doesNotMatch(sql, /\bbody\b|\.\*|SELECT\s+\*/i); // by name, or by a wildcard
});

test("the list walks the hot index: no sort over every live post", async () => {
    const e = env();
    const sqls = [];
    const prepare = e.COMMUNITY_DB.prepare;
    e.COMMUNITY_DB.prepare = (sql) => (sqls.push(sql), prepare(sql));
    await feed(e);
    const plan = e.COMMUNITY_DB.db.prepare(`EXPLAIN QUERY PLAN ${sqls[0]}`).all("", 0, 5).map((r) => r.detail).join(" | ");
    assert.match(plan, /USING INDEX posts_hot/);
    assert.doesNotMatch(plan, /TEMP B-TREE/);
});

test("me says how many posts are left today and how many spots are free", async () => {
    const e = env();
    await seed(e, { id: "TODAY000", key: KEY_A });
    await seed(e, { id: "LIVEOLD0", key: KEY_A, created: Date.now() - 3 * DAY });
    assert.deepEqual(await (await call(e, "GET", "/v1/community/me", { key: KEY_A })).json(), { postsLeftToday: 1, spotsFree: 3, blocked: false });
    await e.COMMUNITY_DB.prepare("INSERT INTO blocked (poster, at) VALUES (?1, 1)").bind(await sha256Hex(KEY_B)).run();
    assert.equal((await (await call(e, "GET", "/v1/community/me", { key: KEY_B })).json()).blocked, true);
});

test("a post opens with its songs; gone ones are 404, and a hidden one only opens for its poster", async () => {
    const e = env();
    await seed(e, { id: "PLAYLIST", kind: "playlist", summary: { covers: [] }, body: { covers: [], tracks: [{ t: "T1", a: "A" }, { t: "T2", a: "A" }] } });
    await seed(e, { id: "SONGPOST", body: { track: { t: "garden", a: "Death Plus" } } });
    await seed(e, { id: "HIDDEN00", key: KEY_A, down: 3 });
    await seed(e, { id: "EXPIRED0", created: Date.now() - 31 * DAY });
    const pl = (await (await call(e, "GET", "/v1/community/posts/PLAYLIST")).json()).post;
    assert.deepEqual(pl.tracks.map((t) => t.t), ["T1", "T2"]);
    assert.equal((await (await call(e, "GET", "/v1/community/posts/SONGPOST")).json()).post.track.t, "garden");
    for (const id of ["HIDDEN00", "EXPIRED0", "NOSUCHID"]) {
        const r = await call(e, "GET", `/v1/community/posts/${id}`, { key: KEY_B });
        assert.equal(r.status, 404, id);
        assert.deepEqual(await r.json(), { error: "gone" });
    }
    assert.equal((await call(e, "GET", "/v1/community/posts/HIDDEN00", { key: KEY_A })).status, 200);
});

test("a removed post is 404 even for its poster, a hidden one 404 without a key; mine drops expired posts; a bad or missing key is 401", async () => {
    const e = env();
    await seed(e, { id: "HIDDEN00", key: KEY_A, down: 3 });
    await seed(e, { id: "REMOVED0", key: KEY_A, removed: Date.now() });
    await seed(e, { id: "MINEEXPD", key: KEY_A, created: Date.now() - 31 * DAY });
    assert.equal((await call(e, "GET", "/v1/community/posts/REMOVED0", { key: KEY_A })).status, 404);
    assert.equal((await call(e, "GET", "/v1/community/posts/HIDDEN00")).status, 404);
    assert.equal((await call(e, "GET", "/v1/community/posts/HIDDEN00", { key: "short" })).status, 401);
    assert.deepEqual((await (await call(e, "GET", "/v1/community/mine", { key: KEY_A })).json()).posts.map((p) => p.id), ["HIDDEN00"]);
    assert.equal((await call(e, "GET", "/v1/community/me")).status, 401);
});
