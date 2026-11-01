/**
 * GET /goal.json: this month's goal as data, for the app's Support card (and anything else that wants the number the
 * front page shows). The same source as the page's bar: currentGoal() over ACCESS_KV's goal:<month>:* keys, against
 * GOAL_CENTS. Public (the front page already shows it to everyone), so it answers any origin (CORS *) and no cookie
 * changes it.
 *
 *   { "raised_cents": 16800, "goal_cents": 20000, "month": "2026-11" }
 *
 * Kept at the edge for 10 minutes (the Cache API, keyed on the bare path: the stashfm.app zone's Cache API ignores the
 * query string anyway), so however many phones ask, KV is listed at most once per 10 minutes per data centre. The app
 * asks at most once a day. When KV can't be read: a 503 that is never cached, and the app leaves the line out.
 */
import { currentGoal, goalTarget } from "./goal.js";

/** How long the edge and the browser keep an answer. */
export const GOAL_JSON_MAX_AGE_S = 600;

const CORS = {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
    "Access-Control-Max-Age": "86400",
};

/** The edge cache in a Worker; none elsewhere (tests pass their own). */
export const defaultEdgeCache = () => globalThis.caches?.default ?? null;

/** The body for [goal] (currentGoal()'s answer) against [target] cents. Never a negative total. */
export function goalJsonBody(goal, target) {
    const raised = Number.isInteger(goal.cents) ? Math.max(0, goal.cents) : 0;
    return { raised_cents: raised, goal_cents: target, month: goal.month };
}

const json = (body, status, cacheControl) =>
    new Response(JSON.stringify(body), {
        status,
        headers: { "content-type": "application/json; charset=utf-8", "Cache-Control": cacheControl, ...CORS },
    });

/**
 * Answers /goal.json. [method] is the request's (HEAD already read as GET). [later] runs work after the response
 * (ctx.waitUntil); [cache] is the edge cache, or null for none.
 */
export async function goalJson(request, env, method, later, cache) {
    if (method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
    if (method !== "GET") return new Response(null, { status: 405, headers: { Allow: "GET, HEAD, OPTIONS", ...CORS } });
    // One key for every spelling of the URL: no query, no cookies, always a GET.
    const key = new Request(new URL("/goal.json", request.url).toString(), { method: "GET" });
    if (cache) {
        try {
            const hit = await cache.match(key);
            if (hit) return hit;
        } catch (err) {
            console.error("goal.json cache read failed:", err && err.message);
        }
    }
    let goal;
    try {
        goal = await currentGoal(env);
    } catch (err) {
        console.error("goal.json unavailable:", err && err.message);
        return json({ error: "unavailable" }, 503, "no-store");
    }
    const res = json(goalJsonBody(goal, goalTarget(env)), 200, `public, max-age=${GOAL_JSON_MAX_AGE_S}`);
    if (cache) later(Promise.resolve(cache.put(key, res.clone())).catch((err) => console.error("goal.json cache write failed:", err && err.message)));
    return res;
}
