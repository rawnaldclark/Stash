import { test } from "node:test";
import assert from "node:assert/strict";
import { handle } from "../src/index.js";
import { cleanCover, cleanTrack } from "../src/validate.js";
import { cleanPost } from "../src/community-post.js";
import { env } from "./fake-kv.js";

/**
 * Cover URLs pass the allowlist on their parsed host, so what's stored and shown must be that parsed URL too:
 * a raw string can read differently to another parser (a crawler, a phone) and land somewhere else.
 */
const FORMS = [
    [String.raw`https://i.scdn.co\@evil.example/x.jpg`, "https://i.scdn.co/@evil.example/x.jpg"], // backslash before @
    ["https://i.sc\tdn.co/tab", "https://i.scdn.co/tab"],
    ["https://i.scdn.co\n/newline", "https://i.scdn.co/newline"],
    [" https://i.scdn.co/space", "https://i.scdn.co/space"],
    ["https:i.scdn.co/noslashes", "https://i.scdn.co/noslashes"],
    ["https://I.SCDN.CO/upper", "https://i.scdn.co/upper"],
    ["https://i.scdn.co:443/default-port", "https://i.scdn.co/default-port"],
    ["https://user:pw@i.scdn.co/x", null],
    ["https://evil@i.scdn.co/x", null],
    ["https://:pw@i.scdn.co/x", null],
    ["https://i.scdn.co:8443/x", null],
    ["evil.com/i.scdn.co", null],
    ["https://evil.com/i.scdn.co", null],
    ["http://i.scdn.co/x", null],
    [`https://i.scdn.co/${"a".repeat(1000)}`, null],
    [42, null],
];

test("cleanCover keeps the parsed URL, and drops credentials, ports and other hosts", () => {
    for (const [raw, want] of FORMS) assert.equal(cleanCover(raw), want, JSON.stringify(raw));
});

test("a short link stores client art in its parsed form", async () => {
    const e = env();
    for (const [i, [raw, want]] of FORMS.entries()) {
        const r = await handle(new Request("https://stashfm.app/v1/tracks", { method: "POST", body: JSON.stringify({ t: `Song ${i}`, a: "A", art: raw }) }), e);
        assert.equal(r.status, 201, JSON.stringify(raw));
        const stored = JSON.parse(e.SHARE_KV.map.get(`t:${(await r.json()).id}`).value).track;
        assert.equal(stored.art, want ?? undefined, JSON.stringify(raw));
    }
});

test("a mix stores its covers in their parsed form", async () => {
    const e = env();
    // The mix validator already rejects covers that don't start with https:// (400), so only those forms are sent.
    const sent = FORMS.filter(([raw]) => typeof raw === "string" && raw.startsWith("https://"));
    const r = await handle(new Request("https://stashfm.app/v1/mixes", { method: "POST", body: JSON.stringify({
        editKey: "k".repeat(43), doc: { v: 1, name: "M", covers: sent.slice(0, 4).map(([raw]) => raw), tracks: [{ t: "T", a: "A" }] } }) }), e);
    assert.equal(r.status, 201);
    const stored = JSON.parse(e.SHARE_KV.map.get(`mix:${(await r.json()).id}`).value).doc.covers;
    assert.deepEqual(stored, sent.slice(0, 4).map(([, want]) => want).filter(Boolean));
    const bad = await handle(new Request("https://stashfm.app/v1/mixes", { method: "POST", body: JSON.stringify({
        editKey: "k".repeat(43), doc: { v: 1, name: "N", covers: ["https://evil@i.scdn.co/x", "https://i.scdn.co:8443/x"], tracks: [{ t: "T", a: "A" }] } }) }), e);
    assert.equal(JSON.parse(e.SHARE_KV.map.get(`mix:${(await bad.json()).id}`).value).doc.covers, undefined);
});

test("a Listen Together song keeps its art in parsed form", () => {
    for (const [raw, want] of FORMS) {
        const clean = cleanTrack({ t: "T", a: "A", art: raw });
        assert.equal(clean.art, want ?? undefined, JSON.stringify(raw));
    }
});

test("a Community playlist post keeps its covers in parsed form", () => {
    const covers = [String.raw`https://i.scdn.co\@evil.example/x.jpg`, "https://evil@i.scdn.co/y", "https://I.SCDN.CO/z", "https://i.scdn.co:8443/w"];
    const post = cleanPost({ kind: "playlist", name: "N", title: "T", body: { covers, tracks: [{ t: "T", a: "A" }] } });
    assert.deepEqual(post.body.covers, ["https://i.scdn.co/@evil.example/x.jpg", "https://i.scdn.co/z"]);
    assert.deepEqual(post.summary.covers, post.body.covers);
});

test("pages show a cover stored before this change in its parsed form", async () => {
    const e = env();
    await e.SHARE_KV.put("mix:Old1Mix2", JSON.stringify({ keyHash: "0".repeat(64), doc: { v: 1, id: "Old1Mix2", version: 1, name: "Old",
        covers: ["https://evil@i.scdn.co/a.jpg", String.raw`https://i.scdn.co\@evil.example/b.jpg`], tracks: [{ t: "T", a: "A" }] } }));
    const html = await (await handle(new Request("https://stashfm.app/m/Old1Mix2"), e)).text();
    assert.ok(html.includes('<meta property="og:image" content="https://i.scdn.co/@evil.example/b.jpg">'));
    assert.ok(!html.includes("evil@") && !html.includes("\\"));
});
