import { test } from "node:test";
import assert from "node:assert/strict";
import { handle } from "../src/index.js";
import { sha256Hex } from "../src/store.js";
import { env } from "./fake-kv.js";
import { fakeSocket } from "./fake-room.js";
import { BASE, KEY_A, KEY_B, call } from "./community-helpers.js";
import { seedStation, sess } from "./stations-helpers.js";

/** POST /v1/rooms as the app sends it; [key] goes in the Community key header. */
function open(e, body, key) {
    const headers = { "CF-Connecting-IP": "203.0.113.9", "content-type": "application/json" };
    if (key !== undefined) headers["X-Stash-Community-Key"] = key;
    return handle(new Request(`${BASE}/v1/rooms`, { method: "POST", headers, body: JSON.stringify(body) }), e);
}
const refused = async (p) => { const r = await p; return [r.status, (await r.json()).error]; };
/** A fake socket's hello to [room], as an app's first frame. */
async function connect(room, hello) {
    const ws = fakeSocket();
    room.ctx.acceptWebSocket(ws);
    await room.webSocketMessage(ws, JSON.stringify({ t: "hello", ...hello }));
    return ws;
}

// ── Task 19: opening a session, and ending one from outside ───────────────────

test("opening a session needs Sessions on, a key, and the caller's own station", async () => {
    const off = env();
    await seedStation(off, { id: "STATION1", key: KEY_A });
    assert.deepEqual(await refused(open(off, { public: true, stationId: "STATION1" }, KEY_A)), [503, "sessions_off"]);
    const e = sess();
    await seedStation(e, { id: "STATION1", key: KEY_A });
    await seedStation(e, { id: "STATION2", key: KEY_B });
    for (const [key, body, want] of [
        [undefined, { public: true, stationId: "STATION1" }, [401, "bad_key"]],
        ["short", { public: true, stationId: "STATION1" }, [401, "bad_key"]],
        [KEY_A, { public: true }, [400, "bad_request"]],
        [KEY_A, { public: true, stationId: "abc" }, [400, "bad_request"]],
        [KEY_A, { public: true, stationId: 12345678 }, [400, "bad_request"]],
        [KEY_A, { public: true, stationId: "NOSUCHID" }, [404, "gone"]],
        [KEY_A, { public: true, stationId: "STATION2" }, [403, "not_yours"]],
    ]) assert.deepEqual(await refused(open(e, body, key)), want, JSON.stringify([key, body]));
    assert.equal(e.ROOMS.rooms.size, 0, "no room was made for any of them");
    const limited = sess({ ROOM_RL: { limit: async () => ({ success: false }) } });
    assert.equal((await open(limited, { public: true, stationId: "STATION1" }, KEY_A)).status, 429, "the room limit first");
});

test("a station others may not see can't go on the air: removed, hidden by votes, or its phone blocked", async () => {
    for (const [what, sql, want] of [
        ["removed", "UPDATE stations SET removed_at = 1, removed_by = 'owner' WHERE id = 'STATION1'", [404, "gone"]],
        ["hidden", "UPDATE stations SET down = 3 WHERE id = 'STATION1'", [404, "gone"]],
        ["blocked", "INSERT INTO blocked (poster, at) SELECT owner, 1 FROM stations WHERE id = 'STATION1'", [403, "blocked"]],
        ["vouched", "UPDATE stations SET down = 3, vouched = 1 WHERE id = 'STATION1'", null],
    ]) {
        const e = sess();
        await seedStation(e, { id: "STATION1", key: KEY_A });
        e.COMMUNITY_DB.db.exec(sql);
        const r = await open(e, { public: true, stationId: "STATION1" }, KEY_A);
        if (want) {
            assert.deepEqual([r.status, (await r.json()).error], want, what);
            assert.equal(e.ROOMS.rooms.size, 0, `${what}: no room made`);
        } else {
            assert.equal(r.status, 201, `${what}: votes don't hide a vouched station`);
        }
    }
});

