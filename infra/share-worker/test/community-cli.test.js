import { test } from "node:test";
import assert from "node:assert/strict";
import { commandSql } from "../scripts/community.mjs";
import { env } from "./fake-kv.js";
import { sha256Hex } from "../src/store.js";
import { call, seed, DAY, KEY_A, KEY_B, KEY_C } from "./community-helpers.js";

/** Runs each statement in turn, as `d1 execute` does, and returns each one's rows. */
const run = async (e, sql) => {
    const rows = [];
    for (const s of sql.split(";\n")) rows.push((await e.COMMUNITY_DB.prepare(s).all()).results);
    return rows;
};
/** Three downvotes and an upvote on POST0001, each from its own network. */
const threeDownOneUp = (e) => e.COMMUNITY_DB.prepare("INSERT INTO votes (post_id, voter, ip_hash, value, at) VALUES ('POST0001','v1','n1',-1,1),('POST0001','v2','n2',-1,1),('POST0001','v3','n3',-1,1),('POST0001','v4','n4',1,1)").run();
const removal = (e, id = "POST0001") => e.COMMUNITY_DB.prepare("SELECT removed_at, removed_by FROM posts WHERE id = ?1").bind(id).first();
const takeDown = (e, id) => call(e, "DELETE", `/v1/community/posts/${id}`, { key: KEY_A });
const vote = (e, value, key, from) => call(e, "PUT", "/v1/community/posts/POST0001/vote", { key, body: { value }, from });

test("remove and restore: restore clears the owner's removal, drops the downvotes, recounts and vouches for the post", async () => {
    const e = env();
    await seed(e, { id: "POST0001", down: 3 });
    await threeDownOneUp(e);
    await run(e, commandSql("remove", "POST0001", 42));
    assert.deepEqual(await removal(e), { removed_at: 42, removed_by: "owner" });
    const shown = await run(e, commandSql("restore", "POST0001", 43));
    assert.deepEqual(shown.at(-1), [{ id: "POST0001", up: 1, down: 0, removed: null, vouched: 1 }]);
    assert.deepEqual(await removal(e), { removed_at: null, removed_by: null });
});

test("a post its poster took down: remove changes nothing, and restore leaves it down with its votes, unvouched", async () => {
    const e = env();
    await seed(e, { id: "POST0001", key: KEY_A, up: 1, down: 3 });
    await threeDownOneUp(e);
    assert.equal((await takeDown(e, "POST0001")).status, 204);
    const takenDown = await removal(e);
    assert.deepEqual(await run(e, commandSql("remove", "POST0001", 42)), [[]]);
    assert.deepEqual(await removal(e), takenDown);
    const shown = await run(e, commandSql("restore", "POST0001", 43));
    assert.deepEqual(shown.at(-1), [{ id: "POST0001", up: 1, down: 3, removed: "poster", vouched: 0 }]);
    assert.deepEqual(await removal(e), takenDown);
    assert.equal(await e.COMMUNITY_DB.prepare("SELECT COUNT(*) AS n FROM votes").first("n"), 4);
});

test("restore of a post hidden by votes, never removed, drops its downvotes", async () => {
    const e = env();
    await seed(e, { id: "POST0001", down: 3 });
    await threeDownOneUp(e);
    const shown = await run(e, commandSql("restore", "POST0001", 43));
    assert.deepEqual(shown.at(-1), [{ id: "POST0001", up: 1, down: 0, removed: null, vouched: 1 }]);
    assert.equal(await e.COMMUNITY_DB.prepare("SELECT COUNT(*) AS n FROM votes WHERE value = -1").first("n"), 0);
});

test("a post restore vouched for can't be hidden again: fresh downvotes from two networks leave it listed, and it still takes votes", async () => {
    const e = env();
    await seed(e, { id: "POST0001", down: 3 });
    await e.COMMUNITY_DB.prepare("INSERT INTO votes (post_id, voter, ip_hash, value, at) VALUES ('POST0001','v1','n1',-1,1),('POST0001','v2','n2',-1,1),('POST0001','v3','n3',-1,1)").run();
    assert.deepEqual((await run(e, commandSql("restore", "POST0001", 43))).at(-1), [{ id: "POST0001", up: 0, down: 0, removed: null, vouched: 1 }]);
    await vote(e, -1, KEY_A, "203.0.113.50");
    await vote(e, -1, KEY_B, "203.0.113.50");
    assert.equal((await (await vote(e, -1, KEY_C, "198.51.100.7")).json()).down, 3); // -3 would hide a post nobody vouched for
    assert.deepEqual((await (await call(e, "GET", "/v1/community/feed")).json()).posts.map((p) => p.id), ["POST0001"]);
    const [[listed]] = await run(e, commandSql("list", "10"));
    assert.deepEqual([listed.hidden, listed.vouched], [0, 1]);
    assert.deepEqual(await (await vote(e, 1, KEY_C, "198.51.100.7")).json(), { up: 1, down: 2, myVote: 1 }); // a vote at -3 still counts
});

