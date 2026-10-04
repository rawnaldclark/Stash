import { test } from "node:test";
import assert from "node:assert/strict";
import { handle } from "../src/index.js";
import { env } from "./fake-kv.js";

const BASE = "https://share.test";
const KEY = "k".repeat(43);

async function withMix(e, doc) {
    const r = await handle(new Request(`${BASE}/v1/mixes`, { method: "POST", body: JSON.stringify({ doc, editKey: KEY }) }), e);
    return (await r.json()).id;
}

test("mix page escapes every string and carries Open Graph tags", async () => {
    const e = env();
    const id = await withMix(e, { v: 1, name: "<script>alert(1)</script>", sharedBy: "\"Rawn\"", covers: ["https://i.scdn.co/image/a"],
        tracks: [{ t: "<b>T</b>", a: "A&B" }] });
    const r = await handle(new Request(`${BASE}/m/${id}`), e);
    assert.equal(r.status, 200);
    assert.match(r.headers.get("content-type"), /text\/html/);
    const html = await r.text();
    assert.ok(!html.includes("<script>alert(1)</script>"));
    assert.ok(html.includes("&lt;script&gt;alert(1)&lt;/script&gt;"));
    assert.ok(html.includes("&lt;b&gt;T&lt;/b&gt;") && html.includes("A&amp;B") && html.includes("&quot;Rawn&quot;"));
    assert.ok(html.includes('property="og:title"') && html.includes('content="https://i.scdn.co/image/a"'));
    assert.ok(html.includes(`intent://`) && html.includes("releases/latest"));
});

test("page for a deleted or unknown mix still renders with the right status", async () => {
    const e = env();
    const id = await withMix(e, { v: 1, name: "X", tracks: [{ t: "T", a: "A" }] });
    await handle(new Request(`${BASE}/v1/mixes/${id}`, { method: "DELETE", headers: { "X-Stash-Edit-Key": KEY } }), e);
    const gone = await handle(new Request(`${BASE}/m/${id}`), e);
    assert.equal(gone.status, 410); assert.match(await gone.text(), /no longer shared/);
    const unknown = await handle(new Request(`${BASE}/m/zzzzzzzz`), e);
    assert.equal(unknown.status, 404);
});

test("track page escapes the query values", async () => {
    const r = await handle(new Request(`${BASE}/t?t=%3Ci%3ESong&a=Artist`), env());
    assert.equal(r.status, 200);
    const html = await r.text();
    assert.ok(html.includes("&lt;i&gt;Song") && !html.includes("<i>Song"));
});

test("assetlinks lists both packages with their certificate fingerprints", async () => {
    const r = await handle(new Request(`${BASE}/.well-known/assetlinks.json`), env());
    assert.equal(r.status, 200);
    const body = await r.json();
    const pkgs = body.map((s) => s.target.package_name).sort();
    assert.deepEqual(pkgs, ["com.stash.app", "com.stash.app.debug"]);
    for (const s of body) assert.match(s.target.sha256_cert_fingerprints[0], /^([0-9A-F]{2}:){31}[0-9A-F]{2}$/);
});

test("mix page never uses an off-list cover stored before the allowlist", async () => {
    const e = env();
    await e.SHARE_KV.put("mix:Old1Mix2", JSON.stringify({ keyHash: "0".repeat(64), doc: { v: 1, id: "Old1Mix2", version: 1, name: "Old",
        covers: ["https://evil.example/a.jpg"], tracks: [{ t: "T", a: "A" }] } }));
    const html = await (await handle(new Request(`${BASE}/m/Old1Mix2`), e)).text();
    assert.ok(!html.includes("evil.example"));
});

/** The content of one `<meta property|name="key">` tag, or null. */
const meta = (html, key) => new RegExp(`<meta (?:property|name)="${key}" content="([^"]*)">`).exec(html)?.[1] ?? null;
const canonical = (html) => /<link rel="canonical" href="([^"]*)">/.exec(html)?.[1] ?? null;

