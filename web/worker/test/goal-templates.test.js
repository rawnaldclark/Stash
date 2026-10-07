import { beforeEach, test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { addManualEntry, currentGoal, forgetGoalCache, goalTarget, goalView, monthKey, parseDollars, readGoal, removeManualEntry, wholeDollars, exactDollars } from "../goal.js";
import { env, fakeKV, freezeClock, goalEntry } from "./fakes.js";

beforeEach(() => forgetGoalCache());

test("monthKey is the UTC month", () => {
    assert.equal(monthKey(new Date("2026-10-31T23:59:59Z")), "2026-10");
    assert.equal(monthKey(new Date("2026-11-01T00:00:00Z")), "2026-11");
});

test("the target comes from GOAL_CENTS, $100 if it's missing or junk", () => {
    assert.equal(goalTarget({ GOAL_CENTS: "10000" }), 10000);
    assert.equal(goalTarget({ GOAL_CENTS: "25000" }), 25000);
    assert.equal(goalTarget({}), 10000);
    assert.equal(goalTarget({ GOAL_CENTS: "-3" }), 10000);
});

test("goalView rounds down, never overstates, and caps the bar at 100%", () => {
    assert.deepEqual(goalView({ cents: 3799 }, 10000), { raised: "$37", target: "$100", percent: 37, text: "$37 of $100 this month" });
    assert.equal(goalView({ cents: 0 }, 10000).percent, 0);
    assert.equal(goalView({ cents: 9999 }, 10000).percent, 99);
    assert.equal(goalView({ cents: 25000 }, 10000).percent, 100);
    assert.equal(goalView({ cents: 25000 }, 10000).raised, "$250");
    assert.equal(wholeDollars(123456), "$1,234");
    assert.equal(exactDollars(1250), "$12.50");
});

test("parseDollars takes plain dollar amounts only", () => {
    assert.equal(parseDollars("5"), 500);
    assert.equal(parseDollars("12.5"), 1250);
    assert.equal(parseDollars(" $12.50 "), 1250);
    assert.equal(parseDollars("0.01"), 1);
    for (const bad of ["", "0", "0.00", "-1", "1,000", "1.234", "abc", "1e3", "1000001"]) assert.equal(parseDollars(bad), null, bad);
});

test("a month's total is the sum of its entries' metadata, across list pages, and nothing from other months", async () => {
    const entries = {};
    for (let i = 0; i < 1500; i++) Object.assign(entries, goalEntry("2026-10", "kofi", `tx${String(i).padStart(4, "0")}`, 100));
    Object.assign(entries, goalEntry("2026-10", "manual", "m1", 250), goalEntry("2026-09", "kofi", "old", 999), goalEntry("2026-100", "kofi", "x", 7));
    entries["goal:2026-10:kofi:junk"] = { value: "{}", metadata: { cents: "junk" } };
    const kv = fakeKV(entries);
    const goal = await readGoal(kv, "2026-10");
    assert.equal(goal.cents, 150250);
    assert.equal(goal.entries.length, 1502);
    assert.ok(kv.lists >= 2, "read more than one page");
    assert.equal((await readGoal(kv, "2026-08")).cents, 0);
});

test("manual entries: one key each, added with an id, removed by it; Ko-fi's entries can't be removed", async () => {
    const kv = fakeKV(goalEntry("2026-10", "kofi", "tx1", 500));
    const id = await addManualEntry(kv, "2026-10", { cents: 1000, source: "GitHub Sponsors", by: "owner@example.com" });
    assert.match(id, /^[0-9a-f]{8}$/);
    const meta = kv.map.get(`goal:2026-10:manual:${id}`).opts.metadata;
    assert.equal(meta.cents, 1000);
    assert.equal(meta.source, "GitHub Sponsors");
    assert.equal((await readGoal(kv, "2026-10")).cents, 1500);
    assert.equal(await removeManualEntry(kv, "2026-10", "missing"), false);
    assert.equal(await removeManualEntry(kv, "2026-10", "tx1"), false, "not a manual key");
    assert.equal(await removeManualEntry(kv, "2026-10", id), true);
    assert.equal((await readGoal(kv, "2026-10")).cents, 500);
});

test("the pages' goal is cached for a minute, and moves to the new month by itself", async () => {
    const clock = freezeClock(Date.UTC(2026, 9, 31, 23, 59, 0));
    try {
        const e = env({ ACCESS_KV: fakeKV(goalEntry("2026-10", "kofi", "a", 4000)) });
        assert.equal((await currentGoal(e)).cents, 4000);
        const more = goalEntry("2026-10", "kofi", "b", 5000);
        const [[key, { value, metadata }]] = Object.entries(more);
        await e.ACCESS_KV.put(key, value, { metadata });
        assert.equal((await currentGoal(e)).cents, 4000, "cached");
        clock.advance(30_000);
        assert.equal((await currentGoal(e)).cents, 4000);
        clock.advance(31_000); // now 2026-11-01 00:00:01
        const nov = await currentGoal(e);
        assert.equal(nov.month, "2026-11");
        assert.equal(nov.cents, 0);
    } finally {
        clock.restore();
    }
});

// JSONC to JSON: drops line and block comments outside strings, and trailing commas.
function stripJsonc(text) {
    let out = "";
    for (let i = 0; i < text.length; i++) {
        const c = text[i];
        if (c === '"') {
            let j = i + 1;
            while (j < text.length && text[j] !== '"') j += text[j] === "\\" ? 2 : 1;
            out += text.slice(i, j + 1);
            i = j;
        } else if (c === "/" && text[i + 1] === "/") {
            while (i < text.length && text[i] !== "\n") i++;
            out += "\n";
        } else if (c === "/" && text[i + 1] === "*") {
            i = text.indexOf("*/", i + 2) + 1;
        } else out += c;
    }
    return out.replace(/,(\s*[\]}])/g, "$1");
}

