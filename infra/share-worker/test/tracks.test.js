import { test } from "node:test";
import assert from "node:assert/strict";
import worker, { handle } from "../src/index.js";
import { candidateIds, trackIdentity } from "../src/tracks.js";
import { env } from "./fake-kv.js";

const SITE = "https://stashfm.app";
const LEGACY = "https://stash-share.rawnaldclark.workers.dev";
const song = (over = {}) => ({ t: "Never Gonna Give You Up", a: "Rick Astley", al: "Whenever You Need Somebody", ...over });
const post = (body, e, { base = SITE, headers = {} } = {}) => handle(new Request(`${base}/v1/tracks`, {
    method: "POST",
    headers: { "content-type": "application/json", "CF-Connecting-IP": "203.0.113.9", ...headers },
    body: typeof body === "string" ? body : JSON.stringify(body),
}), e);
const get = (id, e, base = SITE) => handle(new Request(`${base}/v1/tracks/${id}`), e);

test("POST /v1/tracks answers 201 with a short link on the request's host and stores the song for good", async () => {
    const e = env();
    const r = await post(song({ d: 213000, isrc: "GBARL9300135", sp: "4uLU6hMCjMI75M1A2tKUQC", yt: "dQw4w9WgXcQ" }), e);
    assert.equal(r.status, 201);
    const { id, url } = await r.json();
    assert.match(id, /^[A-Za-z0-9]{8}$/);
    assert.equal(url, `${SITE}/t/${id}`);
    const entry = e.SHARE_KV.map.get(`t:${id}`);
    assert.equal(entry.opts.expirationTtl, undefined, "song links never expire");
    const stored = JSON.parse(entry.value);
    assert.equal(stored.v, 1);
    assert.ok(stored.createdAt > 0);
    assert.deepEqual(stored.track, song({ d: 213000, isrc: "GBARL9300135", sp: "4uLU6hMCjMI75M1A2tKUQC", yt: "dQw4w9WgXcQ" }));
});

test("the same song shared twice gets the same link, 200 the second time; case, spaces and duration don't change it", async () => {
    const e = env();
    const first = await post(song({ d: 213000 }), e);
    assert.equal(first.status, 201);
    const { id } = await first.json();
    const again = await post({ t: "  never gonna give you UP ", a: "RICK ASTLEY", al: "whenever you need somebody", d: 999 }, e);
    assert.equal(again.status, 200);
    assert.deepEqual(await again.json(), { id, url: `${SITE}/t/${id}` });
    assert.equal(e.SHARE_KV.map.size, 1);
});

test("Unicode spelled two ways (composed é, or e plus an accent) is the same song", async () => {
    const e = env();
    const { id } = await (await post({ t: "Halo", a: "Beyoncé" }, e)).json();
    const again = await post({ t: "Halo", a: "Beyoncé" }, e);
    assert.equal(again.status, 200);
    assert.equal((await again.json()).id, id);
});

test("a different song gets a different link", async () => {
    const e = env();
    const ids = new Set();
    for (const s of [song(), song({ t: "Together Forever" }), song({ a: "Someone Else" }), song({ al: undefined }),
        song({ isrc: "GBARL9300135" }), song({ sp: "4uLU6hMCjMI75M1A2tKUQC" }), song({ yt: "dQw4w9WgXcQ" }),
        song({ art: "https://i.scdn.co/image/a" })]) {
        const r = await post(s, e);
        assert.equal(r.status, 201, JSON.stringify(s));
        ids.add((await r.json()).id);
    }
    assert.equal(ids.size, 8);
});

test("the id is the first 8 characters of the identity hash, and the next 8 when another song holds them", async () => {
    const e = env();
    const ids = await candidateIds(trackIdentity(song()));
    assert.equal(ids.length, 5);
    for (const id of ids) assert.match(id, /^[A-Za-z0-9]{8}$/);
    assert.equal(new Set(ids).size, 5);
    await e.SHARE_KV.put(`t:${ids[0]}`, JSON.stringify({ v: 1, track: { t: "Other", a: "Song" }, createdAt: 1 }));
    const r = await post(song(), e);
    assert.equal(r.status, 201);
    assert.equal((await r.json()).id, ids[1]);
    const again = await post(song(), e);
    assert.equal(again.status, 200);
    assert.equal((await again.json()).id, ids[1], "skips the other song's id again and finds its own");
    assert.equal(JSON.parse(e.SHARE_KV.map.get(`t:${ids[0]}`).value).track.t, "Other", "the other song is untouched");
});

