/**
 * The "heard" mark (contract 2026-11-03): PUT /v1/community/posts/{id}/heard after 30 s of playing a song from a post,
 * `heard` on every summary that carries `myVote`, and HEARD_REQUIRED, which makes a +1 or -1 vote need the mark.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { commandSql } from "../scripts/community.mjs";
import { cleanup } from "../src/community.js";
import { sha256Hex } from "../src/store.js";
import { env } from "./fake-kv.js";
import { call, seed, DAY, HOUR, KEY_A, KEY_B, KEY_C } from "./community-helpers.js";

const heard = (e, id, key, from) => call(e, "PUT", `/v1/community/posts/${id}/heard`, { key, from });
const vote = (e, id, value, key, from) => call(e, "PUT", `/v1/community/posts/${id}/vote`, { key, body: { value }, from });
const marks = async (e) => (await e.COMMUNITY_DB.prepare("SELECT post_id, voter, at FROM heard ORDER BY post_id, voter").all()).results;
const voteRows = async (e) => (await e.COMMUNITY_DB.prepare("SELECT post_id, value FROM votes ORDER BY post_id, value").all()).results;
const feedPost = async (e, key) => (await (await call(e, "GET", "/v1/community/feed", { key })).json()).posts[0];
const openPost = async (e, id, key) => (await (await call(e, "GET", `/v1/community/posts/${id}`, { key })).json()).post;
const on = (over = {}) => env({ HEARD_REQUIRED: "1", ...over });

test("heard is stored once as (post, the key's hash, when): 204, and sending it again changes nothing", async () => {
    const e = env();
    await seed(e, { id: "POST0001", key: KEY_A });
    const first = await heard(e, "POST0001", KEY_B);
    assert.equal(first.status, 204);
    assert.equal(await first.text(), "");
    const [mark] = await marks(e);
    assert.deepEqual({ post_id: mark.post_id, voter: mark.voter }, { post_id: "POST0001", voter: await sha256Hex(KEY_B) });
    assert.equal((await heard(e, "POST0001", KEY_B)).status, 204);
    assert.deepEqual(await marks(e), [mark], "the first time is kept");
});

test("a heard mark holds no address or network: the table is post, voter and time only", async () => {
    const e = env();
    const columns = e.COMMUNITY_DB.db.prepare("SELECT name FROM pragma_table_info('heard') ORDER BY cid").all().map((c) => c.name);
    assert.deepEqual(columns, ["post_id", "voter", "at"]);
});

test("heard on a missing, removed, expired or hidden post is 404 gone, and stores nothing", async () => {
    const e = env();
    const now = Date.now();
    await seed(e, { id: "REMOVED0", removed: now });
    await seed(e, { id: "TAKENDWN", removed: now, removedBy: "poster" });
    await seed(e, { id: "EXPIRED0", created: now - 31 * DAY });
    await seed(e, { id: "HIDDEN00", down: 3 });
    for (const id of ["MISSING0", "REMOVED0", "TAKENDWN", "EXPIRED0", "HIDDEN00"]) {
        const r = await heard(e, id, KEY_B);
        assert.equal(r.status, 404, id);
        assert.deepEqual(await r.json(), { error: "gone" }, id);
    }
    assert.deepEqual(await marks(e), []);
});

test("heard on your own post is 403 own_post; no key or a bad one is 401; only PUT", async () => {
    const e = env();
    await seed(e, { id: "POST0001", key: KEY_A });
    const own = await heard(e, "POST0001", KEY_A);
    assert.equal(own.status, 403);
    assert.deepEqual(await own.json(), { error: "own_post" });
    for (const key of [undefined, "short"]) {
        const r = await heard(e, "POST0001", key);
        assert.equal(r.status, 401, String(key));
        assert.deepEqual(await r.json(), { error: "bad_key" });
    }
    for (const method of ["POST", "DELETE", "GET"]) assert.equal((await call(e, method, "/v1/community/posts/POST0001/heard", { key: KEY_B })).status, 405, method);
    assert.deepEqual(await marks(e), []);
});

test("heard shares the vote limit, per network: over it, 429 rate_limited and nothing stored", async () => {
    const keys = [];
    let allow = true;
    const e = env({ COMMUNITY_VOTE_RL: { limit: async ({ key }) => (keys.push(key), { success: allow }) } });
    await seed(e, { id: "POST0001", key: KEY_A });
    await heard(e, "POST0001", KEY_B, "203.0.113.5");
    await heard(e, "POST0001", KEY_C, "2001:db8:aa:bb01::1");
    assert.deepEqual(keys, ["203.0.113.5", "2001:db8:aa:bb/56"]);
    allow = false;
    const r = await heard(e, "POST0001", KEY_A.replace("a", "d"));
    assert.equal(r.status, 429);
    assert.deepEqual(await r.json(), { error: "rate_limited" });
    assert.equal((await marks(e)).length, 2);
});

test("a post the cleanup deletes mid-heard leaves no mark behind", async () => {
    const e = env();
    await seed(e, { id: "POST0001", key: KEY_A });
    const d1 = e.COMMUNITY_DB;
    const prepare = d1.prepare;
    // The cleanup lands after heard's checks, just before its INSERT.
    e.COMMUNITY_DB = { ...d1, prepare: (sql) => {
        if (sql.startsWith("INSERT INTO heard")) d1.db.exec("DELETE FROM posts WHERE id = 'POST0001'");
        return prepare(sql);
    } };
    assert.equal((await heard(e, "POST0001", KEY_B)).status, 204);
    assert.deepEqual(await marks({ COMMUNITY_DB: d1 }), []);
});

test("feed and post answers carry heard next to myVote: false, then true once heard; absent without a key", async () => {
    const e = env();
    await seed(e, { id: "POST0001", key: KEY_A });
    assert.equal((await feedPost(e, KEY_B)).heard, false);
    assert.equal((await openPost(e, "POST0001", KEY_B)).heard, false);
    await heard(e, "POST0001", KEY_B);
    const fromFeed = await feedPost(e, KEY_B);
    assert.equal(fromFeed.heard, true);
    assert.equal(fromFeed.myVote, 0);
    assert.equal((await openPost(e, "POST0001", KEY_B)).heard, true);
    // Someone else's mark isn't yours.
    assert.equal((await feedPost(e, KEY_C)).heard, false);
    // Keyless (web, edge-cached) reads carry neither field.
    for (const p of [await feedPost(e), await openPost(e, "POST0001")]) {
        assert.equal("heard" in p, false);
        assert.equal("myVote" in p, false);
    }
});

test("your own post is always heard: in /mine, the feed and the post", async () => {
    const e = env();
    await seed(e, { id: "POST0001", key: KEY_A });
    const [mine] = (await (await call(e, "GET", "/v1/community/mine", { key: KEY_A })).json()).posts;
    assert.equal(mine.heard, true);
    assert.equal((await feedPost(e, KEY_A)).heard, true);
    assert.equal((await openPost(e, "POST0001", KEY_A)).heard, true);
});

test("enforcement is off by default, and for any value but \"1\": votes without a listen count as before", async () => {
    for (const value of [undefined, "0", "", "true", "yes", " 1"]) {
        const e = value === undefined ? env() : env({ HEARD_REQUIRED: value });
        await seed(e, { id: "POST0001", key: KEY_A });
        assert.deepEqual(await (await vote(e, "POST0001", 1, KEY_B, "203.0.113.1")).json(), { up: 1, down: 0, myVote: 1 }, String(value));
        assert.deepEqual(await (await vote(e, "POST0001", -1, KEY_C, "203.0.113.2")).json(), { up: 1, down: 1, myVote: -1 }, String(value));
    }
});

test("enforcement on: +1 and -1 without a listen are 403 listen_first and not stored", async () => {
    const e = on();
    await seed(e, { id: "POST0001", key: KEY_A });
    for (const value of [1, -1]) {
        const r = await vote(e, "POST0001", value, KEY_B);
        assert.equal(r.status, 403, String(value));
        assert.deepEqual(await r.json(), { error: "listen_first" });
    }
    assert.deepEqual(await voteRows(e), []);
    const p = await feedPost(e, KEY_B);
    assert.deepEqual([p.up, p.down, p.myVote, p.heard], [0, 0, 0, false]);
});

test("enforcement on: after heard, both directions work and can change", async () => {
    const e = on();
    await seed(e, { id: "POST0001", key: KEY_A });
    await heard(e, "POST0001", KEY_B);
    assert.deepEqual(await (await vote(e, "POST0001", 1, KEY_B)).json(), { up: 1, down: 0, myVote: 1 });
    assert.deepEqual(await (await vote(e, "POST0001", -1, KEY_B)).json(), { up: 0, down: 1, myVote: -1 });
    // A mark is per post: another post still needs its own.
    await seed(e, { id: "POST0002", key: KEY_A });
    assert.equal((await vote(e, "POST0002", 1, KEY_B)).status, 403);
});

test("enforcement on: 0 is always allowed, so a vote cast before the switch can be taken back without a listen", async () => {
    const e = env();
    await seed(e, { id: "POST0001", key: KEY_A });
    await vote(e, "POST0001", -1, KEY_B); // cast while enforcement was off
    e.HEARD_REQUIRED = "1";
    assert.equal((await vote(e, "POST0001", 1, KEY_B)).status, 403, "changing it still needs a listen");
    assert.deepEqual(await (await vote(e, "POST0001", 0, KEY_B)).json(), { up: 0, down: 0, myVote: 0 });
    assert.deepEqual(await voteRows(e), []);
    // 0 with nothing to take back is fine too.
    assert.equal((await vote(e, "POST0001", 0, KEY_C)).status, 200);
});

test("enforcement on: the other refusals come first (gone, own post, blocked)", async () => {
    const e = on();
    await seed(e, { id: "POST0001", key: KEY_A });
    assert.deepEqual(await (await vote(e, "MISSING0", 1, KEY_B)).json(), { error: "gone" });
    assert.deepEqual(await (await vote(e, "POST0001", 1, KEY_A)).json(), { error: "own_post" });
    await e.COMMUNITY_DB.prepare("INSERT INTO blocked (poster, at) VALUES (?1, 1)").bind(await sha256Hex(KEY_B)).run();
    assert.deepEqual(await (await vote(e, "POST0001", 1, KEY_B)).json(), { error: "blocked" });
});

test("the daily cleanup deletes heard marks with their posts and keeps the rest", async () => {
    const e = env();
    const now = Date.now();
    await seed(e, { id: "EXPIRED0", created: now - 31 * DAY });
    await seed(e, { id: "OLDREMOV", created: now - 3 * DAY, removed: now - 2 * DAY });
    await seed(e, { id: "RESTORAB", created: now - 3 * DAY, removed: now - 2 * HOUR });
    await seed(e, { id: "LIVEPOST", created: now - 3 * DAY });
    for (const id of ["EXPIRED0", "OLDREMOV", "RESTORAB", "LIVEPOST"]) {
        await e.COMMUNITY_DB.prepare("INSERT INTO heard (post_id, voter, at) VALUES (?1, 'v', 1)").bind(id).run();
    }
    await cleanup(e, now);
    assert.deepEqual((await marks(e)).map((m) => m.post_id), ["LIVEPOST", "RESTORAB"]);
});

test("drop-unheard-downvotes deletes downvotes with no listen, keeps upvotes and heard downvotes, and recounts", async () => {
    const e = env();
    await seed(e, { id: "POST0001", key: KEY_A });
    await seed(e, { id: "POST0002", key: KEY_A });
    // Cast through the API so the stored counts are the Worker's own.
    await heard(e, "POST0001", KEY_C, "198.51.100.3");
    await vote(e, "POST0001", -1, KEY_B, "203.0.113.1"); // no listen: goes
    await vote(e, "POST0001", -1, KEY_C, "198.51.100.3"); // heard: stays
    await vote(e, "POST0001", 1, KEY_B.replace("b", "d"), "203.0.113.4"); // an upvote: stays
    await vote(e, "POST0002", -1, KEY_B, "203.0.113.1"); // no listen: goes
    const shown = await run(e, commandSql("drop-unheard-downvotes"));
    assert.deepEqual(shown[0], [{ dropping: 2, voters: 1, posts: 2 }]);
    assert.deepEqual(shown.at(-1), [{ downvotes_left: 1, posts_at_or_below_0: 2 }]);
    const counts = (await e.COMMUNITY_DB.prepare("SELECT id, up, down FROM posts ORDER BY id").all()).results;
    assert.deepEqual(counts, [{ id: "POST0001", up: 1, down: 1 }, { id: "POST0002", up: 0, down: 0 }]);
    // It's safe to run twice: nothing more goes.
    assert.deepEqual((await run(e, commandSql("drop-unheard-downvotes")))[0], [{ dropping: 0, voters: 0, posts: 0 }]);
});

test("drop-unheard-downvotes' recount keeps the per-network cap", async () => {
    const e = env();
    await seed(e, { id: "POST0001", key: KEY_A });
    for (let i = 0; i < 4; i++) await vote(e, "POST0001", 1, `${i}`.padStart(43, "k"), "203.0.113.50");
    await run(e, commandSql("drop-unheard-downvotes"));
    assert.equal(await e.COMMUNITY_DB.prepare("SELECT up FROM posts WHERE id = 'POST0001'").first("up"), 2);
});

test("drop-unheard-downvotes fits one quoted --command line and takes no argument", () => {
    assert.ok(!/--|["%]|\?\d/.test(commandSql("drop-unheard-downvotes")));
    assert.throws(() => commandSql("drop-unheard-downvotes", "POST0001"));
});

/** Runs each statement in turn, as `d1 execute` does, and returns each one's rows. */
async function run(e, sql) {
    const rows = [];
    for (const s of sql.split(";\n")) rows.push((await e.COMMUNITY_DB.prepare(s).all()).results);
    return rows;
}
