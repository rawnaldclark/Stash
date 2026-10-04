import { test } from "node:test";
import assert from "node:assert/strict";
import { handle } from "../src/index.js";
import { lookUpArt } from "../src/art.js";
import { env } from "./fake-kv.js";

const SITE = "https://stashfm.app";
const APP = "https://stash-share.rawnaldclark.workers.dev"; // the host every app version's manifest knows
const SP = "4uLU6hMCjMI75M1A2tKUQC";
const YT = "dQw4w9WgXcQ";
const ISRC = "GBARL9300135";
const OEMBED_THUMB = "https://image-cdn-ak.spotifycdn.com/image/ab67616d00001e02255e131abc1410833be95673";
const SPOTIFY_640 = "https://i.scdn.co/image/ab67616d0000b273255e131abc1410833be95673";
const DEEZER_XL = "https://cdn-images.dzcdn.net/images/cover/2fec34e02d0ca76df05f9f533f0492f2/1000x1000-000000-80-0-0.jpg";
const HQ = `https://i.ytimg.com/vi/${YT}/hqdefault.jpg`;
const HOUR = 3_600_000;
const song = (over = {}) => ({ t: "Never Gonna Give You Up", a: "Rick Astley", al: "Whenever You Need Somebody", ...over });

/** The content of one `<meta property|name="key">` tag, or null. */
const meta = (html, key) => new RegExp(`<meta (?:property|name)="${key}" content="([^"]*)">`).exec(html)?.[1] ?? null;
const canonical = (html) => /<link rel="canonical" href="([^"]*)">/.exec(html)?.[1] ?? null;
const amp = (s) => s.replace(/&/g, "&amp;");

const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
/** Replaces global fetch for one test. [routes] maps a URL prefix to a handler; anything else throws (no real network). */
function stubFetch(t, routes) {
    const real = globalThis.fetch;
    const calls = [];
    globalThis.fetch = async (input, init) => {
        const url = typeof input === "string" ? input : input.url;
        calls.push(url);
        const hit = Object.entries(routes).find(([prefix]) => url.startsWith(prefix));
        if (!hit) throw new Error(`unexpected fetch ${url}`);
        return hit[1](url, init);
    };
    t.after(() => { globalThis.fetch = real; });
    return calls;
}
const OEMBED = "https://open.spotify.com/oembed";
const DEEZER = "https://api.deezer.com/track/isrc:";
const oembedOk = () => json({ type: "rich", title: "Never Gonna Give You Up", thumbnail_url: OEMBED_THUMB });
const deezerOk = () => json({ id: 1, isrc: ISRC, album: { id: 2, cover_xl: DEEZER_XL } });
const deezerWith = (cover_xl) => () => json({ id: 1, album: { cover_xl } });
const gone = () => new Response(null, { status: 404 });

/** A Workers-style `caches.default` for one test. */
function fakeCache(t) {
    const store = new Map();
    const cache = {
        store,
        async match(req) { return store.get(req.url)?.clone(); },
        async put(req, res) { store.set(req.url, res.clone()); },
    };
    globalThis.caches = { default: cache };
    t.after(() => { delete globalThis.caches; });
    return cache;
}

async function share(e, track) {
    const r = await handle(new Request(`${SITE}/v1/tracks`, { method: "POST", body: JSON.stringify(track) }), e);
    return (await r.json()).id;
}
const page = async (e, path, { base = SITE, ip = "203.0.113.9" } = {}) => {
    const r = await handle(new Request(`${base}${path}`, { headers: { "CF-Connecting-IP": ip } }), e);
    return { status: r.status, html: await r.text(), headers: r.headers };
};
const record = (e, id) => JSON.parse(e.SHARE_KV.map.get(`t:${id}`).value);
const putRecord = (e, id, rec) => e.SHARE_KV.put(`t:${id}`, JSON.stringify(rec));

