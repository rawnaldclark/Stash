import { test } from "node:test";
import assert from "node:assert/strict";
import { fakeD1 } from "./fake-d1.js";

const station = (d, over = {}) => {
    const r = { id: "STATION1", owner: "o".repeat(64), name: "Rawn's Basement", created_at: 1, ...over };
    return d.prepare("INSERT INTO stations (id, owner, name, created_at) VALUES (?1, ?2, ?3, ?4)")
        .bind(r.id, r.owner, r.name, r.created_at).run();
};

test("0007 creates stations, its votes, heard marks, follows and the live directory, all empty", async () => {
    const d = fakeD1();
    await station(d);
    const got = await d.prepare("SELECT id, auto_live, up, down, vouched, removed_at, last_live_at, vibe, schedule FROM stations").first();
    assert.deepEqual(got, { id: "STATION1", auto_live: "off", up: 0, down: 0, vouched: 0, removed_at: null, last_live_at: null, vibe: null, schedule: null });
    for (const table of ["station_votes", "station_heard", "station_follows", "live"]) {
        assert.equal(await d.prepare(`SELECT COUNT(*) AS n FROM ${table}`).first("n"), 0, table);
    }
});

test("one station per phone: a second station with the same owner is refused", async () => {
    const d = fakeD1();
    await station(d);
    await assert.rejects(() => station(d, { id: "STATION2" }), /UNIQUE constraint failed: stations.owner/);
    await station(d, { id: "STATION2", owner: "p".repeat(64) });
});

test("auto_live is off, schedule or always, nothing else", async () => {
    const d = fakeD1();
    await station(d);
    const set = (v) => d.prepare("UPDATE stations SET auto_live = ?1").bind(v).run();
    await assert.rejects(() => set("sometimes"), /CHECK constraint failed/);
    await set("schedule");
    await set("always");
    await set("off");
});

test("removed_by is set exactly when removed_at is, and only by the owner", async () => {
    const d = fakeD1();
    await station(d);
    const set = (at, by) => d.prepare("UPDATE stations SET removed_at = ?1, removed_by = ?2").bind(at, by).run();
    await assert.rejects(() => set(1, null), /CHECK constraint failed/);
    await assert.rejects(() => set(null, "owner"), /CHECK constraint failed/);
    await assert.rejects(() => set(1, "host"), /CHECK constraint failed/);
    await set(1, "owner");
    await set(null, null);
});

test("station votes hold -1 or 1, one per phone per station", async () => {
    const d = fakeD1();
    const vote = (voter, value) => d.prepare("INSERT INTO station_votes (station_id, voter, ip_hash, value, at) VALUES ('STATION1', ?1, 'n', ?2, 1)").bind(voter, value).run();
    await vote("a", 1);
    await assert.rejects(() => vote("a", -1), /UNIQUE constraint failed/);
    await assert.rejects(() => vote("b", 0), /CHECK constraint failed/);
});

test("a live row is one per station", async () => {
    const d = fakeD1();
    const put = (room) => d.prepare("INSERT INTO live (station_id, room, started_at, seen_at) VALUES ('STATION1', ?1, 1, 1)").bind(room).run();
    await put("ROOMCODE");
    await assert.rejects(() => put("OTHERONE"), /UNIQUE constraint failed: live.station_id/);
    assert.deepEqual(await d.prepare("SELECT listeners, paused, crowd FROM live").first(), { listeners: 0, paused: 0, crowd: "[]" });
});