test("when every candidate id holds another song the request fails with a plain 503: retrying can't help", async () => {
    const e = env();
    for (const id of await candidateIds(trackIdentity(song()))) {
        await e.SHARE_KV.put(`t:${id}`, JSON.stringify({ v: 1, track: { t: "Other", a: id }, createdAt: 1 }));
    }
    const r = await worker.fetch(new Request(`${SITE}/v1/tracks`, { method: "POST", body: JSON.stringify(song()) }), e);
    assert.equal(r.status, 503);
    assert.equal(r.headers.get("Retry-After"), null);
    assert.deepEqual(await r.json(), { error: "unavailable" });
});

test("invalid bodies are 400 and store nothing", async () => {
    const e = env();
    const bad = [
        "not json", "null", "[]", "\"song\"",
        { a: "A" }, { t: "T" }, { t: "", a: "A" }, { t: "   ", a: "A" }, { t: "T", a: "" },
        { t: 5, a: "A" }, { t: "T", a: ["A"] },
        song({ t: "x".repeat(501) }), song({ a: "y".repeat(501) }), song({ al: "z".repeat(501) }), song({ al: 5 }),
        song({ isrc: "I".repeat(21) }), song({ sp: "s".repeat(41) }), song({ yt: "y".repeat(21) }), song({ yt: 7 }),
        song({ d: 0 }), song({ d: -1 }), song({ d: 1.5 }), song({ d: "213000" }),
    ];
    for (const body of bad) {
        const r = await post(body, e);
        assert.equal(r.status, 400, JSON.stringify(body).slice(0, 80));
        assert.equal((await r.json()).error, "bad_request");
    }
    assert.equal(e.SHARE_KV.map.size, 0);
});

test("caps are inclusive: 500-character title, artist and album, 20-character ISRC, 40-character Spotify id", async () => {
    const r = await post({ t: "x".repeat(500), a: "y".repeat(500), al: "z".repeat(500), isrc: "I".repeat(20), sp: "s".repeat(40), yt: "y".repeat(20) }, env());
    assert.equal(r.status, 201);
});

test("unknown fields are ignored, nulls count as absent, and off-list art is dropped rather than rejected", async () => {
    const e = env();
    const r = await post({ ...song(), al: null, d: null, junk: "x", id: "evil", art: "https://evil.example/log.gif" }, e);
    assert.equal(r.status, 201);
    const { id } = await r.json();
    const stored = JSON.parse(e.SHARE_KV.map.get(`t:${id}`).value).track;
    assert.deepEqual(stored, { t: "Never Gonna Give You Up", a: "Rick Astley" });
    const offList = ["http://i.scdn.co/image/a", "https://i.scdn.co.evil.example/a", 42, `https://i.scdn.co/${"a".repeat(1000)}`];
    for (const [i, art] of offList.entries()) {
        const other = await post({ t: `Song ${i}`, a: "A", art }, e);
        assert.equal(other.status, 201, String(art).slice(0, 40));
        assert.equal(JSON.parse(e.SHARE_KV.map.get(`t:${(await other.json()).id}`).value).track.art, undefined, String(art).slice(0, 40));
    }
});

test("an attacker's link made first can't change the cover anyone else's share of that song gets", async () => {
    const e = env();
    const EVIL = "https://lh3.googleusercontent.com/attacker-upload";
    const attacker = await (await post(song({ art: EVIL }), e)).json();
    // A real share later: with no art, and with the sharer's own art.
    const plain = await post(song(), e);
    assert.equal(plain.status, 201, "a new link, not the attacker's");
    const plainId = (await plain.json()).id;
    const own = await post(song({ art: "https://i.scdn.co/image/real" }), e);
    assert.equal(own.status, 201);
    const ownId = (await own.json()).id;
    assert.equal(new Set([attacker.id, plainId, ownId]).size, 3);
    for (const id of [plainId, ownId]) {
        const body = JSON.stringify(await (await get(id, e)).json());
        assert.ok(!body.includes("attacker-upload"), body);
        const html = await (await handle(new Request(`${SITE}/t/${id}`), e)).text();
        assert.ok(!html.includes("attacker-upload"));
    }
    // And sharing again with other art never rewrites a stored link's cover.
    await post(song({ art: "https://i.scdn.co/image/other" }), e);
    assert.equal(JSON.parse(e.SHARE_KV.map.get(`t:${attacker.id}`).value).track.art, EVIL);
    assert.equal(JSON.parse(e.SHARE_KV.map.get(`t:${plainId}`).value).track.art, undefined);
});