test("short song page: music.song preview tags, the sharer's art as the image, and the short link as its URL", async (t) => {
    const calls = stubFetch(t, {});
    const e = env();
    const id = await share(e, song({ sp: SP, yt: YT, art: "https://i.scdn.co/image/stored" }));
    const { status, html, headers } = await page(e, `/t/${id}`);
    assert.equal(status, 200);
    assert.match(headers.get("content-type"), /^text\/html/);
    assert.equal(meta(html, "og:site_name"), "Stash");
    assert.equal(meta(html, "og:type"), "music.song");
    assert.equal(meta(html, "og:url"), `${SITE}/t/${id}`);
    assert.equal(canonical(html), `${SITE}/t/${id}`);
    assert.equal(meta(html, "og:title"), "Never Gonna Give You Up");
    assert.equal(meta(html, "og:description"), "Rick Astley · Whenever You Need Somebody");
    assert.equal(meta(html, "og:image"), "https://i.scdn.co/image/stored");
    assert.equal(meta(html, "og:image:width"), null, "a client's cover has no known size");
    assert.ok(meta(html, "og:image:alt").includes("Never Gonna Give You Up"));
    assert.equal(meta(html, "twitter:card"), "summary_large_image");
    assert.equal(meta(html, "twitter:title"), "Never Gonna Give You Up");
    assert.equal(meta(html, "twitter:description"), "Rick Astley · Whenever You Need Somebody");
    assert.equal(meta(html, "twitter:image"), "https://i.scdn.co/image/stored");
    assert.match(html, /<title>Never Gonna Give You Up · Rick Astley<\/title>/);
    // The body a friend without Stash sees.
    assert.ok(html.includes('class="cover" src="https://i.scdn.co/image/stored"'));
    assert.ok(html.includes("<h1>Never Gonna Give You Up</h1>"));
    assert.ok(html.includes("Rick Astley") && html.includes("Whenever You Need Somebody"));
    assert.ok(html.includes(`href="https://open.spotify.com/track/${SP}">Listen on Spotify</a>`));
    assert.ok(html.includes(`href="https://music.youtube.com/watch?v=${YT}">Listen on YouTube Music</a>`));
    assert.ok(html.includes("releases/latest\">Get Stash</a>"));
    assert.deepEqual(calls, [], "the sharer's art needs no lookup");
});

test("Open in Stash on a short link opens the long workers.dev link today's app understands, falling back to the page", async (t) => {
    stubFetch(t, {});
    const e = env();
    const id = await share(e, song({ d: 213000, sp: SP, yt: YT, art: "https://i.scdn.co/image/stored" }));
    const { html } = await page(e, `/t/${id}`);
    const app = `${APP}/t?t=Never+Gonna+Give+You+Up&a=Rick+Astley&al=Whenever+You+Need+Somebody&d=213000&sp=${SP}&yt=${YT}`;
    const intent = `intent://${app.slice("https://".length)}#Intent;scheme=https;package=com.stash.app;` +
        `S.browser_fallback_url=${encodeURIComponent(`${SITE}/t/${id}`)};end`;
    assert.ok(html.includes(`href="${amp(intent)}"`), html);
});

test("without an album the description is just the artist; without ids there are no listen buttons", async (t) => {
    stubFetch(t, {});
    const e = env();
    const id = await share(e, { t: "Song", a: "Artist" });
    const { html } = await page(e, `/t/${id}`);
    assert.equal(meta(html, "og:description"), "Artist");
    assert.ok(!html.includes("Listen on"));
    assert.equal(meta(html, "og:image"), null);
    assert.equal(meta(html, "twitter:image"), null);
    assert.equal(meta(html, "twitter:card"), "summary_large_image");
});

test("an unknown short link is a 404 page that still offers Get Stash", async () => {
    const { status, html } = await page(env(), "/t/zzzzzzzz");
    assert.equal(status, 404);
    assert.match(html, /Song not found/);
    assert.ok(html.includes("Get Stash") && !html.includes("Open in Stash"));
    assert.equal(meta(html, "og:url"), `${SITE}/t/zzzzzzzz`);
});

