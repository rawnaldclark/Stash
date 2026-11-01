/**
 * SHARE_PROXY_KEY: the web player's Worker proxies Community (and later sharing) calls, so every web listener arrives
 * with the proxy's CF-Connecting-IP. With the secret on both Workers it vouches for the listener's own address
 * (X-Stash-Proxy-Key + X-Stash-Client-IP); without the exact key, those headers change nothing.
 */
import { mock, test } from "node:test";
import assert from "node:assert/strict";
import { handle } from "../src/index.js";
import { clientAddress, ip, validIp, CLIENT_IP_HEADER, PROXY_KEY_HEADER } from "../src/http.js";
import { sha256Hex } from "../src/store.js";
import { env } from "./fake-kv.js";
import { BASE, KEY_A, seed } from "./community-helpers.js";

const SECRET = "proxy-key-for-tests-only-0123456789abcdefgh"; // 43 characters, the player's accepted alphabet
const PROXY = "198.51.100.200"; // the player Worker, as CF-Connecting-IP shows it here
const LISTENER = "203.0.113.77";
const keyN = (n) => `${n}`.padStart(43, "k");
/** Every refused key warns (count only): silenced here, and counted by the warning test. */
const warn = mock.method(console, "warn", () => {});

/** A request as the proxy (or anyone) would send it: CF-Connecting-IP plus whichever of the two headers are given. */
function request(path, { method = "GET", from = PROXY, key, client, communityKey, body } = {}) {
    const headers = { "CF-Connecting-IP": from };
    if (key !== undefined) headers[PROXY_KEY_HEADER] = key;
    if (client !== undefined) headers[CLIENT_IP_HEADER] = client;
    if (communityKey !== undefined) headers["X-Stash-Community-Key"] = communityKey;
    if (body !== undefined) headers["content-type"] = "application/json";
    return new Request(`${BASE}${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
}

/** A rate-limit binding that lets everything through and records the keys it was asked about. */
function recorder() {
    const keys = [];
    return { keys, limit: async ({ key }) => (keys.push(key), { success: true }) };
}

const vote = (e, id, value, opts) =>
    handle(request(`/v1/community/posts/${id}/vote`, { method: "PUT", body: { value }, ...opts }), e);

test("validIp takes IPv4 and IPv6 as Cloudflare writes them, and nothing else", () => {
    for (const ok of ["203.0.113.9", "0.0.0.0", "255.255.255.255", "2001:db8::1", "2001:db8:aa:bb01::1", "::1", "::",
        "2001:0db8:0000:0000:0000:0000:0000:0001", "1:2:3:4:5:6:7::", "FE80::A"]) {
        assert.equal(validIp(ok), true, ok);
    }
    for (const bad of ["", "?", "256.1.1.1", "1.2.3", "1.2.3.4.5", "01.2.3.4", " 1.2.3.4", "1.2.3.4/32", "2001:db8::/56",
        "[::1]", "fe80::1%eth0", "1::2::3", ":1::", "1:::2", "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7:8::",
        "12345::1", "g::1", "1.2.3.4::", "::1.2.3.4:5", "::256.0.0.1", "localhost", "203.0.113.9, 198.51.100.1", "a".repeat(40),
        // IPv4-in-IPv6 text: networkOf would key it whole, not by its /56.
        "::ffff:203.0.113.9", "::203.0.113.9", "1:2:3:4:5:6:1.2.3.4", "64:ff9b::203.0.113.9"]) {
        assert.equal(validIp(bad), false, bad);
    }
});

test("with no SHARE_PROXY_KEY here, the headers are ignored entirely", () => {
    const r = request("/", { key: SECRET, client: LISTENER });
    assert.equal(clientAddress(r, env()), PROXY);
    assert.equal(clientAddress(r, env({ SHARE_PROXY_KEY: "" })), PROXY);
    assert.equal(clientAddress(r, undefined), PROXY, "a caller that passes no env fails closed");
});

test("a wrong key is ignored: off by one character, shorter, longer, or empty", () => {
    const e = env({ SHARE_PROXY_KEY: SECRET });
    for (const key of [`${SECRET.slice(0, -1)}X`, SECRET.slice(0, -1), `${SECRET}x`, "", SECRET.toUpperCase()]) {
        assert.equal(clientAddress(request("/", { key, client: LISTENER }), e), PROXY, key);
    }
});

test("the right key with a missing or malformed address falls back to the caller's own", () => {
    const e = env({ SHARE_PROXY_KEY: SECRET });
    assert.equal(clientAddress(request("/", { key: SECRET }), e), PROXY);
    for (const client of ["", "not-an-ip", "203.0.113.77, 10.0.0.1", "999.0.0.1", "2001:db8::/56", "::ffff:203.0.113.77", "1:2:3:4:5:6:203.0.113.77"]) {
        assert.equal(clientAddress(request("/", { key: SECRET, client }), e), PROXY, client);
    }
});

test("the right key with a well-formed address: that address is the caller", () => {
    const e = env({ SHARE_PROXY_KEY: SECRET });
    assert.equal(clientAddress(request("/", { key: SECRET, client: LISTENER }), e), LISTENER);
    assert.equal(ip(request("/", { key: SECRET, client: LISTENER }), e), LISTENER);
    // Stray whitespace around the stored secret (a piped `wrangler secret put`) is trimmed, as the player trims its own.
    assert.equal(clientAddress(request("/", { key: SECRET, client: LISTENER }), env({ SHARE_PROXY_KEY: `${SECRET}\n` })), LISTENER);
});

test("a secret the player would refuse (under 32 characters, or outside its alphabet) is treated as unset", () => {
    for (const weak of ["short-secret", "x".repeat(31), `${"x".repeat(40)} with spaces`]) {
        const e = env({ SHARE_PROXY_KEY: weak });
        assert.equal(clientAddress(request("/", { key: weak, client: LISTENER }), e), PROXY, weak);
    }
});

test("a refused key warns with a count only, never the key; the right key or no key stays quiet", () => {
    warn.mock.resetCalls();
    const wrong = `${SECRET.slice(0, -1)}X`;
    clientAddress(request("/", { key: wrong, client: LISTENER }), env({ SHARE_PROXY_KEY: SECRET }));
    clientAddress(request("/", { key: SECRET, client: LISTENER }), env()); // a key while this Worker has none: a half-done setup
    assert.equal(warn.mock.callCount(), 2);
    for (const { arguments: args } of warn.mock.calls) {
        const line = args.join(" ");
        assert.match(line, /X-Stash-Proxy-Key doesn't match SHARE_PROXY_KEY here; counted by CF-Connecting-IP \(\d+ in this isolate\)$/);
        assert.ok(!line.includes(wrong) && !line.includes(SECRET) && !line.includes(LISTENER), line);
    }
    warn.mock.resetCalls();
    clientAddress(request("/", { key: SECRET, client: LISTENER }), env({ SHARE_PROXY_KEY: SECRET }));
    clientAddress(request("/", { client: LISTENER }), env({ SHARE_PROXY_KEY: SECRET }));
    clientAddress(request("/"), env());
    assert.equal(warn.mock.callCount(), 0);
});

test("a browser can't vouch for itself: the client header without the key is ignored, on the same paths", async () => {
    const read = recorder();
    const votes = recorder();
    const e = env({ SHARE_PROXY_KEY: SECRET, COMMUNITY_READ_RL: read, COMMUNITY_VOTE_RL: votes });
    await seed(e, { id: "POST0001", key: KEY_A });
    const browser = "192.0.2.10";
    await handle(request("/v1/community/feed", { from: browser, client: LISTENER }), e);
    // Rotating the claimed address on every vote doesn't make a fresh network either: still capped at 2.
    let last;
    for (let i = 0; i < 5; i++) {
        last = await (await vote(e, "POST0001", 1, { from: browser, client: `203.0.113.${i + 1}`, communityKey: keyN(i) })).json();
    }
    assert.equal(last.up, 2);
    assert.deepEqual(read.keys, [browser]);
    assert.deepEqual(votes.keys, Array(5).fill(browser));
});

test("right key + IPv4: the read and vote limits are keyed by the listener, not the proxy", async () => {
    const read = recorder();
    const votes = recorder();
    const e = env({ SHARE_PROXY_KEY: SECRET, COMMUNITY_READ_RL: read, COMMUNITY_VOTE_RL: votes });
    await seed(e, { id: "POST0001", key: KEY_A });
    await handle(request("/v1/community/feed", { key: SECRET, client: LISTENER }), e);
    await handle(request("/v1/community/posts/POST0001", { key: SECRET, client: "203.0.113.78" }), e);
    await vote(e, "POST0001", 1, { key: SECRET, client: LISTENER, communityKey: keyN(1) });
    await vote(e, "POST0001", 1, { key: "wrong", client: LISTENER, communityKey: keyN(2) });
    assert.deepEqual(read.keys, [LISTENER, "203.0.113.78"]);
    assert.deepEqual(votes.keys, [LISTENER, PROXY]);
});

test("right key + IPv4: each listener behind the proxy is their own network for the 2-vote cap", async () => {
    const e = env({ SHARE_PROXY_KEY: SECRET });
    await seed(e, { id: "POST0001", key: KEY_A });
    await seed(e, { id: "POST0002", key: KEY_A });
    // Five listeners through the proxy: five networks.
    let r;
    for (let i = 0; i < 5; i++) {
        r = await (await vote(e, "POST0001", 1, { key: SECRET, client: `203.0.113.${i + 1}`, communityKey: keyN(i) })).json();
    }
    assert.equal(r.up, 5);
    // Without the key, the same five are all the proxy: 2.
    for (let i = 0; i < 5; i++) {
        r = await (await vote(e, "POST0002", 1, { client: `203.0.113.${i + 1}`, communityKey: keyN(i) })).json();
    }
    assert.equal(r.up, 2);
    // The stored network is the listener's, salted and hashed as for a direct caller.
    const stored = await e.COMMUNITY_DB.prepare("SELECT ip_hash FROM votes WHERE post_id = 'POST0001' AND voter = ?1").bind(await sha256Hex(keyN(0))).first("ip_hash");
    assert.equal(stored, await sha256Hex(`test-salt|203.0.113.1`));
});

test("right key + IPv6: the listener's network is still their /56", async () => {
    const votes = recorder();
    const e = env({ SHARE_PROXY_KEY: SECRET, COMMUNITY_VOTE_RL: votes });
    await seed(e, { id: "POST0001", key: KEY_A });
    // Three hosts in 2001:db8:aa:bb00::/56 are one network: 2 count. A fourth in another /56 adds one.
    for (const [i, client] of ["2001:db8:aa:bb01::1", "2001:db8:aa:bb02::1", "2001:db8:aa:bbff::9"].entries()) {
        await vote(e, "POST0001", -1, { key: SECRET, client, communityKey: keyN(i) });
    }
    const r = await (await vote(e, "POST0001", -1, { key: SECRET, client: "2001:db8:aa:cc01::1", communityKey: keyN(9) })).json();
    assert.equal(r.down, 3);
    assert.deepEqual(votes.keys, ["2001:db8:aa:bb/56", "2001:db8:aa:bb/56", "2001:db8:aa:bb/56", "2001:db8:aa:cc/56"]);
});

test("the share routes' limits and daily budget follow the vouched listener too", async () => {
    const tracks = recorder();
    const e = env({ SHARE_PROXY_KEY: SECRET, TRACK_RL: tracks });
    const song = { t: "Never Gonna Give You Up", a: "Rick Astley" };
    assert.equal((await handle(request("/v1/tracks", { method: "POST", key: SECRET, client: LISTENER, body: song }), e)).status, 201);
    assert.deepEqual(tracks.keys, [LISTENER]);
    const network = await e.COMMUNITY_DB.prepare("SELECT network FROM share_quota").first("network");
    assert.equal(network, await sha256Hex(`test-salt|${LISTENER}`));
});