test("client art is part of the song's identity: the same art gives the same link", async () => {
    const e = env();
    const art = "https://cdn-images.dzcdn.net/images/cover/2fec34e02d0ca76df05f9f533f0492f2/1000x1000-000000-80-0-0.jpg";
    const { id } = await (await post(song({ art }), e)).json();
    const again = await post(song({ art }), e);
    assert.equal(again.status, 200);
    assert.equal((await again.json()).id, id);
    assert.equal(JSON.parse(e.SHARE_KV.map.get(`t:${id}`).value).track.art, art);
});

test("art URLs that differ only in case are different covers, so they're different links", async () => {
    const e = env();
    const a = await (await post(song({ art: "https://lh3.googleusercontent.com/AbC" }), e)).json();
    const b = await post(song({ art: "https://lh3.googleusercontent.com/abc" }), e);
    assert.equal(b.status, 201);
    assert.notEqual((await b.json()).id, a.id);
});

test("client i.scdn.co art is moved to its 640 px size before it is stored or hashed", async () => {
    const e = env();
    const small = "https://i.scdn.co/image/ab67616d00001e02255e131abc1410833be95673";
    const big = "https://i.scdn.co/image/ab67616d0000b273255e131abc1410833be95673";
    const { id } = await (await post(song({ art: small }), e)).json();
    assert.equal(JSON.parse(e.SHARE_KV.map.get(`t:${id}`).value).track.art, big);
    const again = await post(song({ art: big }), e);
    assert.equal(again.status, 200, "the 300 px and 640 px URLs are one cover");
    assert.equal((await again.json()).id, id);
});

test("a body over the cap is 413; a rate-limited client gets 429 before anything is read", async () => {
    assert.equal((await post({ ...song(), junk: "x".repeat(40_000) }, env())).status, 413);
    assert.equal((await post(song(), env(), { headers: { "content-length": "999999" } })).status, 413);
    const limited = env({ TRACK_RL: { limit: async () => ({ success: false }) } });
    const r = await post(song(), limited);
    assert.equal(r.status, 429);
    assert.equal(r.headers.get("Retry-After"), "60");
    assert.deepEqual(await r.json(), { error: "rate_limited" });
    assert.equal(limited.SHARE_KV.map.size, 0);
});

test("the track rate limiter is keyed like the others (IPv4 as is, IPv6 by its /64)", async () => {
    const keys = [];
    const e = env({ TRACK_RL: { limit: async (o) => { keys.push(o); return { success: true }; } } });
    await post(song(), e);
    await post(song(), e, { headers: { "CF-Connecting-IP": "2001:db8::1" } });
    assert.deepEqual(keys, [{ key: "203.0.113.9" }, { key: "2001:db8:0:0" }]);
});

test("GET /v1/tracks/{id}: 200 with the song (absent fields omitted) and cacheable; unknown is 404", async () => {
    const e = env();
    const { id } = await (await post(song({ yt: "dQw4w9WgXcQ", art: "https://i.scdn.co/image/a" }), e)).json();
    const r = await get(id, e);
    assert.equal(r.status, 200);
    assert.equal(r.headers.get("cache-control"), "public, max-age=300");
    assert.deepEqual(await r.json(), { track: song({ yt: "dQw4w9WgXcQ", art: "https://i.scdn.co/image/a" }) });
    const missing = await get("zzzzzzzz", e);
    assert.equal(missing.status, 404);
    assert.deepEqual(await missing.json(), { error: "not_found" });
    assert.equal((await get("short", e)).status, 404, "not an id");
});

test("wrong methods on the track API are 405; HEAD routes like GET", async () => {
    const e = env();
    const { id } = await (await post(song(), e)).json();
    assert.equal((await handle(new Request(`${SITE}/v1/tracks`), e)).status, 405);
    assert.equal((await handle(new Request(`${SITE}/v1/tracks/${id}`, { method: "DELETE" }), e)).status, 405);
    assert.equal((await handle(new Request(`${SITE}/v1/tracks/${id}`, { method: "HEAD" }), e)).status, 200);
});

test("a request to workers.dev gets workers.dev links, one to stashfm.app gets stashfm.app links, for the same id", async () => {
    const e = env();
    const old = await (await post(song(), e, { base: LEGACY })).json();
    assert.equal(old.url, `${LEGACY}/t/${old.id}`);
    const now = await (await post(song(), e)).json();
    assert.equal(now.url, `${SITE}/t/${old.id}`);
    assert.equal((await get(old.id, e, LEGACY)).status, 200);
});
