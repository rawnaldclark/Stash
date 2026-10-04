import { test } from "node:test";
import assert from "node:assert/strict";
import { handle } from "../src/index.js";
import { resolveArt } from "../src/art.js";
import { env } from "./fake-kv.js";

const SITE = "https://stashfm.app";
const SP = "4uLU6hMCjMI75M1A2tKUQC";
const YT = "dQw4w9WgXcQ";
const ISRC = "GBARL9300135";
const OEMBED_THUMB = "https://image-cdn-ak.spotifycdn.com/image/ab67616d00001e02255e131abc1410833be95673";
const SPOTIFY_640 = "https://i.scdn.co/image/ab67616d0000b273255e131abc1410833be95673";
const DEEZER_XL = "https://cdn-images.dzcdn.net/images/cover/2fec34e02d0ca76df05f9f533f0492f2/1000x1000-000000-80-0-0.jpg";
const HQ = `https://i.ytimg.com/vi/${YT}/hqdefault.jpg`;
const song = (over = {}) => ({ t: "Never Gonna Give You Up", a: "Rick Astley", al: "Whenever You Need Somebody", ...over });

/** The content of one `<meta property|name="key">` tag, or null. */
const meta = (html, key) => new RegExp(`<meta (?:property|name)="${key}" content="([^"]*)">`).exec(html)?.[1] ?? null;
const canonical = (html) => /<link rel="canonical" href="([^"]*)">/.exec(html)?.[1] ?? null;

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
const page = async (e, path, base = SITE) => {
    const r = await handle(new Request(`${base}${path}`), e);
    return { status: r.status, html: await r.text(), headers: r.headers };
};
const storedArt = (e, id) => JSON.parse(e.SHARE_KV.map.get(`t:${id}`).value).track.art;

