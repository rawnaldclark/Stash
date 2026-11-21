import { test } from "node:test";
import assert from "node:assert/strict";
import { commandSql } from "../scripts/community.mjs";
import { sha256Hex } from "../src/store.js";
import { call, seed, KEY_A, KEY_B } from "./community-helpers.js";
import { listenAndVote, seedLive, seedStation, sess } from "./stations-helpers.js";

/** Runs each statement in turn, as `d1 execute` does, and returns each one's rows. */
const run = async (e, sql) => {
    const rows = [];
    for (const s of sql.split(";\n")) rows.push((await e.COMMUNITY_DB.prepare(s).all()).results);
    return rows;
};
const liveIds = async (e) => (await (await call(e, "GET", "/v1/community/live")).json()).sessions.map((s) => s.station.id);
const removal = (e, id = "STATION1") => e.COMMUNITY_DB.prepare("SELECT removed_at, removed_by FROM stations WHERE id = ?1").bind(id).first();
/** Three downvotes and an upvote on STATION1, each from its own network. */
const threeDownOneUp = (e) => e.COMMUNITY_DB.prepare(
    "INSERT INTO station_votes (station_id, voter, ip_hash, value, at) VALUES ('STATION1','v1','n1',-1,1),('STATION1','v2','n2',-1,1),('STATION1','v3','n3',-1,1),('STATION1','v4','n4',1,1)",
).run();

test("station-remove takes a station off the air and out of every list, and says what it hit", async () => {
    const e = sess();
    await seedStation(e, { id: "STATION1", name: "Rawn's Basement", hostName: "Rawn" });
    await seedLive(e, { station: "STATION1", room: "ROOMCODE" });
    assert.deepEqual(await liveIds(e), ["STATION1"]);
    const shown = await run(e, commandSql("station-remove", "STATION1", 42));
    assert.deepEqual(shown, [[{ id: "STATION1", name: "Rawn's Basement", host_name: "Rawn" }], [{ room: "ROOMCODE" }]]);
    assert.deepEqual(await removal(e), { removed_at: 42, removed_by: "owner" });
    assert.deepEqual(await liveIds(e), []);
    assert.equal((await call(e, "GET", "/v1/community/stations/STATION1")).status, 404);
    assert.deepEqual(await run(e, commandSql("station-remove", "STATION1", 43)), [[], []], "again: nothing to hit");
    assert.deepEqual(await removal(e), { removed_at: 42, removed_by: "owner" }, "the first removal's time stands");
});

test("station-restore undoes a removal, drops the downvotes, recounts and vouches for the station", async () => {
    const e = sess();
    await seedStation(e, { id: "STATION1", down: 3 });
    await threeDownOneUp(e);
    await run(e, commandSql("station-remove", "STATION1", 42));
    const shown = await run(e, commandSql("station-restore", "STATION1", 43));
    assert.deepEqual(shown.at(-1), [{ id: "STATION1", up: 1, down: 0, removed: null, vouched: 1 }]);
    assert.deepEqual(await removal(e), { removed_at: null, removed_by: null });
});

test("station-restore of a station hidden by votes, never removed: it shows again and votes can't hide it", async () => {
    const e = sess();
    await seedStation(e, { id: "STATION1", key: KEY_A, down: 3 });
    await threeDownOneUp(e);
    await seedLive(e, { station: "STATION1" });
    assert.deepEqual(await liveIds(e), []);
    await run(e, commandSql("station-restore", "STATION1", 43));
    assert.deepEqual(await liveIds(e), ["STATION1"]);
    for (const [i, from] of ["203.0.113.50", "203.0.113.50", "198.51.100.7"].entries()) {
        await listenAndVote(e, "STATION1", -1, `${i}`.padStart(43, "k"), from);
    }
    assert.deepEqual(await liveIds(e), ["STATION1"], "vouched: -3 no longer hides it");
});

