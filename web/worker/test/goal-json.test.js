import { beforeEach, test } from "node:test";
import assert from "node:assert/strict";
import worker, { handle } from "../index.js";
import { forgetGoalCache } from "../goal.js";
import { goalJsonBody, GOAL_JSON_MAX_AGE_S } from "../goal-json.js";
import { forgetAllAccess } from "../session.js";
import { env, fakeKV, freezeClock, get, goalEntry } from "./fakes.js";

const month = () => new Date().toISOString().slice(0, 7);

beforeEach(() => {
    forgetAllAccess();
    forgetGoalCache();
});

/** The edge cache as a Worker sees caches.default: match and put by URL, counted. */
function fakeEdgeCache() {
    const map = new Map();
    return {
        map,
        puts: 0,
        async match(req) {
            const hit = map.get(new URL(req.url).toString());
            return hit ? hit.clone() : undefined;
        },
        async put(req, res) {
            this.puts++;
            map.set(new URL(req.url).toString(), res.clone());
        },
    };
}

const goalEnv = (entries, over = {}) => env({ ACCESS_KV: fakeKV(entries), GOAL_CENTS: "20000", ...over });

test("/goal.json: this month's total and target from the same KV keys the front page reads", async () => {
    const e = goalEnv({ ...goalEntry(month(), "kofi", "tx1", 12800), ...goalEntry(month(), "manual", "m1", 4000), ...goalEntry("2020-01", "kofi", "old", 999) });
    const res = await handle(get("/goal.json"), e, undefined, { edgeCache: null });
    assert.equal(res.status, 200);
    assert.match(res.headers.get("content-type"), /^application\/json/);
    assert.deepEqual(await res.json(), { raised_cents: 16800, goal_cents: 20000, month: month() });
    // The page says the same: $168 of $200.
    const page = await (await handle(get("/"), e, undefined, { edgeCache: null })).text();
    assert.match(page, /<strong data-goal="raised">\$168<\/strong> of <span data-goal="target">\$200<\/span>/);
});

test("/goal.json is public: any origin may read it, no cookie, 10 minutes of cache", async () => {
    const res = await handle(get("/goal.json", { Origin: "https://example.com", Cookie: "stash_access=x" }), goalEnv(goalEntry(month(), "kofi", "a", 500)), undefined, { edgeCache: null });
    assert.equal(res.headers.get("access-control-allow-origin"), "*");
    assert.equal(res.headers.get("cache-control"), `public, max-age=${GOAL_JSON_MAX_AGE_S}`);
    assert.equal(GOAL_JSON_MAX_AGE_S, 600);
    assert.equal(res.headers.get("set-cookie"), null);
    assert.equal(res.headers.get("vary"), null);
});

test("/goal.json answers a CORS preflight and refuses writes", async () => {
    const pre = await handle(new Request("https://stashfm.app/goal.json", { method: "OPTIONS", headers: { Origin: "https://example.com" } }), goalEnv({}), undefined, { edgeCache: null });
    assert.equal(pre.status, 204);
    assert.equal(pre.headers.get("access-control-allow-origin"), "*");
    assert.match(pre.headers.get("access-control-allow-methods"), /GET/);
    const write = await handle(new Request("https://stashfm.app/goal.json", { method: "POST", body: "{}" }), goalEnv({}), undefined, { edgeCache: null });
    assert.equal(write.status, 405);
    assert.match(write.headers.get("allow"), /GET/);
});

test("/goal.json for an empty month is zero of the target, never a missing number", async () => {
    const res = await handle(get("/goal.json"), goalEnv({}), undefined, { edgeCache: null });
    assert.deepEqual(await res.json(), { raised_cents: 0, goal_cents: 20000, month: month() });
});

test("the body never goes below zero and takes the month from the goal", () => {
    assert.deepEqual(goalJsonBody({ month: "2026-11", cents: -500 }, 20000), { raised_cents: 0, goal_cents: 20000, month: "2026-11" });
    assert.deepEqual(goalJsonBody({ month: "2026-11", cents: 16899 }, 20000), { raised_cents: 16899, goal_cents: 20000, month: "2026-11" });
    assert.equal(goalJsonBody({ month: "2026-11", cents: "junk" }, 20000).raised_cents, 0);
});

test("/goal.json is kept at the edge: the next ask within the cache is answered without KV, whatever the query", async () => {
    const cache = fakeEdgeCache();
    const e = goalEnv(goalEntry(month(), "kofi", "a", 4000));
    const first = await handle(get("/goal.json"), e, undefined, { edgeCache: cache });
    assert.equal((await first.json()).raised_cents, 4000);
    assert.equal(cache.puts, 1);
    assert.deepEqual([...cache.map.keys()], ["https://stashfm.app/goal.json"]);
    // KV changes and goes away: the cached answer still comes back, for any spelling of the URL.
    e.ACCESS_KV.list = async () => {
        throw new Error("KV down");
    };
    forgetGoalCache();
    const again = await handle(get("/goal.json?v=2"), e, undefined, { edgeCache: cache });
    assert.equal(again.status, 200);
    assert.equal((await again.json()).raised_cents, 4000);
    assert.equal(cache.puts, 1);
});

test("/goal.json when KV can't be read: a 503 that nothing keeps", async () => {
    const cache = fakeEdgeCache();
    const kv = fakeKV();
    kv.get = kv.list = async () => {
        throw new Error("KV down");
    };
    const res = await handle(get("/goal.json"), env({ ACCESS_KV: kv }), undefined, { edgeCache: cache });
    assert.equal(res.status, 503);
    assert.equal(res.headers.get("cache-control"), "no-store");
    assert.equal(cache.puts, 0);
});

test("/goal.json follows the UTC month: a new month starts at zero", async () => {
    const clock = freezeClock(Date.UTC(2026, 9, 31, 23, 59, 30));
    try {
        const e = goalEnv(goalEntry("2026-10", "kofi", "oct", 19000));
        assert.deepEqual(await (await handle(get("/goal.json"), e, undefined, { edgeCache: null })).json(), { raised_cents: 19000, goal_cents: 20000, month: "2026-10" });
        clock.advance(61_000);
        forgetGoalCache();
        assert.deepEqual(await (await handle(get("/goal.json"), e, undefined, { edgeCache: null })).json(), { raised_cents: 0, goal_cents: 20000, month: "2026-11" });
    } finally {
        clock.restore();
    }
});

test("through the Worker's fetch, /goal.json keeps its CORS and gains the site's security headers", async () => {
    const ctx = { waitUntil() {} };
    const res = await worker.fetch(get("/goal.json"), goalEnv(goalEntry(month(), "kofi", "a", 100)), ctx);
    assert.equal(res.status, 200);
    assert.equal(res.headers.get("access-control-allow-origin"), "*");
    assert.equal(res.headers.get("x-content-type-options"), "nosniff");
});
