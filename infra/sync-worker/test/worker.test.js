import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import worker, { spaceRoute } from "../src/index.js";
import { box, makeDevice, req, world } from "./fakes.js";
import { linkNew } from "./link.js";

const MiB = 1024 * 1024;

test("routes: the allow-list, ids only in the path, 405 with Allow, the reserved ws route", () => {
    assert.deepEqual(spaceRoute([], "GET"), { op: "get", params: {} });
    assert.deepEqual(spaceRoute(["devices", "me", "label"], "PUT"), { op: "label", params: {} });
    assert.deepEqual(spaceRoute(["devices", "me"], "DELETE"), { op: "removeDevice", params: { did: "me" } });
    assert.deepEqual(spaceRoute(["snapshot", "812", "0", "16"], "PUT"), { op: "snapshotPut", params: { upto: 812, part: 0, count: 16 } });
    assert.deepEqual(spaceRoute(["inbox", "d_12345678", "send_123", "1", "2"], "PUT").params, { to: "d_12345678", sendId: "send_123", part: 1, count: 2 });
    assert.deepEqual(spaceRoute(["slots", "config"], "POST"), { allow: "GET, PUT" });
    for (const s of [["snapshot", "1", "16", "16"], ["snapshot", "1", "0", "17"], ["log", "after", "-1"], ["log", "after", "01"], ["key", "x"], ["slots", "queue", "short"], ["nope"]]) {
        assert.equal(spaceRoute(s, s[0] === "snapshot" ? "PUT" : "GET"), null, s.join("/"));
    }
});

test("every answer is no-store and carries the server time; queries, unknown routes and wrong methods are refused", async () => {
    const w = world();
    const { phone, spaceId } = await linkNew(w);
    const cases = [
        [req("GET", `/v1/spaces/${spaceId}?x=1`, { device: phone }), 400],
        [req("GET", "/v1/nothing"), 404],
        [req("GET", "/"), 404],
        [req("GET", `/v1/spaces/${spaceId}/ws`, { device: phone }), 404],
        [req("PATCH", `/v1/spaces/${spaceId}`, { device: phone }), 405],
        [req("GET", "/v1/spaces"), 405],
        [req("GET", "/v1/pair"), 405],
        [req("GET", "/v1/spaces/short"), 404],
        [req("GET", `/v1/spaces/${spaceId}`, { device: phone }), 200],
    ];
    for (const [r, status] of cases) {
        const res = await w.fetch(r);
        assert.equal(res.status, status, r.url);
        assert.equal(res.headers.get("cache-control"), "no-store", r.url);
        assert.match(res.headers.get("x-stash-server-time"), /^\d+$/);
        if (status !== 200) assert.ok((await res.json()).error.code, r.url);
    }
    assert.equal((await w.fetch(req("PATCH", `/v1/spaces/${spaceId}`, { device: phone }))).headers.get("allow"), "GET, DELETE");
});

test("bodies: JSON only, 1 MiB at most (read no further), parsed or refused", async () => {
    const w = world();
    const { phone, spaceId } = await linkNew(w);
    const path = `/v1/spaces/${spaceId}/log`;
    assert.equal((await w.fetch(new Request(`https://sync.stashfm.app${path}`, { method: "POST", headers: { Authorization: phone.auth, "content-type": "text/plain" }, body: "{}" }))).status, 415);
    assert.equal((await w.fetch(req("POST", path, { device: phone, body: "{not json" }))).status, 400);
    const huge = JSON.stringify({ env: { e: 1, n: "A".repeat(16), c: "A".repeat(MiB + 10) } });
    const big = await w.fetch(req("POST", path, { device: phone, body: huge }));
    assert.equal(big.status, 413);
    assert.equal((await big.json()).error.code, "too_large");
    // Without a content-length the stream is cut off at the cap, not buffered whole.
    let pulled = 0;
    const stream = new ReadableStream({
        pull(c) {
            pulled += 64 * 1024;
            if (pulled > 8 * MiB) c.close();
            else c.enqueue(new Uint8Array(64 * 1024).fill(65));
        },
    });
    const chunked = new Request(`https://sync.stashfm.app${path}`, { method: "POST", headers: { Authorization: phone.auth, "content-type": "application/json" }, body: stream, duplex: "half" });
    assert.equal((await w.fetch(chunked)).status, 413);
    assert.ok(pulled < 2 * MiB, `read ${pulled} bytes`);
    assert.equal((await w.fetch(req("POST", path, { device: phone, body: { env: box(1) } }))).status, 201);
});

