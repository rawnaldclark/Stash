import { test } from "node:test";
import assert from "node:assert/strict";
import worker, { handle, limitKey } from "../src/index.js";
import { newId } from "../src/store.js";
import { env } from "./fake-kv.js";

const BASE = "https://share.test";
const KEY = "k".repeat(43);
const doc = (over = {}) => ({ v: 1, name: "Ambient", tracks: [{ t: "T", a: "A" }], ...over });
const req = (method, path, { body, key, headers = {} } = {}) => new Request(`${BASE}${path}`, {
    method,
    headers: { "content-type": "application/json", "CF-Connecting-IP": "203.0.113.9", ...(key ? { "X-Stash-Edit-Key": key } : {}), ...headers },
    body: body ? JSON.stringify(body) : undefined,
});
async function created(e, d = doc()) {
    return (await (await handle(req("POST", "/v1/mixes", { body: { doc: d, editKey: KEY } }), e)).json()).id;
}

test("rate limiter is keyed on the client IP", async () => {
    const keys = [];
    const e = env({ CREATE_RL: { limit: async (o) => { keys.push(o); return { success: true }; } } });
    await created(e);
    assert.deepEqual(keys, [{ key: "203.0.113.9" }]);
});

test("IPv6 keys on the /64, expanding ::; IPv4 unchanged", () => {
    assert.equal(limitKey("2001:db8::1"), "2001:db8:0:0");
    assert.equal(limitKey("2001:db8:85a3:1234:5678:8a2e:370:7334"), "2001:db8:85a3:1234");
    assert.equal(limitKey("::1"), "0:0:0:0");
    assert.equal(limitKey("203.0.113.9"), "203.0.113.9");
});

test("newId is always 8 alphanumerics", () => {
    for (let i = 0; i < 2000; i++) assert.match(newId(), /^[A-Za-z0-9]{8}$/);
});

test("PUT takes the higher of the stored and base version", async () => {
    const e = env(); const id = await created(e);
    const r = await handle(req("PUT", `/v1/mixes/${id}`, { body: { doc: doc(), baseVersion: 5 }, key: KEY }), e);
    assert.deepEqual(await r.json(), { version: 6 });
    const bad = await handle(req("PUT", `/v1/mixes/${id}`, { body: { doc: doc(), baseVersion: -3 }, key: KEY }), e);
    assert.deepEqual(await bad.json(), { version: 7 });
});

test("PUT keeps the stored keyHash", async () => {
    const e = env(); const id = await created(e);
    const before = (await e.SHARE_KV.get(`mix:${id}`, "json")).keyHash;
    await handle(req("PUT", `/v1/mixes/${id}`, { body: { doc: doc(), editKey: "x".repeat(43) }, key: KEY }), e);
    assert.equal((await e.SHARE_KV.get(`mix:${id}`, "json")).keyHash, before);
});

test("PUT with an oversized content-length is 413", async () => {
    const e = env(); const id = await created(e);
    const r = await handle(req("PUT", `/v1/mixes/${id}`, { body: { doc: doc() }, key: KEY, headers: { "content-length": "2000000" } }), e);
    assert.equal(r.status, 413);
});

test("only known fields are stored; server fields win", async () => {
    const e = env();
    const id = await created(e, doc({ id: "evil", version: 999, extra: "x", tracks: [{ t: "T", a: "A", junk: 1, yt: "abc" }] }));
    const stored = (await e.SHARE_KV.get(`mix:${id}`, "json")).doc;
    assert.equal(stored.id, id);
    assert.equal(stored.version, 1);
    assert.ok(!("extra" in stored));
    assert.deepEqual(stored.tracks, [{ t: "T", a: "A", yt: "abc" }]);
});

test("DELETE: already deleted is 410, no key is 403, rate-limited is 429", async () => {
    const e = env(); const id = await created(e);
    assert.equal((await handle(req("DELETE", `/v1/mixes/${id}`), e)).status, 403);
    assert.equal((await handle(req("DELETE", `/v1/mixes/${id}`, { key: KEY }), e)).status, 204);
    assert.equal((await handle(req("DELETE", `/v1/mixes/${id}`, { key: KEY }), e)).status, 410);
    const f = env(); const id2 = await created(f);
    f.WRITE_RL = { limit: async () => ({ success: false }) };
    assert.equal((await handle(req("DELETE", `/v1/mixes/${id2}`, { key: KEY }), f)).status, 429);
});

test("GET /v1/mixes is 405; HEAD routes like GET", async () => {
    assert.equal((await handle(req("GET", "/v1/mixes"), env())).status, 405);
    const e = env(); const id = await created(e);
    assert.equal((await handle(req("HEAD", `/v1/mixes/${id}`), e)).status, 200);
    assert.equal((await handle(req("HEAD", `/m/${id}`), e)).status, 200);
});

test("a KV failure becomes a 503 JSON with Retry-After", async () => {
    const e = env();
    e.SHARE_KV.put = async () => { throw new Error("429 Too Many Requests"); };
    const r = await worker.fetch(req("POST", "/v1/mixes", { body: { doc: doc(), editKey: KEY } }), e);
    assert.equal(r.status, 503);
    assert.equal(r.headers.get("Retry-After"), "2");
    assert.deepEqual(await r.json(), { error: "unavailable" });
});

test("HTML pages carry CSP and nosniff", async () => {
    const e = env(); const id = await created(e);
    const r = await handle(req("GET", `/m/${id}`), e);
    assert.equal(r.headers.get("content-security-policy"), "default-src 'none'; style-src 'unsafe-inline'; img-src https:");
    assert.equal(r.headers.get("x-content-type-options"), "nosniff");
});

test("track page caps values at 500 chars and drops the dot with no artist", async () => {
    const long = await (await handle(req("GET", `/t?t=${"x".repeat(900)}&a=${"y".repeat(900)}`), env())).text();
    assert.ok(long.includes(`<h1>${"x".repeat(500)}</h1>`));
    assert.ok(long.includes(`<p class="artist">${"y".repeat(500)}</p>`));
    const solo = await (await handle(req("GET", "/t?t=Song"), env())).text();
    assert.match(solo, /<title>Song<\/title>/);
});

test("message pages offer only Get Stash", async () => {
    const html = await (await handle(req("GET", "/m/zzzzzzzz"), env())).text();
    assert.ok(!html.includes("Open in Stash"));
    assert.ok(html.includes("Get Stash"));
});