// The built pages and the Worker share a contract: the data-* blanks the Worker fills. This checks the real
// build (npm run build first; skipped without it), so renaming a blank on one side can't go unnoticed.
const DIST = new URL("../../dist/", import.meta.url);
const built = existsSync(new URL("gate/front.html", DIST));

test("the built gate pages have every blank the Worker fills, and no scripts", { skip: !built && "run npm run build first" }, () => {
    const page = (name) => readFileSync(new URL(name, DIST), "utf8");
    const expect = {
        "gate/front.html": ['data-if="goal"', 'data-goal="raised"', 'data-goal="target"', 'data-goal="bar"', 'data-goal="fill"', 'action="/access"', 'href="/request"', 'data-value="next"'],
        "gate/sent.html": ['data-fill="email"', 'data-value="email"', 'data-value="next"', 'action="/access/verify"', 'autocomplete="one-time-code"'],
        "gate/code-failed.html": ['data-value="email"', 'data-value="next"', 'action="/access/verify"', 'action="/access"'],
        "gate/request.html": ['action="/request"', 'name="note"'],
        "gate/requested.html": [],
        "gate/signed-out.html": [],
        "gate/slow-down.html": [],
        "gate/problem.html": ['data-fill="title"', 'data-fill="message"'],
        "gate/admin.html": ['data-fill="admin-email"', 'data-fill="flash"', 'data-if="hash-mismatch"', 'data-html="requests"', 'data-html="pager"', 'data-html="entries"', 'data-fill="goal-exact"', 'data-fill="month"', 'data-fill="request-count"', 'data-if="no-requests"', 'data-if="has-entries"', 'value="donation"', 'value="add"', 'value="remove"'],
        "index.html": ['data-if="goal"', 'data-goal="raised"', 'action="/signout"', 'data-if="signed-in"', 'data-if="signed-out"', 'action="/access"', 'href="/request"', 'href="/player"'],
    };
    for (const [file, markers] of Object.entries(expect)) {
        const html = page(file);
        for (const m of markers) assert.ok(html.includes(m), `${file} is missing ${m}`);
        assert.doesNotMatch(html, /<script(?![^>]*type="application\/ld\+json")/i, `${file} has a script`);
    }
    const gate = readdirSync(new URL("gate/", DIST)).sort();
    assert.deepEqual(gate, Object.keys(expect).filter((f) => f.startsWith("gate/")).map((f) => f.slice(5)).sort(), "a gate page with no test");
    for (const file of gate.filter((f) => f !== "front.html")) assert.match(page(`gate/${file}`), /<meta name="robots" content="noindex"/, file);
    assert.doesNotMatch(page("sitemap-0.xml"), /gate/);
});

test("every Worker route is in run_worker_first, and the public files aren't", () => {
    const raw = readFileSync(new URL("../../wrangler.jsonc", import.meta.url), "utf8");
    const config = JSON.parse(stripJsonc(raw));
    const patterns = config.assets.run_worker_first;
    const glob = (p) => new RegExp(`^${p.replace(/[.+?^${}()|[\]\\]/g, "\\$&").replace(/\*/g, ".*")}$`);
    const runsWorker = (path) => patterns.some((p) => !p.startsWith("!") && glob(p).test(path)) && !patterns.some((p) => p.startsWith("!") && glob(p.slice(1)).test(path));
    for (const path of ["/", "/access", "/access/verify", "/request", "/signout", "/admin", "/player", "/gate/front", "/404", "/a-page-added-later"]) assert.ok(runsWorker(path), path);
    for (const path of ["/_astro/x.css", "/favicon.ico", "/robots.txt", "/sitemap-index.xml", "/privacy", "/social-preview.jpg"]) assert.ok(!runsWorker(path), path);
    assert.equal(config.main, "worker/index.js");
    assert.equal(config.assets.binding, "ASSETS");
    // Least privilege: the early-access namespace only, never the tip jar's STASH_KV (the app's supporters list, the relay config).
    assert.deepEqual(config.kv_namespaces.map((k) => k.binding), ["ACCESS_KV"]);
    assert.doesNotMatch(raw, /fca1bc38b42741e6a8cac11f54de5abd/, "STASH_KV's id must not appear");
    assert.match(config.kv_namespaces[0].id, /^[0-9a-f]{32}$/, "the stash-early-access namespace id");
    // Previews get nothing but assets: no production KV or email (Cloudflare's Previews don't inherit bindings).
    assert.deepEqual(config.previews, {});
    assert.equal(config.vars.GOAL_CENTS, "10000");
    // The web player's address is a plain var; its signing key is a secret and must never be in this file.
    assert.equal(config.vars.PLAYER_URL, "https://play.stashfm.app");
    assert.ok(!("PLAYER_TICKET_PRIVATE_KEY" in config.vars));
    assert.doesNotMatch(raw, /"d"\s*:/, "no private JWK in wrangler.jsonc");
    assert.deepEqual(config.routes, [{ pattern: "stashfm.app/*", zone_name: "stashfm.app" }], "the site takes the rest of stashfm.app; the share Worker keeps its own, more specific routes");
    const ids = config.ratelimits.map((r) => Number(r.namespace_id));
    assert.ok(ids.every((id) => id >= 2010 && id <= 2015), "2001-2009 belong to the share Worker");
    assert.equal(new Set(ids).size, ids.length);
    assert.deepEqual(config.ratelimits.map((r) => r.name).sort(), ["CODE_SEND_RL", "REQUEST_IP_RL", "SEND_EMAIL_RL", "SEND_IP_RL", "VERIFY_EMAIL_RL", "VERIFY_IP_RL"]);
});
