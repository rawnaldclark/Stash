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
    await seed(e, { id: "RESTORAB", created: now - 3 * DAY, removed: now - 2 * HOUR }); // removed today: restorable
    await seed(e, { id: "TODAYREM", created: now - 2 * HOUR, removed: now - HOUR }); // counts for today's limit
    await seed(e, { id: "LIVEPOST", created: now - 3 * DAY });
    await voteRow(e, "EXPIRED0");
    await voteRow(e, "LIVEPOST");
    await cleanup(e, now);
    assert.deepEqual(await ids(e), ["LIVEPOST", "RESTORAB", "TODAYREM"]);
    const votes = (await e.COMMUNITY_DB.prepare("SELECT post_id FROM votes").all()).results.map((r) => r.post_id);
    assert.deepEqual(votes, ["LIVEPOST"]);
});

test("the cron handler runs cleanup through waitUntil", async () => {
    const e = env();
    await seed(e, { id: "EXPIRED0", created: Date.now() - 31 * DAY });
    const pending = [];
    await worker.scheduled({ cron: "17 4 * * *" }, e, { waitUntil: (p) => pending.push(p) });
    await Promise.all(pending);
    assert.deepEqual(await ids(e), []);
});
