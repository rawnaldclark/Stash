import { test } from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";
import { cleanup } from "../src/community.js";
import { env } from "./fake-kv.js";
import { seed, HOUR, DAY } from "./community-helpers.js";

const ids = async (e) => (await e.COMMUNITY_DB.prepare("SELECT id FROM posts ORDER BY id").all()).results.map((r) => r.id);
const voteRow = (e, postId) => e.COMMUNITY_DB.prepare("INSERT INTO votes (post_id, voter, ip_hash, value, at) VALUES (?1, 'v', 'n', 1, 1)").bind(postId).run();

test("cleanup drops expired posts and long-removed ones with their votes, and keeps the rest", async () => {
    const e = env();
    const now = Date.now();
    await seed(e, { id: "EXPIRED0", created: now - 31 * DAY });
    await seed(e, { id: "OLDREMOV", created: now - 3 * DAY, removed: now - 2 * DAY });
    await seed(e, { id: "RESTORAB", created: now - 3 * DAY, removed: now - 2 * HOUR }); // the owner removed it today: restorable
    await seed(e, { id: "TODAYREM", created: now - 2 * HOUR, removed: now - HOUR, removedBy: "poster" }); // kept because its poster took it down only an hour ago; today's limit still counts it, but restore won't undo it
    await seed(e, { id: "SLOWCLOK", created: now - HOUR, removed: now - 2 * DAY }); // the CLI stamps removed_at with the PC's clock: still today's post
    await seed(e, { id: "LIVEPOST", created: now - 3 * DAY });
    for (const id of await ids(e)) await voteRow(e, id);
    await cleanup(e, now);
    assert.deepEqual(await ids(e), ["LIVEPOST", "RESTORAB", "SLOWCLOK", "TODAYREM"]);
    const votes = (await e.COMMUNITY_DB.prepare("SELECT post_id FROM votes ORDER BY post_id").all()).results.map((r) => r.post_id);
    assert.deepEqual(votes, ["LIVEPOST", "RESTORAB", "SLOWCLOK", "TODAYREM"]);
});

test("the cron handler runs cleanup through waitUntil", async () => {
    const e = env();
    await seed(e, { id: "EXPIRED0", created: Date.now() - 31 * DAY });
    const pending = [];
    await worker.scheduled({ cron: "17 4 * * *" }, e, { waitUntil: (p) => pending.push(p) });
    assert.equal(pending.length, 1); // fake-d1 is synchronous: without this, fire-and-forget passes too
    await Promise.all(pending);
    assert.deepEqual(await ids(e), []);
});
