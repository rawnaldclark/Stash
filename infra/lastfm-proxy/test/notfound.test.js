import { test, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import worker, { canonicalParams, isUnlookable } from "../src/index.js";

// A Map-backed stand-in for caches.default, and a fetch stub answering with [upstreamBody].
let store;
let upstreamCalls;
let upstreamBody;
let realFetch;
beforeEach(() => {
    store = new Map();
    upstreamCalls = [];
    upstreamBody = '{"track":{"name":"T"}}';
    globalThis.caches = {
        default: {
            match: async (req) => store.get(req.url)?.clone(),
            put: async (req, res) => void store.set(req.url, res),
        },
    };
    realFetch = globalThis.fetch;
    globalThis.fetch = async (url) => {
        upstreamCalls.push(new URL(url));
        return new Response(upstreamBody, { status: 200 });
    };
});
afterEach(() => {
    globalThis.fetch = realFetch;
    delete globalThis.caches;
});

async function get(query) {
    const pending = [];
    const res = await worker.fetch(
        new Request(`https://proxy.example/lastfm?${query}`, { headers: { "CF-Connecting-IP": "203.0.113.7" } }),
        { LASTFM_API_KEY: "K" },
        { waitUntil: (p) => pending.push(p) },
    );
    await Promise.all(pending);
    return res;
}

const lookable = (q) => !isUnlookable(canonicalParams(new URLSearchParams(q)));

test("Last.fm's not found is cached like an answer: asked again, it's a HIT with no upstream call", async () => {
    upstreamBody = '{"message":"Track not found","error":6,"links":[]}';
    const first = await get("method=track.getInfo&artist=Nobody&track=Nothing&autocorrect=1");
    assert.equal(first.headers.get("X-Stash-Cache"), "MISS");
    const again = await get("method=track.getInfo&artist=Nobody&track=Nothing&autocorrect=1");
    assert.equal(again.headers.get("X-Stash-Cache"), "HIT");
    assert.equal(JSON.parse(await again.text()).error, 6);
    assert.equal(upstreamCalls.length, 1);
});

test("an error that may not hold a minute later is passed through and not cached", async () => {
    upstreamBody = '{"message":"Service Offline","error":11}';
    await get("method=track.getInfo&artist=A&track=T");
    const again = await get("method=track.getInfo&artist=A&track=T");
    assert.equal(again.headers.get("X-Stash-Cache"), "BYPASS");
    assert.equal(upstreamCalls.length, 2);
});

test("a lookup Last.fm can't answer is told not found with no upstream call and no cache entry", async () => {
    for (const q of [
        "method=track.getInfo&artist=Unknown%20Artist&track=Always%20Remember%20Us%20This%20Way",
        "method=track.getInfo&artist=wamp,%20yung%20sammy,%202.5M%20views&track=Mudda",
        "method=track.getInfo&artist=Me&track=AUD-20240101-WA0001",
        "method=track.getTopTags&artist=Me&track=voice%20note.m4a",
        "method=artist.getTopTags&artist=%3Cunknown%3E",
    ]) {
        const res = await get(q);
        assert.equal(res.headers.get("X-Stash-Cache"), "SKIP", q);
        assert.equal(JSON.parse(await res.text()).error, 6, q);
    }
    assert.equal(upstreamCalls.length, 0);
    assert.equal(store.size, 0);
});

test("real songs and artists are still looked up", () => {
    for (const q of [
        "method=track.getInfo&artist=Sting&track=Recording%20Angel",
        "method=track.getInfo&artist=Smashing%20Pumpkins&track=1979",
        "method=track.getInfo&artist=Seedhe%20Maut,%20MC%20STAN&track=Hola%20Amigo",
        "method=artist.getSimilar&artist=The%20Views",
        "method=artist.getTopTracks&artist=Rush&track=2112",
        "method=tag.getTopTracks&tag=rock",
    ]) {
        assert.ok(lookable(q), q);
    }
});

test("a crafted name of a digit and a long run of spaces is read quickly", () => {
    const started = Date.now();
    for (const artist of ["0" + " ".repeat(20000), "0" + " ".repeat(20000) + "x", "1 ".repeat(10000)]) {
        isUnlookable(canonicalParams(new URLSearchParams({ method: "track.getinfo", artist, track: "t" })));
    }
    assert.ok(Date.now() - started < 200, `took ${Date.now() - started} ms`);
    // Still a view count where there is one.
    assert.ok(!lookable("method=track.getInfo&artist=Someone,%201.2%20k%20views&track=T"));
    assert.ok(!lookable("method=track.getInfo&artist=Someone,%20922K%20views&track=T"));
});
