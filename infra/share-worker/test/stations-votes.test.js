import { test } from "node:test";
import assert from "node:assert/strict";
import { sha256Hex } from "../src/store.js";
import { call, laggy, KEY_A, KEY_B, KEY_C } from "./community-helpers.js";
import { keyN, listenAndVote, seedStation, sess, stationHeard, stationVote } from "./stations-helpers.js";
import { STATION_RECOUNT_SQL } from "../src/stations-api.js";
import { VOTE_ROWS_PER_NETWORK } from "../src/community-shared.js";

const rows = (e, table, id = "STATION1") => e.COMMUNITY_DB.prepare(`SELECT COUNT(*) AS n FROM ${table} WHERE station_id = ?1`).bind(id).first("n");

test("after listening, a vote counts once per phone, can change, and 0 takes it back", async () => {
    const e = sess();
    await seedStation(e, { id: "STATION1", key: KEY_A });
    assert.deepEqual(await (await listenAndVote(e, "STATION1", 1, KEY_B)).json(), { up: 1, down: 0, myVote: 1 });
    assert.deepEqual(await (await stationVote(e, "STATION1", 1, KEY_B)).json(), { up: 1, down: 0, myVote: 1 });
    assert.deepEqual(await (await stationVote(e, "STATION1", -1, KEY_B)).json(), { up: 0, down: 1, myVote: -1 });
    assert.deepEqual(await (await stationVote(e, "STATION1", 0, KEY_B)).json(), { up: 0, down: 0, myVote: 0 });
    const s = (await (await call(e, "GET", "/v1/community/stations/STATION1", { key: KEY_B })).json()).station;
    assert.equal(s.myVote, 0);
    assert.equal(s.heard, true);
});

test("a vote needs 2 minutes' listening first, always (no app ever voted without it); 0 never does", async () => {
    for (const HEARD_REQUIRED of ["0", "1", undefined]) {
        const e = sess({ HEARD_REQUIRED });
        await seedStation(e, { id: "STATION1", key: KEY_A });
        for (const value of [1, -1]) {
            const r = await stationVote(e, "STATION1", value, KEY_B);
            assert.equal(r.status, 403);
            assert.deepEqual(await r.json(), { error: "listen_first" });
        }
        assert.equal(await rows(e, "station_votes"), 0);
        assert.equal((await stationVote(e, "STATION1", 0, KEY_B)).status, 200);
    }
});

test("one network counts for at most 2 votes each way, however many keys it makes", async () => {
    const e = sess();
    await seedStation(e, { id: "STATION1", key: KEY_A });
    for (let i = 0; i < 6; i++) await listenAndVote(e, "STATION1", 1, keyN(i), "203.0.113.50");
    assert.equal((await (await listenAndVote(e, "STATION1", 1, KEY_C, "198.51.100.7")).json()).up, 3);
});

test("votes at the same moment from different networks all count", async () => {
    const e = laggy(sess());
    await seedStation(e, { id: "STATION1", key: KEY_A });
    await Promise.all(["198.51.100.1", "198.51.100.2", "198.51.100.3"].map((from, i) => listenAndVote(e, "STATION1", 1, keyN(20 + i), from)));
    assert.equal((await e.COMMUNITY_DB.prepare("SELECT up FROM stations").first()).up, 3);
});

test("a vote is stored only together with its recount", async () => {
    const e = sess();
    await seedStation(e, { id: "STATION1", key: KEY_A });
    await stationHeard(e, "STATION1", KEY_B);
    await e.COMMUNITY_DB.prepare("CREATE TRIGGER no_recount BEFORE UPDATE OF up, down ON stations BEGIN SELECT RAISE(ABORT, 'recount failed'); END").run();
    await assert.rejects(stationVote(e, "STATION1", 1, KEY_B), /recount failed/);
    assert.equal(await rows(e, "station_votes"), 0);
});

test("a station deleted mid-vote is gone, and no vote row is left behind", async () => {
    const e = sess();
    await seedStation(e, { id: "STATION1", key: KEY_A });
    await stationHeard(e, "STATION1", KEY_B);
    const d1 = e.COMMUNITY_DB;
    e.COMMUNITY_DB = { ...d1, batch: async (s) => { d1.db.exec("DELETE FROM stations WHERE id = 'STATION1'"); return d1.batch(s); } };
    const r = await stationVote(e, "STATION1", 1, KEY_B);
    assert.equal(r.status, 404);
    assert.equal(await d1.prepare("SELECT COUNT(*) AS n FROM station_votes").first("n"), 0);
});

test("-3 from two networks hides a station: gone to others, no more votes", async () => {
    const e = sess();
    await seedStation(e, { id: "STATION1", key: KEY_A });
    await listenAndVote(e, "STATION1", -1, keyN(1), "203.0.113.50");
    await listenAndVote(e, "STATION1", -1, keyN(2), "203.0.113.50");
    assert.equal((await (await listenAndVote(e, "STATION1", -1, keyN(3), "198.51.100.7")).json()).down, 3);
    assert.equal((await call(e, "GET", "/v1/community/stations/STATION1", { key: KEY_B })).status, 404);
    assert.equal((await listenAndVote(e, "STATION1", 1, keyN(4), "192.0.2.1")).status, 404);
});

