import { test } from "node:test";
import assert from "node:assert/strict";
import { handle } from "../src/index.js";
import { sha256Hex } from "../src/store.js";
import { env } from "./fake-kv.js";

const BASE = "https://share.test";
const create = (e, body = { hostName: "Rawn" }) => handle(new Request(`${BASE}/v1/rooms`, {
    method: "POST", headers: { "CF-Connecting-IP": "203.0.113.9" }, body: JSON.stringify(body),
}), e);

test("creating a room returns a code, a host key and the invite link; only the key's hash is stored", async () => {
    const e = env();
    const r = await create(e);
    assert.equal(r.status, 201);
    const { code, hostKey, url } = await r.json();
    assert.match(code, /^[A-HJ-NP-Z2-9]{8}$/);
    assert.match(hostKey, /^[A-Za-z0-9_-]{43}$/);
    assert.equal(url, `${BASE}/l/${code}`);
    const stored = await e.ROOMS.rooms.get(code).ctx.storage.get("room");
    assert.equal(stored.keyHash, await sha256Hex(hostKey));
    assert.equal(stored.hostName, "Rawn");
    assert.ok(!JSON.stringify(stored).includes(hostKey));
});

test("room creation has its own rate limit", async () => {
    const e = env({ ROOM_RL: { limit: async () => ({ success: false }) } });
    assert.equal((await create(e)).status, 429);
    assert.equal((await handle(new Request(`${BASE}/v1/rooms`), env())).status, 405);
});

test("the preview shows the host, the count and whether it is full; unknown rooms are 404", async () => {
    const e = env();
    const { code } = await (await create(e)).json();
    const r = await handle(new Request(`${BASE}/v1/rooms/${code}`), e);
    assert.equal(r.status, 200);
    assert.deepEqual(await r.json(), { hostName: "Rawn", memberCount: 0, full: false });
    assert.equal((await handle(new Request(`${BASE}/v1/rooms/ZZZZZZZZ`), e)).status, 404);
    assert.equal((await handle(new Request(`${BASE}/v1/rooms/abc`), e)).status, 404, "not a room code");
    assert.equal((await handle(new Request(`${BASE}/v1/rooms/ZZZZZZ`), e)).status, 404, "6 characters is not a room code any more");
});

test("the socket route: 404 for a closed room, 426 without an upgrade, 409 when full", async () => {
    const e = env();
    const ws = (code, query = "") => handle(new Request(`${BASE}/v1/rooms/${code}/ws${query}`, { headers: { Upgrade: "websocket" } }), e);
    assert.equal((await ws("ZZZZZZZZ")).status, 404);
    const { code } = await (await create(e)).json();
    assert.equal((await handle(new Request(`${BASE}/v1/rooms/${code}/ws`), e)).status, 426);
    const room = e.ROOMS.rooms.get(code);
    for (let i = 0; i < 10; i++) await room.apply({ type: "hello", msg: { t: "hello" }, hostKeyOk: false, newId: `m${i}`, newToken: `t${i}` });
    assert.equal((await ws(code)).status, 409);
});

test("the invite page names the host (escaped) and 404s once the room is gone", async () => {
    const e = env();
    const { code } = await (await create(e, { hostName: "<b>Rawn</b>" })).json();
    const r = await handle(new Request(`${BASE}/l/${code}`), e);
    assert.equal(r.status, 200);
    const html = await r.text();
    assert.ok(html.includes("Join &lt;b&gt;Rawn&lt;/b&gt;&#39;s session in Stash"));
    assert.ok(html.includes("intent://") && html.includes("releases/latest"));
    const gone = await handle(new Request(`${BASE}/l/ZZZZZZZZ`), e);
    assert.equal(gone.status, 404);
    assert.match(await gone.text(), /has ended/);
});

test("looking a room up has its own rate limit, checked before any room is touched", async () => {
    const e = env({ JOIN_RL: { limit: async () => ({ success: false }) } });
    const touched = [];
    const get = e.ROOMS.get;
    e.ROOMS.get = (id) => { touched.push(id); return get(id); };
    const code = "K7QA2PXM";
    const api = await handle(new Request(`${BASE}/v1/rooms/${code}`), e);
    assert.equal(api.status, 429);
    assert.equal(api.headers.get("Retry-After"), "60");
    assert.deepEqual(await api.json(), { error: "rate_limited" });
    const ws = await handle(new Request(`${BASE}/v1/rooms/${code}/ws`, { headers: { Upgrade: "websocket" } }), e);
    assert.equal(ws.status, 429);
    assert.equal(ws.headers.get("Retry-After"), "60");
    const page = await handle(new Request(`${BASE}/l/${code}`), e);
    assert.equal(page.status, 429);
    assert.match(page.headers.get("content-type"), /^text\/html/);
    assert.deepEqual(touched, [], "no Durable Object was reached");
});