test("station-block blocks the station's phone, takes the station off the air and removes its live posts", async () => {
    const e = sess();
    const owner = await sha256Hex(KEY_A);
    await seedStation(e, { id: "STATION1", key: KEY_A, name: "Spam FM" });
    await seedStation(e, { id: "OTHER001" });
    await seedLive(e, { station: "STATION1", room: "ROOMSPAM" });
    await seed(e, { id: "POST0001", key: KEY_A });
    await seed(e, { id: "POST0002", poster: "cd".repeat(32) });
    const shown = await run(e, commandSql("station-block", "STATION1", 7));
    assert.deepEqual(shown, [
        [],
        [{ id: "STATION1", name: "Spam FM" }],
        [{ id: "POST0001", title: "T" }],
        [{ room: "ROOMSPAM" }],
        [{ poster: owner.slice(0, 8), at: 7, note: "station STATION1" }],
    ]);
    assert.deepEqual(await removal(e), { removed_at: 7, removed_by: "owner" });
    assert.equal((await e.COMMUNITY_DB.prepare("SELECT removed_by FROM posts WHERE id = 'POST0002'").first()).removed_by, null);
    // The phone can't go live, vote or post again.
    assert.deepEqual(await (await call(e, "POST", "/v1/community/stations", { key: KEY_A, body: { name: "Back" } })).json(), { error: "blocked" });
    assert.deepEqual(await (await listenAndVote(e, "OTHER001", 1, KEY_A)).json(), { error: "blocked" });
    // unblock (the post command, by prefix) lifts it.
    await run(e, commandSql("unblock", owner.slice(0, 8)));
    assert.equal(await e.COMMUNITY_DB.prepare("SELECT COUNT(*) AS n FROM blocked").first("n"), 0);
});

test("station-list: recent stations with their standing, and the sessions on the air now", async () => {
    const e = sess();
    const now = Date.now();
    await seedStation(e, { id: "STATION1", name: "Maya's Desk Radio", owner: "ab".repeat(32), up: 1, down: 4, created: now - 3_600_000, lastLive: now - 60_000 });
    await seedStation(e, { id: "STATION2", name: "Quiet", created: now - 7_200_000, removed: now - 1000 });
    await seedStation(e, { id: "STATION3", name: "Big" });
    await seedLive(e, { station: "STATION1", room: "ROOMMAYA", listeners: 12 });
    await seedLive(e, { station: "STATION3", room: "ROOMBIGG", listeners: 40 });
    await seedLive(e, { station: "STATION2", room: "ROOMOLDD", listeners: 99, seen: 4 * 60_000 });
    await e.COMMUNITY_DB.prepare("INSERT INTO station_follows (station_id, follower, at) VALUES ('STATION1', 'f', 1)").run();
    const [recent, onAir] = await run(e, commandSql("station-list", "10", now));
    const maya = recent.find((r) => r.id === "STATION1");
    assert.deepEqual(
        [maya.name, maya.hidden, maya.removed, maya.listeners, maya.followers, maya.owner, maya.since_live_h],
        ["Maya's Desk Radio", 1, null, 12, 1, "abababab", 0],
    );
    assert.equal(recent.find((r) => r.id === "STATION2").removed, "owner");
    assert.equal(recent.find((r) => r.id === "STATION2").listeners, null, "a stale row isn't on the air");
    assert.deepEqual(onAir.map((r) => [r.id, r.room, r.listeners]), [["STATION3", "ROOMBIGG", 40], ["STATION1", "ROOMMAYA", 12]]);
});

test("unknown station ids hit nothing", async () => {
    const e = sess();
    assert.deepEqual(await run(e, commandSql("station-remove", "NOPE0001")), [[], []]);
    assert.deepEqual(await run(e, commandSql("station-block", "NOPE0001")), [[], [], [], [], []]);
    assert.deepEqual(await run(e, commandSql("station-restore", "NOPE0001")), [[], [], [], [], []]);
});

test("every station command's SQL fits one quoted --command line, with nothing left to bind", () => {
    for (const [c, a] of [["station-list", "20"], ["station-remove", "STATION1"], ["station-restore", "STATION1"], ["station-block", "STATION1"]]) {
        assert.ok(!/--|["%]|\?\d/.test(commandSql(c, a)), c);
    }
});

test("bad station arguments never reach the SQL", () => {
    assert.throws(() => commandSql("station-remove", "x'; DROP TABLE stations; --"));
    assert.throws(() => commandSql("station-block", "short"));
    assert.throws(() => commandSql("station-restore"));
    assert.throws(() => commandSql("station-list", "0"));
    assert.throws(() => commandSql("station-list", "201"));
    assert.throws(() => commandSql("station-remove", "STATION1", "STATION2")); // a second argument
    assert.throws(() => commandSql("station", "list")); // the spaced form isn't a command
});