test("device auth: a missing or malformed header is 401 unauthorized; a removed device is 401 revoked; an unknown space is 404 gone", async () => {
    const w = world();
    const { phone, browser, spaceId } = await linkNew(w);
    const path = `/v1/spaces/${spaceId}`;
    for (const auth of [undefined, "Bearer x", `Stash-Device ${phone.id}`, `Stash-Device ${phone.id}:short`, `Stash-Device x:${phone.token}`]) {
        const res = await w.fetch(new Request(`https://sync.stashfm.app${path}`, { headers: auth ? { Authorization: auth } : {} }));
        assert.equal(res.status, 401, String(auth));
        assert.equal((await res.json()).error.code, "unauthorized");
    }
    assert.equal((await w.fetch(req("DELETE", `${path}/devices/${browser.id}`, { device: phone }))).status, 204);
    const cut = await w.fetch(req("GET", path, { device: browser, player: true }));
    assert.equal(cut.status, 401);
    assert.equal((await cut.json()).error.code, "revoked");

    const ghost = "G".repeat(22);
    const gone = await w.fetch(req("GET", `/v1/spaces/${ghost}`, { device: phone }));
    assert.equal(gone.status, 404);
    assert.equal((await gone.json()).error.code, "gone");
    assert.deepEqual(w.env.SPACES.ctxs.get(ghost).sql.tables(), [], "a probe leaves no storage behind");

    assert.equal((await w.fetch(req("DELETE", path, { device: phone }))).status, 204);
    assert.equal((await (await w.fetch(req("GET", path, { device: phone }))).json()).error.code, "gone");
});

test("the member API end to end over HTTP: log, snapshot, slots with If-Match, queue, sends, key, label", async () => {
    const w = world();
    const { phone, browser, spaceId } = await linkNew(w);
    const p = (path) => `/v1/spaces/${spaceId}${path}`;
    const asBrowser = { device: browser, player: true };
    const call = async (method, path, opts) => {
        const res = await w.fetch(req(method, p(path), opts));
        return { status: res.status, body: res.status === 204 ? null : await res.json(), res };
    };
    assert.deepEqual((await call("POST", "/log", { device: phone, body: { env: box(1) } })).body, { seq: 1, serverAt: w.clock.t });
    assert.equal((await call("GET", "/log/after/0", asBrowser)).body.entries[0].device, phone.id);
    assert.deepEqual((await call("PUT", "/snapshot/1/0/1", { ...asBrowser, body: { env: box(1) } })).body, { complete: true });
    assert.equal((await call("GET", "/snapshot/0", { device: phone })).body.uptoSeq, 1);

    const c1 = await call("PUT", "/slots/config", { device: phone, body: { env: box(1) } });
    assert.equal(c1.status, 200);
    assert.equal((await call("PUT", "/slots/config", { ...asBrowser, body: { env: box(1) } })).status, 412);
    assert.equal((await call("PUT", "/slots/config", { ...asBrowser, body: { env: box(1) }, headers: { "If-Match": String(c1.body.serverAt) } })).status, 200);

    assert.equal((await call("PUT", "/slots/now", { ...asBrowser, body: { env: box(1) } })).status, 200);
    assert.equal((await call("GET", "/slots/now", { device: phone })).body.slots[0].device, browser.id);
    assert.equal((await call("PUT", "/slots/queue", { device: phone, body: { env: box(1, 2000) } })).status, 200);
    assert.equal((await call("GET", `/slots/queue/${phone.id}`, asBrowser)).status, 200);

    assert.deepEqual((await call("PUT", `/inbox/${browser.id}/send_0001/0/1`, { device: phone, body: { env: box(1) } })).body, { complete: true });
    assert.equal((await call("GET", "/inbox", asBrowser)).body.sends[0].sendId, "send_0001");
    assert.equal((await call("GET", "/inbox/send_0001/0", asBrowser)).status, 200);
    assert.equal((await call("DELETE", "/inbox/send_0001", asBrowser)).status, 204);

    assert.equal((await call("PUT", "/devices/me/label", { device: phone, body: { labelCt: box() } })).status, 204);
    const envelopes = { [phone.id]: box(), [browser.id]: box() };
    assert.equal((await call("POST", "/rotate", { device: phone, body: { epoch: 2, envelopes, config: box(2) } })).status, 200);
    assert.deepEqual((await call("GET", "/key/2", asBrowser)).body, { epoch: 2, ct: envelopes[browser.id] });
    assert.equal((await call("POST", "/log", { device: phone, body: { env: box(1) } })).body.error.code, "epoch");
});