test("a session opens on the caller's station: its link is the station's page, and its host key hosts it in v3", async () => {
    const e = sess();
    await seedStation(e, { id: "STATION1", key: KEY_A, hostName: "DJ Rawn" });
    const r = await open(e, { public: true, stationId: "STATION1" }, KEY_A);
    assert.equal(r.status, 201);
    const { code, hostKey, url } = await r.json();
    assert.match(code, /^[A-HJ-NP-Z2-9]{8}$/);
    assert.equal(url, `${BASE}/s/STATION1`);
    const room = e.ROOMS.rooms.get(code);
    const stored = await room.ctx.storage.get("room");
    assert.equal(stored.pub.stationId, "STATION1");
    assert.equal(stored.hostName, "DJ Rawn", "the station's DJ name when the app sends none");
    assert.equal(stored.keyHash, await sha256Hex(hostKey));
    assert.ok(!JSON.stringify(stored).includes(KEY_A) && !JSON.stringify(stored).includes(await sha256Hex(KEY_A)),
        "the room never holds the host's Community key, nor its hash");
    const host = await connect(room, { v: 3, hostKey, name: "Rawn" });
    const welcome = host.sent.find((m) => m.t === "welcome");
    assert.equal(welcome.state.host, welcome.memberId);
    assert.equal(welcome.state.session.station, "STATION1");
    assert.equal((await connect(room, { hostKey })).closeCode, 4426, "an app from before Sessions can't host one");
    const named = await (await open(e, { public: true, stationId: "STATION1", hostName: "  Rawn  " }, KEY_A)).json();
    assert.equal((await e.ROOMS.rooms.get(named.code).ctx.storage.get("room")).hostName, "Rawn", "the app's name first");
});

test("deleting a station on the air ends its session at once: its listeners hear station_removed", async () => {
    const e = sess();
    const id = (await (await call(e, "POST", "/v1/community/stations", { key: KEY_A, body: { name: "Late Night" } })).json()).id;
    const { code, hostKey } = await (await open(e, { public: true, stationId: id }, KEY_A)).json();
    const room = e.ROOMS.rooms.get(code);
    const host = await connect(room, { v: 3, hostKey });
    const sam = await connect(room, { v: 3, communityKey: KEY_B, name: "Sam" });
    await room.webSocketMessage(host, JSON.stringify({ t: "load", track: { t: "Nude", a: "Radiohead" } }));
    const liveRoom = () => e.COMMUNITY_DB.prepare("SELECT room FROM live WHERE station_id = ?1").bind(id).first("room");
    assert.equal(await liveRoom(), code, "on the air");
    assert.equal((await call(e, "DELETE", `/v1/community/stations/${id}`, { key: KEY_A })).status, 204);
    assert.ok(sam.sent.some((m) => m.t === "ended" && m.reason === "station_removed"));
    assert.equal(sam.closeCode, 4000);
    assert.equal(room.ctx.store.size, 0);
    assert.equal(await liveRoom(), null);
});

test("deleting a station whose room is unreachable still deletes it; the room finds out at its next write", async () => {
    const e = sess();
    await seedStation(e, { id: "STATION1", key: KEY_A });
    await e.COMMUNITY_DB.prepare("INSERT INTO live (station_id, room, listeners, started_at, seen_at) VALUES ('STATION1', 'ABCDEFGH', 0, 1, ?1)")
        .bind(Date.now()).run();
    e.ROOMS.get = () => ({ fetch: async () => { throw new Error("Durable Object reset"); } });
    const error = console.error;
    const logged = [];
    console.error = (...a) => logged.push(a.join(" "));
    try {
        assert.equal((await call(e, "DELETE", "/v1/community/stations/STATION1", { key: KEY_A })).status, 204);
    } finally {
        console.error = error;
    }
    assert.equal(await e.COMMUNITY_DB.prepare("SELECT COUNT(*) AS n FROM stations").first("n"), 0);
    assert.ok(logged.some((l) => l.includes("ABCDEFGH") && l.includes("reset")));
});

test("a room without `public: true` is a private room exactly as before, Sessions on or off", async () => {
    for (const e of [env(), sess()]) {
        const inits = [];
        const get = e.ROOMS.get;
        e.ROOMS.get = (id) => ({ fetch: async (req) => { inits.push(await req.clone().text()); return get(id).fetch(req); } });
        for (const body of [{ hostName: "Rawn" }, { hostName: "Rawn", public: false, stationId: "STATION1" }, { hostName: "Rawn", public: "yes" }]) {
            const r = await open(e, body, KEY_A);
            assert.equal(r.status, 201, JSON.stringify(body));
            const { code, url } = await r.json();
            assert.equal(url, `${BASE}/l/${code}`);
            const stored = await get(code).ctx.storage.get("room");
            assert.equal(inits.at(-1), JSON.stringify({ code, hostName: "Rawn", keyHash: stored.keyHash }), "the same /init, byte for byte");
            assert.ok(!("pub" in stored));
        }
    }
});