test("mix page preview: music.playlist, song count and sharer, its own URL, the first allowed cover as a large image", async () => {
    const e = env();
    const id = await withMix(e, { v: 1, name: "Late Night", sharedBy: "Rawn", covers: ["https://evil.example/a.jpg", "https://i.scdn.co/image/b"],
        tracks: [{ t: "T1", a: "A" }, { t: "T2", a: "B" }] });
    const html = await (await handle(new Request(`${BASE}/m/${id}?fbclid=x`), e)).text();
    assert.equal(meta(html, "og:site_name"), "Stash");
    assert.equal(meta(html, "og:type"), "music.playlist");
    assert.equal(meta(html, "og:url"), `${BASE}/m/${id}`);
    assert.equal(canonical(html), `${BASE}/m/${id}`);
    assert.equal(meta(html, "og:title"), "Late Night");
    assert.equal(meta(html, "og:description"), "2 songs · shared on Stash by Rawn");
    assert.equal(meta(html, "og:image"), "https://i.scdn.co/image/b");
    assert.ok(meta(html, "og:image:alt").includes("Late Night"));
    assert.equal(meta(html, "twitter:card"), "summary_large_image");
    assert.equal(meta(html, "twitter:title"), "Late Night");
    assert.equal(meta(html, "twitter:description"), "2 songs · shared on Stash by Rawn");
    assert.equal(meta(html, "twitter:image"), "https://i.scdn.co/image/b");
});

test("a mix with one song, no sharer and no cover: singular count, summary card, no image tags", async () => {
    const e = env();
    const id = await withMix(e, { v: 1, name: "Solo", tracks: [{ t: "T", a: "A" }] });
    const html = await (await handle(new Request(`${BASE}/m/${id}`), e)).text();
    assert.equal(meta(html, "og:description"), "1 song · shared on Stash");
    assert.equal(meta(html, "twitter:card"), "summary");
    assert.equal(meta(html, "og:image"), null);
    assert.equal(meta(html, "og:image:alt"), null);
    assert.equal(meta(html, "twitter:image"), null);
});

test("Listen Together invite page keeps its wording and shows the current song's art", async () => {
    const e = env();
    const created = await handle(new Request(`${BASE}/v1/rooms`, { method: "POST", body: JSON.stringify({ hostName: "Rawn" }) }), e);
    const { code } = await created.json();
    const before = await (await handle(new Request(`${BASE}/l/${code.toLowerCase()}`), e)).text();
    assert.equal(meta(before, "og:title"), "Join Rawn&#39;s session in Stash");
    assert.equal(meta(before, "og:description"), "0 listening · Listen Together on Stash");
    assert.equal(meta(before, "og:url"), `${BASE}/l/${code}`);
    assert.equal(canonical(before), `${BASE}/l/${code}`);
    assert.equal(meta(before, "og:site_name"), "Stash");
    assert.equal(meta(before, "og:type"), "website");
    assert.equal(meta(before, "twitter:card"), "summary");
    assert.equal(meta(before, "og:image"), null);
    const room = e.ROOMS.rooms.get(code);
    room.ctx.store.set("room", { ...room.ctx.store.get("room"), track: { t: "Song", a: "Artist", art: "https://i.scdn.co/image/now" } });
    const during = await (await handle(new Request(`${BASE}/l/${code}`), e)).text();
    assert.equal(meta(during, "og:image"), "https://i.scdn.co/image/now");
    assert.equal(meta(during, "twitter:image"), "https://i.scdn.co/image/now");
    assert.equal(meta(during, "twitter:card"), "summary_large_image");
    assert.ok(meta(during, "og:image:alt").includes("Song"));
    assert.ok(during.includes("Now playing: Song · Artist"));
});

test("message pages carry the site name, their own URL and twitter tags", async () => {
    const html = await (await handle(new Request(`${BASE}/m/zzzzzzzz`), env())).text();
    assert.equal(meta(html, "og:site_name"), "Stash");
    assert.equal(meta(html, "og:url"), `${BASE}/m/zzzzzzzz`);
    assert.equal(canonical(html), `${BASE}/m/zzzzzzzz`);
    assert.equal(meta(html, "og:title"), "Mix not found");
    assert.equal(meta(html, "twitter:card"), "summary");
    assert.equal(meta(html, "twitter:title"), "Mix not found");
    assert.equal(meta(html, "twitter:description"), "This link doesn't point to a mix.".replace("'", "&#39;"));
});