test("no voting on your own station, while blocked, on a gone one, with a bad value or key", async () => {
    const e = sess();
    await seedStation(e, { id: "STATION1", key: KEY_A });
    await seedStation(e, { id: "GONE0001", removed: Date.now() });
    const own = await stationVote(e, "STATION1", 1, KEY_A);
    assert.equal(own.status, 403);
    assert.deepEqual(await own.json(), { error: "own_station" });
    await e.COMMUNITY_DB.prepare("INSERT INTO blocked (poster, at) VALUES (?1, 1)").bind(await sha256Hex(KEY_C)).run();
    assert.deepEqual(await (await stationVote(e, "STATION1", 1, KEY_C)).json(), { error: "blocked" });
    for (const id of ["GONE0001", "NOSUCHID"]) assert.equal((await stationVote(e, id, 1, KEY_B)).status, 404, id);
    assert.equal((await stationVote(e, "STATION1", 2, KEY_B)).status, 400);
    assert.equal((await stationVote(e, "STATION1", 1, undefined)).status, 401);
});

test("a blocked phone's station takes no votes", async () => {
    const e = sess();
    await seedStation(e, { id: "STATION1", key: KEY_A });
    await e.COMMUNITY_DB.prepare("INSERT INTO blocked (poster, at) VALUES (?1, 1)").bind(await sha256Hex(KEY_A)).run();
    assert.equal((await listenAndVote(e, "STATION1", 1, KEY_B)).status, 404);
});

test("votes and heard marks are rate-limited per network", async () => {
    const e = sess({ COMMUNITY_VOTE_RL: { limit: async () => ({ success: false }) } });
    await seedStation(e, { id: "STATION1", key: KEY_A });
    assert.equal((await stationVote(e, "STATION1", 1, KEY_B)).status, 429);
    assert.equal((await stationHeard(e, "STATION1", KEY_B)).status, 429);
    assert.equal(await rows(e, "station_heard"), 0);
});

test("one network stores at most VOTE_ROWS_PER_NETWORK vote rows on a station; its stored voters can still change theirs", async () => {
    const e = sess();
    await seedStation(e, { id: "STATION1", key: KEY_A });
    for (let i = 0; i < VOTE_ROWS_PER_NETWORK + 5; i++) assert.equal((await listenAndVote(e, "STATION1", 1, keyN(i), "203.0.113.50")).status, 200);
    assert.equal(await rows(e, "station_votes"), VOTE_ROWS_PER_NETWORK);
    assert.deepEqual(await (await stationVote(e, "STATION1", -1, keyN(0), "203.0.113.50")).json(), { up: 2, down: 1, myVote: -1 });
});

test("the network count and the recount use station_votes_net", async () => {
    const db = sess().COMMUNITY_DB.db;
    const plan = (sql, ...args) => db.prepare(`EXPLAIN QUERY PLAN ${sql}`).all(...args).map((r) => r.detail).join(" | ");
    assert.match(plan("SELECT COUNT(*) FROM station_votes WHERE station_id = ?1 AND ip_hash = ?2", "S", "n"), /station_votes_net/);
    assert.match(plan(STATION_RECOUNT_SQL, "S"), /station_votes_net/);
});

test("heard is stored once: 204, and again changes nothing", async () => {
    const e = sess();
    await seedStation(e, { id: "STATION1", key: KEY_A });
    assert.equal((await stationHeard(e, "STATION1", KEY_B)).status, 204);
    assert.equal((await stationHeard(e, "STATION1", KEY_B)).status, 204);
    assert.deepEqual(await e.COMMUNITY_DB.prepare("SELECT station_id, voter FROM station_heard").all().then((r) => r.results),
        [{ station_id: "STATION1", voter: await sha256Hex(KEY_B) }]);
});

test("heard on your own station is 403, on a gone or hidden one 404, without a key 401, and only PUT", async () => {
    const e = sess();
    await seedStation(e, { id: "STATION1", key: KEY_A });
    await seedStation(e, { id: "HIDDEN01", down: 3 });
    assert.deepEqual(await (await stationHeard(e, "STATION1", KEY_A)).json(), { error: "own_station" });
    for (const id of ["HIDDEN01", "NOSUCHID"]) assert.equal((await stationHeard(e, id, KEY_B)).status, 404, id);
    assert.equal((await stationHeard(e, "STATION1", undefined)).status, 401);
    assert.equal((await call(e, "POST", "/v1/community/stations/STATION1/heard", { key: KEY_B, body: {} })).status, 405);
    assert.equal(await rows(e, "station_heard"), 0);
});
