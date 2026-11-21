import { test } from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";
import { cleanupStations } from "../src/stations-api.js";
import { seed, HOUR, DAY } from "./community-helpers.js";
import { seedLive, seedStation, sess } from "./stations-helpers.js";

const MIN = 60_000;
const ids = async (e) => (await e.COMMUNITY_DB.prepare("SELECT id FROM stations ORDER BY id").all()).results.map((r) => r.id);
const left = async (e, table) =>
    (await e.COMMUNITY_DB.prepare(`SELECT station_id FROM ${table} ORDER BY station_id`).all()).results.map((r) => r.station_id);
/** A vote, a heard mark, a follow and a (fresh) live row on station [id]. */
async function attach(e, id) {
    const db = e.COMMUNITY_DB;
    await db.prepare("INSERT INTO station_votes (station_id, voter, ip_hash, value, at) VALUES (?1, 'v', 'n', 1, 1)").bind(id).run();
    await db.prepare("INSERT INTO station_heard (station_id, voter, at) VALUES (?1, 'v', 1)").bind(id).run();
    await db.prepare("INSERT INTO station_follows (station_id, follower, at) VALUES (?1, 'v', 1)").bind(id).run();
    await seedLive(e, { station: id, room: `R${id}`.slice(0, 8) });
}

test("a station not on the air for 7 days goes with its votes, heard marks, follows and live row", async () => {
    const e = sess();
    const now = Date.now();
    await seedStation(e, { id: "IDLE0001", created: now - 7 * DAY - MIN }); // made 7 days ago, never live
    await seedStation(e, { id: "ALMOST01", created: now - 7 * DAY + HOUR }); // 6 days 23 hours
    await seedStation(e, { id: "LIVEDAY1", created: now - 30 * DAY, lastLive: now - DAY }); // old, but live yesterday
    await seedStation(e, { id: "OLDLIVE1", created: now - 30 * DAY, lastLive: now - 8 * DAY });
    for (const id of await ids(e)) await attach(e, id);
    await cleanupStations(e, now);
    assert.deepEqual(await ids(e), ["ALMOST01", "LIVEDAY1"]);
    for (const table of ["station_votes", "station_heard", "station_follows", "live"]) {
        assert.deepEqual(await left(e, table), ["ALMOST01", "LIVEDAY1"], table);
    }
});

test("the owner's removal stays restorable for a day, then the station goes", async () => {
    const e = sess();
    const now = Date.now();
    await seedStation(e, { id: "REMOVED1", created: now - 3 * DAY, removed: now - 2 * HOUR });
    await seedStation(e, { id: "REMOVED2", created: now - 3 * DAY, removed: now - 2 * DAY });
    // The command line stamps removed_at with the PC's clock; a clock days behind must not cut the day short. The
    // station's own last time on the air, stamped by the Worker, says it was removed only an hour ago.
    await seedStation(e, { id: "SLOWCLOK", created: now - 3 * DAY, lastLive: now - HOUR, removed: now - 2 * DAY });
    await cleanupStations(e, now);
    assert.deepEqual(await ids(e), ["REMOVED1", "SLOWCLOK"]);
});

test("directory rows whose room stopped writing are swept; fresh ones stay", async () => {
    const e = sess();
    await seedStation(e, { id: "FRESH001" });
    await seedStation(e, { id: "STALE001" });
    await seedLive(e, { station: "FRESH001", room: "ROOMFRSH", seen: 2 * MIN });
    await seedLive(e, { station: "STALE001", room: "ROOMSTAL", seen: 4 * MIN });
    await cleanupStations(e, Date.now());
    assert.deepEqual(await left(e, "live"), ["FRESH001"]);
    assert.deepEqual(await ids(e), ["FRESH001", "STALE001"], "the stations themselves stay");
});

test("the station cleanup leaves posts alone", async () => {
    const e = sess();
    await seed(e, { id: "LIVEPOST", created: Date.now() - 10 * DAY });
    await cleanupStations(e, Date.now());
    assert.equal(await e.COMMUNITY_DB.prepare("SELECT COUNT(*) AS n FROM posts").first("n"), 1);
});

test("with Sessions off the cleanup deletes nothing and reads no station table (they may not exist yet)", async () => {
    const e = sess({ SESSIONS: "0" });
    await seedStation(e, { id: "IDLE0001", created: Date.now() - 8 * DAY });
    await cleanupStations(e, Date.now());
    assert.deepEqual(await ids(e), ["IDLE0001"]);
    const bare = sess({ SESSIONS: "0" });
    bare.COMMUNITY_DB.db.exec("DROP TABLE live; DROP TABLE station_follows; DROP TABLE station_heard; DROP TABLE station_votes; DROP TABLE stations");
    await cleanupStations(bare, Date.now());
});

test("the daily cron runs the station cleanup next to the post cleanup", async () => {
    const e = sess();
    await seedStation(e, { id: "IDLE0001", created: Date.now() - 8 * DAY });
    const pending = [];
    await worker.scheduled({ cron: "17 4 * * *" }, e, { waitUntil: (p) => pending.push(p) });
    await Promise.all(pending);
    assert.deepEqual(await ids(e), []);
});