test("stashfm.app / is a placeholder landing page; workers.dev / is unchanged", async () => {
    const r = await handle(new Request("https://stashfm.app/"), env());
    assert.equal(r.status, 200);
    assert.match(r.headers.get("content-type"), /^text\/html/);
    const html = await r.text();
    assert.match(html, /<title>Stash<\/title>/);
    assert.equal(meta(html, "og:url"), "https://stashfm.app/");
    assert.ok(html.includes('href="https://github.com/rawnaldclark/Stash/releases/latest">Get Stash</a>'));
    assert.ok(!html.includes("Open in Stash"));
    assert.equal((await handle(new Request("https://stashfm.app/", { method: "HEAD" }), env())).status, 200);
    const old = await handle(new Request("https://stash-share.rawnaldclark.workers.dev/"), env());
    assert.equal(old.status, 404);
    assert.deepEqual(await old.json(), { error: "not_found" });
    assert.equal((await handle(new Request("https://stashfm.app/", { method: "POST" }), env())).status, 404);
});

test("pages built on a workers.dev request keep workers.dev URLs", async () => {
    const OLD = "https://stash-share.rawnaldclark.workers.dev";
    const e = env();
    const id = await withMix(e, { v: 1, name: "X", tracks: [{ t: "T", a: "A" }] });
    const mix = await (await handle(new Request(`${OLD}/m/${id}`), e)).text();
    assert.equal(meta(mix, "og:url"), `${OLD}/m/${id}`);
    assert.ok(mix.includes(`intent://stash-share.rawnaldclark.workers.dev/m/${id}#Intent`));
    assert.ok(!mix.includes("stashfm.app"));
    const song = await (await handle(new Request(`${OLD}/t?t=Song&a=Artist`), e)).text();
    assert.equal(meta(song, "og:url"), `${OLD}/t?t=Song&amp;a=Artist`);
    assert.ok(!song.includes("stashfm.app"));
});

/** Every Stash version's manifest knows the workers.dev host; only new ones know stashfm.app. */
const APP_HOST = "stash-share.rawnaldclark.workers.dev";

test("Open in Stash on stashfm.app opens the workers.dev link every app version handles, falling back to the page", async () => {
    const e = env();
    const id = await withMix(e, { v: 1, name: "X", tracks: [{ t: "T", a: "A" }] });
    const mix = await (await handle(new Request(`https://stashfm.app/m/${id}`), e)).text();
    assert.ok(mix.includes(`intent://${APP_HOST}/m/${id}#Intent;scheme=https;package=com.stash.app;` +
        `S.browser_fallback_url=${encodeURIComponent(`https://stashfm.app/m/${id}`)};end`), mix);
    const created = await handle(new Request("https://stashfm.app/v1/rooms", { method: "POST", body: JSON.stringify({ hostName: "R" }) }), e);
    const { code, url } = await created.json();
    assert.equal(url, `https://stashfm.app/l/${code}`);
    const invite = await (await handle(new Request(url), e)).text();
    assert.ok(invite.includes(`intent://${APP_HOST}/l/${code}#Intent;scheme=https;package=com.stash.app;` +
        `S.browser_fallback_url=${encodeURIComponent(url)};end`));
});

test("a trailing slash on a mix or invite link finds the page, and its URL drops the slash", async () => {
    const e = env();
    const id = await withMix(e, { v: 1, name: "Slash", tracks: [{ t: "T", a: "A" }] });
    const r = await handle(new Request(`${BASE}/m/${id}/`), e);
    assert.equal(r.status, 200);
    assert.equal(meta(await r.text(), "og:url"), `${BASE}/m/${id}`);
    const created = await handle(new Request(`${BASE}/v1/rooms`, { method: "POST", body: JSON.stringify({ hostName: "R" }) }), e);
    const { code } = await created.json();
    assert.equal((await handle(new Request(`${BASE}/l/${code.toLowerCase()}/`), e)).status, 200);
    assert.equal((await handle(new Request(`${BASE}/v1/mixes/${id}/`), e)).status, 404, "API paths stay exact");
});