test("art 1: Spotify oEmbed (asked alongside Deezer, and preferred), at 640 px on i.scdn.co, written back without changing the link", async (t) => {
    const calls = stubFetch(t, { [OEMBED]: oembedOk, [DEEZER]: deezerOk });
    const e = env();
    const id = await share(e, song({ sp: SP, isrc: ISRC, yt: YT }));
    const { html } = await page(e, `/t/${id}`);
    assert.equal(meta(html, "og:image"), SPOTIFY_640);
    assert.equal(meta(html, "og:image:width"), "640");
    assert.equal(meta(html, "og:image:height"), "640");
    assert.deepEqual(calls.map((u) => u.split("?")[0]).sort(), [`${DEEZER}${ISRC}`, OEMBED].sort());
    assert.ok(calls.includes(`${OEMBED}?url=${encodeURIComponent(`https://open.spotify.com/track/${SP}`)}`));
    assert.deepEqual(record(e, id).found, { url: SPOTIFY_640, width: 640, height: 640 });
    assert.equal(record(e, id).track.art, undefined, "server art is kept apart from the sharer's");
    const api = await (await handle(new Request(`${SITE}/v1/tracks/${id}`), e)).json();
    assert.equal(api.track.art, SPOTIFY_640, "the app sees the found art too");
    const again = await handle(new Request(`${SITE}/v1/tracks`, { method: "POST", body: JSON.stringify(song({ sp: SP, isrc: ISRC, yt: YT })) }), e);
    assert.equal(again.status, 200);
    assert.equal((await again.json()).id, id, "found art doesn't change which link the song gets");
    const before = calls.length;
    assert.equal(meta((await page(e, `/t/${id}`)).html, "og:image"), SPOTIFY_640);
    assert.equal(calls.length, before, "the second view uses the found art");
});

test("art 2: Deezer by ISRC when Spotify has nothing, written back with its size", async (t) => {
    stubFetch(t, { [OEMBED]: gone, [DEEZER]: deezerOk });
    const e = env();
    const id = await share(e, song({ sp: SP, isrc: ISRC, yt: YT }));
    const { html } = await page(e, `/t/${id}`);
    assert.equal(meta(html, "og:image"), DEEZER_XL);
    assert.equal(meta(html, "og:image:width"), "1000");
    assert.equal(meta(html, "og:image:height"), "1000");
    assert.deepEqual(record(e, id).found, { url: DEEZER_XL, width: 1000, height: 1000 });
});

test("art 3: YouTube hqdefault when the lookups fail; the miss is remembered for 6 h, then looked up again", async (t) => {
    const calls = stubFetch(t, { [OEMBED]: () => { throw new Error("network down"); }, [DEEZER]: () => json({ error: { type: "DataException", message: "no data", code: 800 } }) });
    const e = env();
    const id = await share(e, song({ sp: SP, isrc: ISRC, yt: YT }));
    const { status, html } = await page(e, `/t/${id}`);
    assert.equal(status, 200);
    assert.equal(meta(html, "og:image"), HQ);
    assert.equal(meta(html, "og:image:width"), "480");
    assert.equal(meta(html, "og:image:height"), "360");
    assert.equal(meta(html, "twitter:image"), HQ);
    const rec = record(e, id);
    assert.equal(rec.found, undefined);
    assert.ok(Date.now() - rec.artTriedAt < 5000);
    assert.equal(calls.length, 2);
    await page(e, `/t/${id}`);
    assert.equal(calls.length, 2, "no new lookup within 6 h");
    await putRecord(e, id, { ...rec, artTriedAt: Date.now() - 7 * HOUR });
    await page(e, `/t/${id}`);
    assert.equal(calls.length, 4, "looked up again after 6 h");
});

test("art 4: every lookup failing and no YouTube id means no image, and the page still renders", async (t) => {
    stubFetch(t, { [OEMBED]: () => new Response("oops", { status: 500 }), [DEEZER]: () => new Response("not json") });
    const e = env();
    const id = await share(e, song({ sp: SP, isrc: ISRC }));
    const { status, html } = await page(e, `/t/${id}`);
    assert.equal(status, 200);
    assert.equal(meta(html, "og:image"), null);
    assert.ok(!html.includes('class="cover" src='));
    assert.equal(record(e, id).found, undefined);
    assert.ok(record(e, id).artTriedAt > 0);
});

test("a lookup answering with an off-list image host is skipped", async (t) => {
    stubFetch(t, {
        [OEMBED]: () => json({ thumbnail_url: "https://evil.example/image/ab67616d00001e02255e131abc1410833be95673" }),
        [DEEZER]: deezerWith("https://evil.example/cover.jpg"),
    });
    const e = env();
    const id = await share(e, song({ sp: SP, isrc: ISRC, yt: YT }));
    assert.equal(meta((await page(e, `/t/${id}`)).html, "og:image"), HQ);
});

test("Deezer's placeholder cover (no image id), credentials or a port are skipped; a sloppy but allowed URL is stored parsed", async (t) => {
    const hex = "2fec34e02d0ca76df05f9f533f0492f2";
    for (const [cover, want] of [
        ["https://cdn-images.dzcdn.net/images/cover//1000x1000-000000-80-0-0.jpg", HQ],
        [`https://x@cdn-images.dzcdn.net/images/cover/${hex}/1000x1000-000000-80-0-0.jpg`, HQ],
        [`https://cdn-images.dzcdn.net:8443/images/cover/${hex}/1000x1000-000000-80-0-0.jpg`, HQ],
        [`https://cdn-images.dz\tcdn.net/images/cover/${hex}/1000x1000-000000-80-0-0.jpg`, DEEZER_XL],
    ]) {
        const real = globalThis.fetch;
        globalThis.fetch = async (url) => (String(url).startsWith(DEEZER) ? deezerWith(cover)() : gone());
        try {
            const e = env();
            const id = await share(e, song({ isrc: ISRC, yt: YT }));
            assert.equal(meta((await page(e, `/t/${id}`)).html, "og:image"), want, JSON.stringify(cover));
        } finally {
            globalThis.fetch = real;
        }
    }
    stubFetch(t, {});
});

test("malformed ids never reach a lookup URL, an image URL or a button", async (t) => {
    const calls = stubFetch(t, {});
    const e = env();
    const id = await share(e, song({ sp: "x?url=https://evil", isrc: "../../x", yt: "a/b\"onload" }));
    const { status, html } = await page(e, `/t/${id}`);
    assert.equal(status, 200);
    assert.deepEqual(calls, []);
    assert.equal(meta(html, "og:image"), null);
    assert.ok(!html.includes("Listen on"));
});

test("Spotify and Deezer are asked at once: a slow Spotify still wins once it answers", async (t) => {
    let deezerAsked;
    const asked = new Promise((resolve) => { deezerAsked = resolve; });
    // Spotify only answers after Deezer has been asked, so asking one after the other would time out Spotify.
    stubFetch(t, { [OEMBED]: async () => { await asked; return oembedOk(); }, [DEEZER]: () => { deezerAsked(); return deezerOk(); } });
    const started = Date.now();
    assert.deepEqual(await lookUpArt(song({ sp: SP, isrc: ISRC })), { url: SPOTIFY_640, width: 640, height: 640 });
    assert.ok(Date.now() - started < 1000);
});

test("a hung lookup gives up after about 2 s and the page still renders with the other source", async (t) => {
    stubFetch(t, { [OEMBED]: () => new Promise(() => {}), [DEEZER]: deezerOk }); // never answers, ignores the abort signal
    const e = env();
    const id = await share(e, song({ sp: SP, isrc: ISRC }));
    const started = Date.now();
    const { status, html } = await page(e, `/t/${id}`);
    const took = Date.now() - started;
    assert.equal(status, 200);
    assert.equal(meta(html, "og:image"), DEEZER_XL);
    assert.ok(took >= 1500 && took < 3500, `took ${took} ms`);
});

test("lookUpArt: each lookup is time-boxed, whether or not fetch honours the abort signal", async (t) => {
    let aborted = false;
    stubFetch(t, {
        [OEMBED]: () => new Promise(() => {}),
        [DEEZER]: (_, init) => new Promise((_, reject) => init.signal.addEventListener("abort", () => { aborted = true; reject(init.signal.reason); })),
    });
    const started = Date.now();
    assert.equal(await lookUpArt(song({ sp: SP, isrc: ISRC, yt: YT }), { timeoutMs: 40 }), null);
    assert.ok(Date.now() - started < 1000);
    assert.ok(aborted, "the Deezer request was aborted");
});

test("over ART_RL, a page skips the lookups (hqdefault instead) and doesn't record a miss; ART_RL is keyed by IP", async (t) => {
    const calls = stubFetch(t, { [OEMBED]: oembedOk, [DEEZER]: deezerOk });
    const keys = [];
    const e = env({ ART_RL: { limit: async (o) => { keys.push(o); return { success: false }; } } });
    const id = await share(e, song({ sp: SP, isrc: ISRC, yt: YT }));
    assert.equal(meta((await page(e, `/t/${id}`, { ip: "2001:db8::1" })).html, "og:image"), HQ);
    assert.deepEqual(calls, []);
    assert.deepEqual(keys, [{ key: "2001:db8:0:0" }]);
    assert.equal(record(e, id).artTriedAt, undefined, "the next view may still look up");
    const legacy = await page(e, `/t?t=S&a=A&sp=${SP}&yt=${YT}`);
    assert.equal(meta(legacy.html, "og:image"), HQ);
    assert.deepEqual(calls, []);
    // Art that needs no lookup doesn't spend the limit.
    const own = await share(e, song({ sp: SP, art: "https://i.scdn.co/image/own" }));
    await page(e, `/t/${own}`);
    assert.equal(keys.length, 2);
});

test("the legacy /t?… page gets album art from the same chain, cached for a day under its song ids", async (t) => {
    const calls = stubFetch(t, { [OEMBED]: oembedOk, [DEEZER]: gone });
    const cache = fakeCache(t);
    const q = `t=Never%20Gonna&a=Rick%20Astley&sp=${SP}&yt=${YT}`;
    const first = await page(env(), `/t?${q}`);
    assert.equal(first.status, 200);
    assert.equal(meta(first.html, "og:image"), SPOTIFY_640);
    assert.equal(meta(first.html, "og:image:width"), "640");
    assert.equal(meta(first.html, "og:type"), "music.song");
    assert.equal(meta(first.html, "og:title"), "Never Gonna");
    assert.equal(calls.length, 1);
    assert.equal(cache.store.size, 1);
    const [cached] = cache.store.values();
    assert.equal(cached.headers.get("cache-control"), "public, max-age=86400");
    // Same song ids in another order, with another title: one cache entry, no new lookup.
    const second = await page(env(), `/t?yt=${YT}&sp=${SP}&a=Someone&t=Other`);
    assert.equal(meta(second.html, "og:image"), SPOTIFY_640);
    assert.equal(meta(second.html, "og:image:width"), "640");
    assert.equal(calls.length, 1);
});

test("a legacy page's failed lookup is cached as a miss for 6 h", async (t) => {
    const calls = stubFetch(t, { [OEMBED]: gone, [DEEZER]: gone });
    const cache = fakeCache(t);
    const first = await page(env(), `/t?t=Song&a=Artist&sp=${SP}&isrc=${ISRC}&yt=${YT}`);
    assert.equal(meta(first.html, "og:image"), HQ);
    assert.equal(calls.length, 2);
    const [cached] = cache.store.values();
    assert.equal(cached.headers.get("cache-control"), "public, max-age=21600");
    const second = await page(env(), `/t?t=Song&a=Artist&sp=${SP}&isrc=${ISRC}&yt=${YT}`);
    assert.equal(meta(second.html, "og:image"), HQ);
    assert.equal(calls.length, 2, "no new lookup while the miss is cached");
});

test("the legacy page works without a Cache API (Node, workers.dev)", async (t) => {
    stubFetch(t, { [OEMBED]: oembedOk });
    const { html } = await page(env(), `/t?t=Song&a=Artist&sp=${SP}&yt=${YT}`);
    assert.equal(meta(html, "og:image"), SPOTIFY_640);
});

test("the legacy page's URL keeps only the song's fields, and Open in Stash opens it on workers.dev", async () => {
    const { html } = await page(env(), "/t?t=A%20Song&a=B&al=C&d=1000&fbclid=xyz&isrc=");
    const url = `${SITE}/t?t=A+Song&a=B&al=C&d=1000`;
    assert.equal(meta(html, "og:url"), amp(url));
    assert.equal(canonical(html), amp(url));
    assert.equal(meta(html, "og:description"), "B · C");
    assert.ok(html.includes(amp(`intent://stash-share.rawnaldclark.workers.dev/t?t=A+Song&a=B&al=C&d=1000#Intent;scheme=https;`
        + `package=com.stash.app;S.browser_fallback_url=${encodeURIComponent(url)};end`)), html);
    assert.ok(!html.includes("fbclid"));
});

test("a trailing slash on a song link finds the page instead of a JSON 404", async (t) => {
    stubFetch(t, {});
    const e = env();
    const id = await share(e, { t: "Song", a: "Artist" });
    const short = await page(e, `/t/${id}/`);
    assert.equal(short.status, 200);
    assert.equal(meta(short.html, "og:url"), `${SITE}/t/${id}`);
    const legacy = await page(e, "/t/?t=Song&a=Artist");
    assert.equal(legacy.status, 200);
    assert.ok(legacy.html.includes("<h1>Song</h1>"));
});

test("a YouTube thumbnail is shown as a clean square without its letterbox bars; og:image stays the plain URL", async (t) => {
    stubFetch(t, {});
    const e = env();
    const id = await share(e, song({ yt: YT }));
    const { html } = await page(e, `/t/${id}`);
    assert.equal(meta(html, "og:image"), HQ);
    assert.ok(html.includes(`<div class="cover crop"><img src="${HQ}"`), html);
    assert.ok(!html.includes('class="cover" src='));
});

test("titles, artists and albums with quotes and HTML are escaped in every tag and in the body", async (t) => {
    stubFetch(t, {});
    const e = env();
    const id = await share(e, { t: "\"><script>alert(1)</script>", a: "O'Brien & <b>Co</b>", al: "\"Live\"" });
    const { html } = await page(e, `/t/${id}`);
    assert.ok(!html.includes("<script>alert(1)</script>"));
    assert.ok(!html.includes("<b>Co</b>"));
    assert.equal(meta(html, "og:title"), "&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;");
    assert.equal(meta(html, "twitter:title"), "&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;");
    assert.equal(meta(html, "og:description"), "O&#39;Brien &amp; &lt;b&gt;Co&lt;/b&gt; · &quot;Live&quot;");
    assert.ok(html.includes("<h1>&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;</h1>"));
});

test("a legacy link without a title shows Unknown song, but its URL and Open in Stash don't invent one", async () => {
    const { status, html } = await page(env(), "/t?a=Artist");
    assert.equal(status, 200);
    assert.ok(html.includes("<h1>Unknown song</h1>"));
    assert.equal(meta(html, "og:url"), `${SITE}/t?a=Artist`);
    assert.ok(!html.includes("t=Unknown"));
});