test("the space API is rate limited per IP (the browser's by the IP the player forwards)", async () => {
    const w = world({ limits: { api: 4 } });
    const { phone, spaceId } = await linkNew(w); // opening the code and creating the space used two
    assert.equal((await w.fetch(req("GET", `/v1/spaces/${spaceId}`, { device: phone }))).status, 200);
    assert.equal((await w.fetch(req("GET", `/v1/spaces/${spaceId}`, { device: phone }))).status, 200);
    const over = await w.fetch(req("GET", `/v1/spaces/${spaceId}`, { device: phone }));
    assert.equal(over.status, 429);
    assert.equal((await over.json()).error.code, "rate_limited");
    const viaPlayer = await w.fetch(req("GET", `/v1/spaces/${spaceId}`, { device: phone, player: true, headers: { "X-Stash-Client-IP": "198.51.100.20" } }));
    assert.equal(viaPlayer.status, 200);
});

test("a throw anywhere becomes a retryable 503", async () => {
    const w = world();
    const device = await makeDevice("p");
    w.env.SPACES = { idFromName: (n) => n, get: () => ({ call: async () => { throw new Error("storage reset"); } }) };
    const errors = [];
    const log = console.error;
    console.error = (e) => errors.push(e);
    try {
        const res = await worker.fetch(req("GET", `/v1/spaces/${"A".repeat(22)}`, { device }), w.env, {});
        assert.equal(res.status, 503);
        assert.equal(res.headers.get("retry-after"), "2");
        assert.equal(res.headers.get("cache-control"), "no-store");
    } finally {
        console.error = log;
    }
    assert.equal(errors.length, 1);
});

test("wrangler.toml: sync.stashfm.app only, no workers.dev, SQLite objects, ratelimit namespaces nobody else uses", () => {
    const toml = readFileSync(new URL("../wrangler.toml", import.meta.url), "utf8");
    assert.match(toml, /^workers_dev = false$/m);
    assert.match(toml, /pattern = "sync\.stashfm\.app", custom_domain = true/);
    assert.match(toml, /new_sqlite_classes = \["SyncSpace", "PairSlot"\]/);
    assert.doesNotMatch(toml, /PLAYER_KEY\s*=/, "the key is a secret, never in the file");
    const ids = [...toml.matchAll(/namespace_id = "(\d+)"/g)].map((m) => m[1]);
    assert.deepEqual(ids, ["2030", "2031", "2032"]);
    const share = readFileSync(new URL("../../share-worker/wrangler.toml", import.meta.url), "utf8");
    const relay = readFileSync(new URL("../../lossless-relay/wrangler.toml", import.meta.url), "utf8");
    for (const id of ids) assert.ok(!share.includes(`"${id}"`) && !relay.includes(`"${id}"`), id);
});
