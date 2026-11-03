/**
 * Who the caller is when a zone rule has removed CF-Connecting-IP. From 2026-10-07 every call on stashfm.app/* reached
 * this Worker without it, so every app user was the one caller "?": one network for Community's 2-votes-each-way cap
 * and one bucket for every per-minute limit. X-Real-IP (written by Cloudflare's edge, see src/http.js) stands in.
 */
import { mock, test } from "node:test";
import assert from "node:assert/strict";
import { handle } from "../src/index.js";
import { clientAddress, ip, networkOf, CLIENT_IP_HEADER, PROXY_KEY_HEADER } from "../src/http.js";
import { sha256Hex } from "../src/store.js";
import { env } from "./fake-kv.js";
import { BASE, KEY_A, seed } from "./community-helpers.js";

const warn = mock.method(console, "warn", () => {});
const keyN = (n) => `${n}`.padStart(43, "k");
const SECRET = "proxy-key-for-tests-only-0123456789abcdefgh";

/** A call as stashfm.app delivers it today: no CF-Connecting-IP, X-Real-IP from the edge (plus any other headers given). */
function request(path, { method = "GET", connecting, real, worker, proxyKey, client, communityKey, body } = {}) {
    const headers = {};
    if (connecting !== undefined) headers["CF-Connecting-IP"] = connecting;
    if (real !== undefined) headers["X-Real-IP"] = real;
    if (worker !== undefined) headers["CF-Worker"] = worker;
    if (proxyKey !== undefined) headers[PROXY_KEY_HEADER] = proxyKey;
    if (client !== undefined) headers[CLIENT_IP_HEADER] = client;
    if (communityKey !== undefined) headers["X-Stash-Community-Key"] = communityKey;
    if (body !== undefined) headers["content-type"] = "application/json";
    return new Request(`${BASE}${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
}

const vote = (e, id, value, opts) => handle(request(`/v1/community/posts/${id}/vote`, { method: "PUT", body: { value }, ...opts }), e);
const storedNetworks = (e) => e.COMMUNITY_DB.prepare("SELECT ip_hash FROM votes ORDER BY rowid").all().then((r) => r.results.map((v) => v.ip_hash));

// First in the file: the isolate's count of unplaced calls starts at 0 here (each test file is its own process).
test("a malformed X-Real-IP, or none, leaves the caller as \"?\", logged as a count at the 1st and 10th", () => {
    warn.mock.resetCalls();
    const malformed = ["", "not-an-ip", "203.0.113.9, 10.0.0.1", "::ffff:203.0.113.9", "1.2.3.4/32", "[::1]", "999.0.0.1", "localhost", "fe80::1%eth0"];
    for (const real of malformed) assert.equal(clientAddress(request("/", { real }), env()), "?", real);
    assert.equal(clientAddress(request("/"), env()), "?"); // the 10th
    assert.deepEqual(warn.mock.calls.map((c) => c.arguments.join(" ")), [
        'share: a call with no usable client address counts as "?" (1 in this isolate)',
        'share: a call with no usable client address counts as "?" (10 in this isolate)',
    ]);
});

test("standing in for a missing CF-Connecting-IP says so once per isolate, without the address", () => {
    warn.mock.resetCalls();
    for (const real of ["203.0.113.21", "203.0.113.22", "203.0.113.23"]) clientAddress(request("/", { real }), env());
    assert.equal(warn.mock.callCount(), 1);
    const line = warn.mock.calls[0].arguments.join(" ");
    assert.match(line, /CF-Connecting-IP is missing .*X-Real-IP/);
    assert.ok(!line.includes("203.0.113."), line);
});

test("no CF-Connecting-IP: the caller is X-Real-IP, keyed like any address (IPv4 whole, IPv6 by its /56)", () => {
    assert.equal(clientAddress(request("/", { real: "203.0.113.9" }), env()), "203.0.113.9");
    assert.equal(ip(request("/", { real: "203.0.113.9" }), env()), "203.0.113.9");
    assert.equal(ip(request("/", { real: "2001:db8:aa:bb01::1" }), env()), "2001:db8:aa:bb/56");
});

test("CF-Connecting-IP, when present, still wins", () => {
    assert.equal(clientAddress(request("/", { connecting: "198.51.100.4", real: "203.0.113.9" }), env()), "198.51.100.4");
});

test("a Worker's own fetch (CF-Worker) can set X-Real-IP, so there it's ignored", () => {
    // Another zone's Worker, or ours: Cloudflare adds CF-Worker to every Worker subrequest.
    assert.equal(clientAddress(request("/", { real: "203.0.113.9", worker: "attacker.example" }), env()), "?");
    assert.equal(clientAddress(request("/", { real: "203.0.113.9", worker: "stashfm.app" }), env()), "?");
});

test("the web player's vouched-for listener is still the caller when the zone removed CF-Connecting-IP", () => {
    const e = env({ SHARE_PROXY_KEY: SECRET });
    const proxied = { worker: "stashfm.app", proxyKey: SECRET };
    assert.equal(clientAddress(request("/", { ...proxied, client: "203.0.113.77" }), e), "203.0.113.77");
    // A wrong key or no forwarded address: the player's own call, which has no usable address of its own here.
    assert.equal(clientAddress(request("/", { ...proxied, proxyKey: `${SECRET.slice(0, -1)}X`, client: "203.0.113.77" }), e), "?");
    assert.equal(clientAddress(request("/", proxied), e), "?");
});

test("votes from three app users on stashfm.app are stored under their own networks and all count", async () => {
    const e = env();
    await seed(e, { id: "POST0001", key: KEY_A });
    const users = ["203.0.113.31", "198.51.100.32", "2001:db8:aa:bb01::33"];
    let last;
    for (const [i, real] of users.entries()) last = await (await vote(e, "POST0001", 1, { real, communityKey: keyN(i) })).json();
    // Before the fix all three were "?": one network, capped at 2.
    assert.equal(last.up, 3);
    const expected = await Promise.all(users.map((a) => sha256Hex(`${e.COMMUNITY_SALT}|${networkOf(a)}`)));
    assert.deepEqual(await storedNetworks(e), expected);
    assert.equal(new Set(expected).size, 3);
});

test("the old behaviour, for contrast: callers with no usable address all share one network and the cap", async () => {
    const e = env();
    await seed(e, { id: "POST0001", key: KEY_A });
    let last;
    for (let i = 0; i < 3; i++) last = await (await vote(e, "POST0001", 1, { communityKey: keyN(i) })).json();
    assert.equal(last.up, 2);
    const unplaced = await sha256Hex(`${e.COMMUNITY_SALT}|?`);
    assert.deepEqual(await storedNetworks(e), [unplaced, unplaced, unplaced]);
});

test("the per-minute limits are keyed by X-Real-IP's network too", async () => {
    const keys = [];
    const e = env({ COMMUNITY_READ_RL: { limit: async ({ key }) => (keys.push(key), { success: true }) } });
    await handle(request("/v1/community/feed", { real: "203.0.113.40" }), e);
    await handle(request("/v1/community/feed", { real: "2001:db8:aa:bb01::1" }), e);
    assert.deepEqual(keys, ["203.0.113.40", "2001:db8:aa:bb/56"]);
});
