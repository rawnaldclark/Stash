import { test } from "node:test";
import assert from "node:assert/strict";
import { commandSql } from "../scripts/community.mjs";
import { env } from "./fake-kv.js";
import { seed, DAY } from "./community-helpers.js";

const run = async (e, sql) => {
    for (const s of sql.split(";\n")) await e.COMMUNITY_DB.prepare(s).all();
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

test("list shows the newest and the top posts, with hidden and removed flags", async () => {
    const e = env();
    await seed(e, { id: "POST0001", down: 3, created: Date.now() - DAY });
    const sql = commandSql("list", "10");
    assert.ok(!sql.includes('"'), "no double quotes: the SQL rides in a quoted shell argument");
    const [newest] = (await e.COMMUNITY_DB.prepare(sql.split(";\n")[0]).all()).results;
    assert.equal(newest.id, "POST0001");
    assert.equal(newest.hidden, 1);
});

test("bad arguments never reach the SQL", () => {
    assert.throws(() => commandSql("remove", "x'; DROP TABLE posts; --"));
    assert.throws(() => commandSql("block", "short"));
    assert.throws(() => commandSql("unblock", "zz"));
    assert.throws(() => commandSql("unblock", "abc")); // under 8 characters
    assert.throws(() => commandSql("list", "0"));
    assert.throws(() => commandSql("wipe", "POST0001"));
});