test("block records the poster and removes all their live posts as the owner's; unblock matches a prefix; restore brings one back", async () => {
    const e = env();
    const poster = await sha256Hex(KEY_A);
    await seed(e, { id: "POST0001", key: KEY_A });
    await seed(e, { id: "POST0002", key: KEY_A });
    await seed(e, { id: "TOOKDOWN", key: KEY_A });
    await seed(e, { id: "OTHER001", poster: "cd".repeat(32) });
    await takeDown(e, "TOOKDOWN");
    await run(e, commandSql("block", "POST0001", 7));
    assert.equal(await e.COMMUNITY_DB.prepare("SELECT poster FROM blocked").first("poster"), poster);
    const removed = (await e.COMMUNITY_DB.prepare("SELECT id, removed_by FROM posts WHERE removed_at IS NOT NULL ORDER BY id").all()).results;
    assert.deepEqual(removed, [{ id: "POST0001", removed_by: "owner" }, { id: "POST0002", removed_by: "owner" }, { id: "TOOKDOWN", removed_by: "poster" }]);
    await run(e, commandSql("unblock", poster.slice(0, 12)));
    assert.equal(await e.COMMUNITY_DB.prepare("SELECT COUNT(*) AS n FROM blocked").first("n"), 0);
    await run(e, commandSql("restore", "POST0002", 8));
    assert.deepEqual(await removal(e, "POST0002"), { removed_at: null, removed_by: null });
});

test("remove, block, unblock and restore show what they hit, and nothing for an unknown id or prefix", async () => {
    const e = env();
    await seed(e, { id: "POST0001", poster: "ab".repeat(32) });
    await seed(e, { id: "POST0002" });
    assert.deepEqual(await run(e, commandSql("remove", "NOPE0001")), [[]]);
    assert.deepEqual(await run(e, commandSql("block", "NOPE0001")), [[], [], []]);
    assert.deepEqual(await run(e, commandSql("restore", "NOPE0001")), [[], [], [], [], []]);
    assert.deepEqual(await run(e, commandSql("block", "POST0001", 7)),
        [[], [{ id: "POST0001", title: "T" }], [{ poster: "abababab", at: 7, note: "post POST0001" }]]);
    assert.deepEqual(await run(e, commandSql("unblock", "0123abcd")), [[]]);
    assert.deepEqual(await run(e, commandSql("unblock", "abababab")), [[{ poster: "abababab", note: "post POST0001" }]]);
    assert.deepEqual(await run(e, commandSql("remove", "POST0001", 8)), [[]]); // still down from the block
    assert.deepEqual(await run(e, commandSql("remove", "POST0002", 8)), [[{ id: "POST0002", title: "T", poster_name: "Seed" }]]);
});

test("list shows the newest and the top posts, with hidden and removed flags", async () => {
    const e = env();
    await seed(e, { id: "POST0001", down: 3, created: Date.now() - DAY });
    const sql = commandSql("list", "10");
    const [newest] = (await e.COMMUNITY_DB.prepare(sql.split(";\n")[0]).all()).results;
    assert.equal(newest.id, "POST0001");
    assert.equal(newest.hidden, 1);
});

test("list: the top statement skips removed posts, newest says who removed each, and the third shows blocked phones", async () => {
    const e = env();
    await seed(e, { id: "LIVE0001", up: 5 });
    await seed(e, { id: "GONE0001", up: 9, key: KEY_A });
    await takeDown(e, "GONE0001");
    await seed(e, { id: "SPAM0001", poster: "cd".repeat(32) });
    await run(e, commandSql("block", "SPAM0001", 5));
    const [newest, top, blocked] = await run(e, commandSql("list", "10"));
    assert.deepEqual(top.map((r) => r.id), ["LIVE0001"]);
    assert.deepEqual(Object.fromEntries(newest.map((r) => [r.id, r.removed])), { LIVE0001: null, GONE0001: "poster", SPAM0001: "owner" });
    assert.deepEqual(blocked, [{ poster: "cdcdcdcd", at: 5, note: "post SPAM0001" }]);
});

test("every command's SQL fits one quoted --command line, with nothing left to bind", () => {
    for (const [c, a] of [["list", "20"], ["remove", "POST0001"], ["restore", "POST0001"], ["block", "POST0001"], ["unblock", "abcdef12"]])
        assert.ok(!/--|["%]|\?\d/.test(commandSql(c, a)), c);
});

test("bad arguments never reach the SQL", () => {
    assert.throws(() => commandSql("remove", "x'; DROP TABLE posts; --"));
    assert.throws(() => commandSql("block", "short"));
    assert.throws(() => commandSql("unblock", "zz"));
    assert.throws(() => commandSql("unblock", "abc")); // under 8 characters
    assert.throws(() => commandSql("unblock", "ABCDEF12")); // upper case
    assert.throws(() => commandSql("list", "0"));
    assert.throws(() => commandSql("list", "201"));
    assert.throws(() => commandSql("list", "10abc"));
    assert.throws(() => commandSql("list", "1e3"));
    assert.throws(() => commandSql("wipe", "POST0001"));
    assert.throws(() => commandSql("block", "POST0001", "POST0002")); // a second argument
});
