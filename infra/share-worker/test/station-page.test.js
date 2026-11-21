import { test } from "node:test";
import assert from "node:assert/strict";
import { sha256Hex } from "../src/store.js";
import { hoursOf } from "../src/stations.js";
import { call, KEY_A } from "./community-helpers.js";
import { seedLive, seedStation, sess } from "./stations-helpers.js";

const page = (e, path) => call(e, "GET", path);
const NY_WEEKDAYS = { tz: "America/New_York", windows: [1, 2, 3, 4, 5].map((day) => ({ day, start: "09:00", end: "17:00" })) };
const ART = "https://i.scdn.co/image/ab67616d0000b273c8b444df094279e70d0ed856";

test("a live station's page: its name, host and vibe, Live now with the listeners and the song, Open in Stash", async () => {
    const e = sess();
    await seedStation(e, { id: "MAYA0001", name: "Maya's Desk Radio", hostName: "Maya", vibe: "late-night r&b, no skips" });
    await seedLive(e, { station: "MAYA0001", room: "ROOMMAYA", listeners: 12, track: { t: "Nude", a: "Radiohead", art: ART } });
    const r = await page(e, "/s/MAYA0001");
    assert.equal(r.status, 200);
    assert.equal(r.headers.get("content-type"), "text/html; charset=utf-8");
    assert.match(r.headers.get("content-security-policy"), /default-src 'none'/);
    const body = await r.text();
    assert.match(body, /<title>Maya&#39;s Desk Radio<\/title>/);
    assert.match(body, /<h1>Maya&#39;s Desk Radio<\/h1>/);
    assert.match(body, /Hosted by Maya/);
    assert.match(body, /late-night r&amp;b, no skips/);
    assert.match(body, /<p>Live now · 12 listening<\/p>/);
    assert.match(body, /Now playing: Nude · Radiohead/);
    assert.match(body, /<meta property="og:image" content="https:\/\/i\.scdn\.co\/image\/ab67616d0000b273c8b444df094279e70d0ed856">/);
    assert.match(body, /<meta property="og:url" content="https:\/\/share\.test\/s\/MAYA0001">/);
    assert.match(body, /href="intent:\/\/stash-share\.rawnaldclark\.workers\.dev\/s\/MAYA0001#Intent;scheme=https;package=com\.stash\.app;S\.browser_fallback_url=https%3A%2F%2Fshare\.test%2Fs%2FMAYA0001;end">Open in Stash</);
    assert.ok(!body.includes("MAYA0001oooo"), "the owner's hash never shows");
    assert.ok(!body.includes("ROOMMAYA"), "nor the room's code: joining goes through the app");
});

test("a live room that's full or paused says so", async () => {
    const e = sess();
    await seedStation(e, { id: "MAYA0001", name: "Maya's Desk Radio" });
    await seedLive(e, { station: "MAYA0001", listeners: 50, paused: 1 });
    const body = await (await page(e, "/s/MAYA0001")).text();
    assert.match(body, /<p>Live now · 50 listening · full · paused<\/p>/);
    assert.doesNotMatch(body, /Hosted by/, "no host name, no line");
});

test("a scheduled station off the air shows its hours, in its own time zone", async () => {
    const e = sess();
    await seedStation(e, { id: "DESK0001", name: "Desk", hostName: "Maya", autoLive: "schedule", schedule: NY_WEEKDAYS });
    const body = await (await page(e, "/s/DESK0001")).text();
    assert.match(body, /<p>On the air: Weekdays 9:00–17:00 \(New York time\)<\/p>/);
    assert.match(body, /<meta name="description" content="On the air: Weekdays 9:00–17:00 \(New York time\) · Hosted by Maya · A station on Stash">/);
    assert.doesNotMatch(body, /Live now/);
    assert.doesNotMatch(body, /og:image/, "nothing playing, no picture");
});

test("off the air without a schedule: not live, and an always-on station says when it goes live", async () => {
    const e = sess();
    await seedStation(e, { id: "OFF00001", name: "Off" });
    await seedStation(e, { id: "ALWAYS01", name: "Always", hostName: "Rawn", autoLive: "always" });
    await seedStation(e, { id: "STALE001", name: "Stale" });
    await seedLive(e, { station: "STALE001", seen: 4 * 60_000 });
    assert.match(await (await page(e, "/s/OFF00001")).text(), /<p>Not live right now\.<\/p>/);
    assert.match(await (await page(e, "/s/ALWAYS01")).text(), /<p>Not live right now\. It goes live whenever Rawn plays music in Stash\.<\/p>/);
    assert.match(await (await page(e, "/s/STALE001")).text(), /<p>Not live right now\.<\/p>/, "a room that stopped writing isn't live");
});

test("a missing, removed, hidden or blocked station, or Sessions off: This station isn't available, 404", async () => {
    const e = sess();
    await seedStation(e, { id: "REMOVED1", removed: Date.now() });
    await seedStation(e, { id: "HIDDEN01", down: 3 });
    await seedStation(e, { id: "VOUCHED1", down: 3, vouched: 1 });
    await seedStation(e, { id: "BLOCKED1", key: KEY_A });
    await e.COMMUNITY_DB.prepare("INSERT INTO blocked (poster, at) VALUES (?1, 1)").bind(await sha256Hex(KEY_A)).run();
    for (const id of ["REMOVED1", "HIDDEN01", "BLOCKED1", "NOSUCHID"]) {
        const r = await page(e, `/s/${id}`);
        assert.equal(r.status, 404, id);
        assert.match(await r.text(), /<h1>This station isn&#39;t available<\/h1>/, id);
    }
    assert.equal((await page(e, "/s/VOUCHED1")).status, 200, "vouched for: votes can't hide it");
    const off = sess({ SESSIONS: "0" });
    await seedStation(off, { id: "STATION1" });
    const r = await page(off, "/s/STATION1");
    assert.equal(r.status, 404);
    assert.match(await r.text(), /This station isn&#39;t available/);
});

test("everything the host typed, and the song, is escaped", async () => {
    const e = sess();
    await seedStation(e, { id: "EVIL0001", name: "<script>alert(1)</script>", hostName: "\"><img src=x onerror=alert(1)>", vibe: "</p><script>x</script>" });
    await seedLive(e, { station: "EVIL0001", track: { t: "<b>t</b>", a: "<i>a</i>", art: "javascript:alert(1)" } });
    const body = await (await page(e, "/s/EVIL0001")).text();
    assert.doesNotMatch(body, /<script>|<img src=x|<b>t<\/b>|<i>a<\/i>|javascript:/);
    assert.match(body, /<h1>&lt;script&gt;alert\(1\)&lt;\/script&gt;<\/h1>/);
    assert.match(body, /Hosted by &quot;&gt;&lt;img src=x onerror=alert\(1\)&gt;/);
});

test("the page forgives a trailing slash and drops the query from its own URL; a malformed id isn't a station page", async () => {
    const e = sess();
    await seedStation(e, { id: "MAYA0001", name: "Maya" });
    const r = await page(e, "/s/MAYA0001/?fbclid=abc");
    assert.equal(r.status, 200);
    assert.match(await r.text(), /<link rel="canonical" href="https:\/\/share\.test\/s\/MAYA0001">/);
    assert.equal((await page(e, "/s/maya0001")).status, 404, "ids are case-sensitive");
    assert.equal((await page(e, "/s/short")).status, 404);
    assert.equal((await call(e, "POST", "/s/MAYA0001", { body: {} })).status, 404);
});

test("over the read limit, the page says slow down", async () => {
    const e = sess({ COMMUNITY_READ_RL: { limit: async () => ({ success: false }) } });
    await seedStation(e, { id: "STATION1" });
    const r = await page(e, "/s/STATION1");
    assert.equal(r.status, 429);
    assert.match(await r.text(), /<h1>Slow down<\/h1>/);
});

test("hours read the way people say them", () => {
    const w = (days, start = "09:00", end = "17:00") => days.map((day) => ({ day, start, end }));
    const h = (windows, tz = "America/New_York") => hoursOf({ tz, windows });
    assert.equal(h(w([1, 2, 3, 4, 5])), "Weekdays 9:00–17:00 (New York time)");
    assert.equal(h(w([7, 6], "12:00", "16:30")), "Weekends 12:00–16:30 (New York time)");
    assert.equal(h(w([1, 2, 3, 4, 5, 6, 7], "22:00", "02:00")), "Every day 22:00–2:00 (New York time)");
    assert.equal(h(w([1, 2, 3])), "Mon–Wed 9:00–17:00 (New York time)");
    assert.equal(h(w([1, 3, 5])), "Mon, Wed, Fri 9:00–17:00 (New York time)");
    assert.equal(h(w([5, 1, 2, 3])), "Mon–Wed, Fri 9:00–17:00 (New York time)");
    assert.equal(h(w([1, 2])), "Mon, Tue 9:00–17:00 (New York time)");
    assert.equal(h([...w([6], "12:00", "16:00"), ...w([1, 2, 3, 4, 5])]), "Weekdays 9:00–17:00; Sat 12:00–16:00 (New York time)");
    assert.equal(h([...w([1], "13:00", "17:00"), ...w([1], "09:00", "12:00")]), "Mon 9:00–12:00; Mon 13:00–17:00 (New York time)");
    assert.equal(h(w([7], "00:00", "00:30"), "UTC"), "Sun 0:00–0:30 (UTC)");
    assert.equal(h(w([2]), "America/Argentina/Buenos_Aires"), "Tue 9:00–17:00 (Buenos Aires time)");
    assert.equal(hoursOf(null), null);
    assert.equal(hoursOf({ tz: "UTC", windows: [] }), null);
});
