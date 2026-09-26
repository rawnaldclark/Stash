import { test } from "node:test";
import assert from "node:assert/strict";
import { commandSql } from "../scripts/community.mjs";
import { env } from "./fake-kv.js";
import { seed, DAY } from "./community-helpers.js";

/** Runs each statement in turn, as `d1 execute` does, and returns each one's rows. */
const run = async (e, sql) => {
    const rows = [];
    for (const s of sql.split(";\n")) rows.push((await e.COMMUNITY_DB.prepare(s).all()).results);
    return rows;
};

test("remove and restore: restore clears the removal, drops the downvotes and recounts", async () => {
    const e = env();
    await seed(e, { id: "POST0001", down: 3 });
    await e.COMMUNITY_DB.prepare("INSERT INTO votes (post_id, voter, ip_hash, value, at) VALUES ('POST0001','v1','n1',-1,1),('POST0001','v2','n2',-1,1),('POST0001','v3','n3',-1,1),('POST0001','v4','n4',1,1)").run();
    await run(e, commandSql("remove", "POST0001", 42));
    assert.equal(await e.COMMUNITY_DB.prepare("SELECT removed_at FROM posts").first("removed_at"), 42);
    await run(e, commandSql("restore", "POST0001", 43));
    const p = await e.COMMUNITY_DB.prepare("SELECT removed_at, up, down FROM posts").first();
    assert.deepEqual(p, { removed_at: null, up: 1, down: 0 });
});

test("block records the poster and removes all their live posts; unblock matches a prefix", async () => {
    const e = env();
    const poster = "ab".repeat(32);
    await seed(e, { id: "POST0001", poster });
    await seed(e, { id: "POST0002", poster });
    await seed(e, { id: "OTHER001", poster: "cd".repeat(32) });
    await run(e, commandSql("block", "POST0001", 7));
    assert.equal(await e.COMMUNITY_DB.prepare("SELECT poster FROM blocked").first("poster"), poster);
    const removed = (await e.COMMUNITY_DB.prepare("SELECT id FROM posts WHERE removed_at IS NOT NULL ORDER BY id").all()).results.map((r) => r.id);
    assert.deepEqual(removed, ["POST0001", "POST0002"]);
    await run(e, commandSql("unblock", poster.slice(0, 12)));
    assert.equal(await e.COMMUNITY_DB.prepare("SELECT COUNT(*) AS n FROM blocked").first("n"), 0);
});

test("remove, block and unblock show what they hit, and nothing for an unknown id or prefix", async () => {
    const e = env();
    await seed(e, { id: "POST0001", poster: "ab".repeat(32) });
    assert.deepEqual(await run(e, commandSql("remove", "NOPE0001")), [[]]);
    assert.deepEqual(await run(e, commandSql("block", "NOPE0001")), [[], [], []]);
    assert.deepEqual(await run(e, commandSql("block", "POST0001", 7)),
        [[], [{ id: "POST0001", title: "T" }], [{ poster: "abababab", at: 7, note: "post POST0001" }]]);
    assert.deepEqual(await run(e, commandSql("unblock", "0123abcd")), [[]]);
    assert.deepEqual(await run(e, commandSql("unblock", "abababab")), [[{ poster: "abababab", note: "post POST0001" }]]);
    assert.deepEqual(await run(e, commandSql("remove", "POST0001", 8)), [[{ id: "POST0001", title: "T", poster_name: "Seed" }]]);
});

test("list shows the newest and the top posts, with hidden and removed flags", async () => {
    const e = env();
    await seed(e, { id: "POST0001", down: 3, created: Date.now() - DAY });
    const sql = commandSql("list", "10");
    const [newest] = (await e.COMMUNITY_DB.prepare(sql.split(";\n")[0]).all()).results;
    assert.equal(newest.id, "POST0001");
    assert.equal(newest.hidden, 1);
});

test("list: the top statement skips removed posts, newest flags them, and the third shows blocked phones", async () => {
    const e = env();
    await seed(e, { id: "LIVE0001", up: 5 });
    await seed(e, { id: "GONE0001", up: 9, removed: 1 });
    await seed(e, { id: "SPAM0001", poster: "cd".repeat(32) });
    await run(e, commandSql("block", "SPAM0001", 5));
    const [newest, top, blocked] = await run(e, commandSql("list", "10"));
    assert.deepEqual(top.map((r) => r.id), ["LIVE0001"]);
    assert.equal(newest.find((r) => r.id === "GONE0001").removed, 1);
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
