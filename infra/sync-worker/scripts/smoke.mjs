#!/usr/bin/env node
/**
 * End-to-end smoke test against a running stash-sync: links a fake phone and browser, uses every kind of route once,
 * removes the browser, then deletes the space (it leaves nothing behind but an expired pairing slot).
 *
 *   PLAYER_KEY=… node scripts/smoke.mjs                         # wrangler dev on 127.0.0.1:8795
 *   PLAYER_KEY=… node scripts/smoke.mjs https://sync.stashfm.app # after a deploy
 *
 * The payloads are random bytes shaped like the clients' envelopes: the server can't tell, which is the point.
 */
import { randomBytes, createHash, webcrypto } from "node:crypto";

const ORIGIN = (process.argv[2] ?? "http://127.0.0.1:8795").replace(/\/$/, "");
const KEY = process.env.PLAYER_KEY;
if (!KEY) {
    console.error("Set PLAYER_KEY (the value both Workers hold) in the environment.");
    process.exit(2);
}

const b64 = (bytes) => Buffer.from(bytes).toString("base64url");
const box = (e = 0, n = 48, p) => ({ e, n: b64(randomBytes(12)), c: b64(randomBytes(n)), ...(p ? { p } : {}) });

async function device(prefix) {
    const tokenBytes = randomBytes(32);
    const token = b64(tokenBytes);
    const pair = await webcrypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"]);
    const pub = b64(new Uint8Array(await webcrypto.subtle.exportKey("raw", pair.publicKey)));
    const id = `${prefix}_${b64(randomBytes(9))}`;
    return { id, token, pub, record: { id, tokenHash: createHash("sha256").update(tokenBytes).digest("base64url"), pub, labelCt: box() } };
}

let failures = 0;
async function call(method, path, { body, dev, player = false, headers = {} } = {}) {
    const h = { ...headers };
    if (player) h["X-Stash-Player-Key"] = KEY;
    if (dev) h.Authorization = `Stash-Device ${dev.id}:${dev.token}`;
    if (body !== undefined) h["content-type"] = "application/json";
    const res = await fetch(ORIGIN + path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body) });
    const text = await res.text();
    return { status: res.status, body: text ? JSON.parse(text) : null, headers: res.headers };
}
function expect(label, r, status) {
    const good = r.status === status && r.headers.get("cache-control") === "no-store";
    if (!good) failures++;
    console.log(`${good ? "ok  " : "FAIL"} ${label}: ${r.status}${good ? "" : ` (wanted ${status}) ${JSON.stringify(r.body)}`}`);
    return r;
}

const phone = await device("p");
const browser = await device("w");

const open = expect("open a code (player)", await call("POST", "/v1/pair", { player: true, body: { device: browser.record }, headers: { "X-Stash-Session": `smoke_${b64(randomBytes(9))}` } }), 201);
const pairId = open.body?.pairId;
expect("open a code without the player key", await call("POST", "/v1/pair", { body: { device: browser.record } }), 403);
expect("phone reads the label", await call("GET", `/v1/pair/${pairId}/label`), 200);
expect("phone answers", await call("POST", `/v1/pair/${pairId}/answer`, { body: { phonePub: phone.pub, ct: box(), device: phone.record } }), 201);
expect("a second answer", await call("POST", `/v1/pair/${pairId}/answer`, { body: { phonePub: phone.pub, ct: box(), device: phone.record } }), 409);
expect("browser polls (player)", await call("GET", `/v1/pair/${pairId}`, { player: true, dev: browser }), 200);
const spaceId = `s_${b64(randomBytes(16))}`;
const labels = { [phone.id]: box(1), [browser.id]: box(1) };
expect("the phone can't create before the browser confirms", await call("POST", "/v1/spaces", { dev: phone, body: { pairId, spaceId, labels } }), 409);
expect("browser replies once its user confirmed (player)", await call("POST", `/v1/pair/${pairId}/reply`, { player: true, dev: browser, body: { ct: box() } }), 201);
expect("phone creates the space", await call("POST", "/v1/spaces", { dev: phone, body: { pairId, spaceId, labels } }), 201);

const s = (p = "") => `/v1/spaces/${spaceId}${p}`;
const info = expect("phone opens the space", await call("GET", s(), { dev: phone }), 200);
console.log(`     devices: ${info.body?.devices?.length}, epoch ${info.body?.epoch}, server time ${info.body?.serverTime}`);
expect("browser opens it (player)", await call("GET", s(), { dev: browser, player: true }), 200);
expect("a query string is refused", await call("GET", s() + "?x=1", { dev: phone }), 400);
expect("log: append", await call("POST", s("/log"), { dev: phone, body: { env: box(1) } }), 201);
expect("log: read", await call("GET", s("/log/after/0"), { dev: browser, player: true }), 200);
expect("snapshot: one part", await call("PUT", s("/snapshot/1/0/1"), { dev: phone, body: { env: box(1) } }), 200);
expect("now: publish", await call("PUT", s("/slots/now"), { dev: browser, player: true, body: { env: box(1) } }), 200);
expect("now: read all", await call("GET", s("/slots/now"), { dev: phone }), 200);
const cfg = expect("config: first write", await call("PUT", s("/slots/config"), { dev: phone, body: { env: box(1) } }), 200);
expect("config: blind overwrite", await call("PUT", s("/slots/config"), { dev: browser, player: true, body: { env: box(1) } }), 412);
expect("config: with If-Match", await call("PUT", s("/slots/config"), { dev: browser, player: true, body: { env: box(1) }, headers: { "If-Match": String(cfg.body?.serverAt) } }), 200);
expect("send: one part", await call("PUT", s(`/inbox/${browser.id}/smoke_send/0/1`), { dev: phone, body: { env: box(1) } }), 200);
expect("send: listed", await call("GET", s("/inbox"), { dev: browser, player: true }), 200);
expect("rotate to epoch 2", await call("POST", s("/rotate"), { dev: phone, body: { epoch: 2, envelopes: { [phone.id]: box(2, 64, phone.pub), [browser.id]: box(2, 64, browser.pub) }, config: box(2) } }), 200);
expect("key for epoch 2", await call("GET", s("/key/2"), { dev: browser, player: true }), 200);
expect("no batches until a snapshot under the new key", await call("POST", s("/log"), { dev: phone, body: { env: box(2) } }), 409);
expect("snapshot under the new key", await call("PUT", s("/snapshot/1/0/1"), { dev: phone, body: { env: box(2) } }), 200);
expect("log: append again", await call("POST", s("/log"), { dev: phone, body: { env: box(2) } }), 201);
expect("a browser token around the player", await call("GET", s(), { dev: browser }), 403);
expect("remove the browser", await call("DELETE", s(`/devices/${browser.id}`), { dev: phone }), 204);
expect("the browser is cut off", await call("GET", s(), { dev: browser, player: true }), 401);
expect("unlink everything", await call("DELETE", s(), { dev: phone }), 204);
expect("the space is gone (answered like a removed device)", await call("GET", s(), { dev: phone }), 401);

console.log(failures ? `\n${failures} check(s) failed` : "\nall checks passed");
process.exit(failures ? 1 : 0);