test("short song page: music.song preview tags, the stored art as the image, and the short link as its URL", async (t) => {
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
    assert.ok(html.includes(`intent://stashfm.app/t/${id}#Intent;scheme=https;package=com.stash.app;`));
    assert.ok(html.includes(`href="https://open.spotify.com/track/${SP}">Listen on Spotify</a>`));
    assert.ok(html.includes(`href="https://music.youtube.com/watch?v=${YT}">Listen on YouTube Music</a>`));
    assert.ok(html.includes("releases/latest\">Get Stash</a>"));
    assert.deepEqual(calls, [], "stored art needs no lookup");
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

test("art fallback 1: Spotify oEmbed, moved to i.scdn.co at 640 px and written back to the link", async (t) => {
    const calls = stubFetch(t, { [OEMBED]: oembedOk, [DEEZER]: deezerOk });
    const e = env();
    const id = await share(e, song({ sp: SP, isrc: ISRC, yt: YT }));
    const { html } = await page(e, `/t/${id}`);
    assert.equal(meta(html, "og:image"), SPOTIFY_640);
    assert.deepEqual(calls, [`${OEMBED}?url=${encodeURIComponent(`https://open.spotify.com/track/${SP}`)}`]);
    assert.equal(storedArt(e, id), SPOTIFY_640);
    const api = await (await handle(new Request(`${SITE}/v1/tracks/${id}`), e)).json();
    assert.equal(api.track.art, SPOTIFY_640, "the app sees the written-back art too");
    await page(e, `/t/${id}`);
    assert.equal(calls.length, 1, "the second view uses the stored art");
});

test("art fallback 2: Deezer by ISRC when Spotify has nothing, written back", async (t) => {
    const calls = stubFetch(t, { [OEMBED]: () => new Response(null, { status: 404 }), [DEEZER]: deezerOk });
    const e = env();
    const id = await share(e, song({ sp: SP, isrc: ISRC, yt: YT }));
    const { html } = await page(e, `/t/${id}`);
    assert.equal(meta(html, "og:image"), DEEZER_XL);
    assert.deepEqual(calls.map((u) => u.split("?")[0]), [OEMBED, `${DEEZER}${ISRC}`]);
    assert.equal(storedArt(e, id), DEEZER_XL);
});

test("art fallback 3: YouTube hqdefault when the lookups fail; it isn't written back, so a later view can still find real art", async (t) => {
    stubFetch(t, { [OEMBED]: () => { throw new Error("network down"); }, [DEEZER]: () => json({ error: { type: "DataException", message: "no data", code: 800 } }) });
    const e = env();
    const id = await share(e, song({ sp: SP, isrc: ISRC, yt: YT }));
    const { status, html } = await page(e, `/t/${id}`);
    assert.equal(status, 200);
    assert.equal(meta(html, "og:image"), HQ);
    assert.equal(meta(html, "twitter:image"), HQ);
    assert.equal(storedArt(e, id), undefined);
});

test("art fallback 4: every lookup failing and no YouTube id means no image, and the page still renders", async (t) => {
    stubFetch(t, { [OEMBED]: () => new Response("oops", { status: 500 }), [DEEZER]: () => new Response("not json") });
    const e = env();
    const id = await share(e, song({ sp: SP, isrc: ISRC }));
    const { status, html } = await page(e, `/t/${id}`);
    assert.equal(status, 200);
    assert.equal(meta(html, "og:image"), null);
    assert.ok(!html.includes('class="cover" src='));
    assert.equal(storedArt(e, id), undefined);
});

test("a lookup answering with an off-list image host is skipped", async (t) => {
    stubFetch(t, {
        [OEMBED]: () => json({ thumbnail_url: "https://evil.example/image/ab67616d00001e02255e131abc1410833be95673" }),
        [DEEZER]: () => json({ album: { cover_xl: "https://evil.example/cover.jpg" } }),
    });
    const e = env();
    const id = await share(e, song({ sp: SP, isrc: ISRC, yt: YT }));
    assert.equal(meta((await page(e, `/t/${id}`)).html, "og:image"), HQ);
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

test("a hung lookup gives up after about 2 s and the page still renders with the next source", async (t) => {
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

test("resolveArt: each lookup is time-boxed, whether or not fetch honours the abort signal", async (t) => {
    let aborted = false;
    stubFetch(t, {
        [OEMBED]: () => new Promise(() => {}),
        [DEEZER]: (_, init) => new Promise((_, reject) => init.signal.addEventListener("abort", () => { aborted = true; reject(init.signal.reason); })),
    });
    const started = Date.now();
    assert.equal(await resolveArt(song({ sp: SP, isrc: ISRC, yt: YT }), { timeoutMs: 40 }), HQ);
    assert.ok(Date.now() - started < 1000);
    assert.ok(aborted, "the Deezer request was aborted");
});

test("the legacy /t?… page now gets album art from the same chain, cached for a day under its song ids", async (t) => {
    const calls = stubFetch(t, { [OEMBED]: oembedOk });
    const cache = fakeCache(t);
    const q = `t=Never%20Gonna&a=Rick%20Astley&sp=${SP}&yt=${YT}`;
    const first = await page(env(), `/t?${q}`);
    assert.equal(first.status, 200);
    assert.equal(meta(first.html, "og:image"), SPOTIFY_640);
    assert.equal(meta(first.html, "og:type"), "music.song");
    assert.equal(meta(first.html, "og:title"), "Never Gonna");
    assert.equal(calls.length, 1);
    assert.equal(cache.store.size, 1);
    const [cached] = cache.store.values();
    assert.equal(cached.headers.get("cache-control"), "public, max-age=86400");
    // Same song ids in another order, with another title: one cache entry, no new lookup.
    const second = await page(env(), `/t?yt=${YT}&sp=${SP}&a=Someone&t=Other`);
    assert.equal(meta(second.html, "og:image"), SPOTIFY_640);
    assert.equal(calls.length, 1);
});

test("the legacy page works without a Cache API (Node, workers.dev) and doesn't cache a fallback", async (t) => {
    stubFetch(t, { [OEMBED]: () => new Response(null, { status: 404 }) });
    const { html } = await page(env(), `/t?t=Song&a=Artist&sp=${SP}&yt=${YT}`);
    assert.equal(meta(html, "og:image"), HQ);
    const cache = fakeCache(t);
    await page(env(), `/t?t=Song&a=Artist&sp=${SP}&yt=${YT}`);
    assert.equal(cache.store.size, 0, "hqdefault needs no lookup, so it's never cached");
});

test("the legacy page's URL keeps only the song's fields, and Open in Stash points at it", async () => {
    const { html } = await page(env(), "/t?t=A%20Song&a=B&al=C&d=1000&fbclid=xyz&isrc=GBARL9300135");
    const url = `${SITE}/t?t=A+Song&a=B&al=C&d=1000&isrc=GBARL9300135`;
    assert.equal(meta(html, "og:url"), url.replace(/&/g, "&amp;"));
    assert.equal(canonical(html), url.replace(/&/g, "&amp;"));
    assert.equal(meta(html, "og:description"), "B · C");
    assert.ok(html.includes(`intent://stashfm.app/t?t=A+Song&amp;a=B`));
    assert.ok(!html.includes("fbclid"));
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
