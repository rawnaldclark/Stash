/**
 * The monthly donation goal, against GOAL_CENTS. Every donation is its own ACCESS_KV key,
 * goal:<YYYY-MM>:<kind>:<id> (UTC month), with { cents, source, at, ... } as its metadata: the tip jar writes
 * kind "kofi" (infra/tipjar-worker/src/access.js), the admin page kind "manual" (GitHub Sponsors, PayPal). One
 * key per donation means the two Workers never overwrite each other. A month's total is the sum of a
 * list({ prefix: "goal:<YYYY-MM>:" }), page by page.
 *
 * Pages read it through currentGoal(), which keeps the answer for 60 seconds per Worker instance.
 */
import { clock } from "./crypto.js";
import { KEYS } from "./keys.js";

const CACHE_MS = 60 * 1000;
const DEFAULT_TARGET = 10000;
/** 50 pages of 1,000: far more donations than a month will see, but a bound all the same. */
const MAX_PAGES = 50;
let cached = null;

export const monthKey = (date) => date.toISOString().slice(0, 7);

export function goalTarget(env) {
    const n = Number.parseInt(env.GOAL_CENTS, 10);
    return Number.isInteger(n) && n > 0 ? n : DEFAULT_TARGET;
}

/** The month's entries ({ key, kind, id, cents, source, at, ... }, oldest first) and their total in cents. */
export async function readGoal(kv, month) {
    const prefix = KEYS.goalMonth(month);
    const entries = [];
    let cursor;
    for (let page = 0; page < MAX_PAGES; page++) {
        const res = await kv.list({ prefix, cursor });
        for (const { name, metadata } of res.keys) {
            const [kind, ...id] = name.slice(prefix.length).split(":");
            entries.push({ ...(metadata ?? {}), key: name, kind, id: id.join(":") });
        }
        if (res.list_complete || !res.cursor) break;
        cursor = res.cursor;
    }
    entries.sort((a, b) => String(a.at ?? "").localeCompare(String(b.at ?? "")));
    const cents = entries.reduce((sum, e) => sum + (Number.isInteger(e.cents) ? e.cents : 0), 0);
    return { month, cents, entries };
}

/** This month's goal, cached for a minute. */
export async function currentGoal(env) {
    const month = monthKey(new Date(clock.now()));
    if (cached && cached.month === month && cached.until > clock.now()) return cached.goal;
    const goal = await readGoal(env.ACCESS_KV, month);
    cached = { month, goal, until: clock.now() + CACHE_MS };
    return goal;
}

export const forgetGoalCache = () => {
    cached = null;
};

/** A donation added by hand. Returns its id. */
export async function addManualEntry(kv, month, { cents, source, by }) {
    const id = crypto.randomUUID().slice(0, 8);
    const entry = { cents, source, at: new Date(clock.now()).toISOString(), by };
    await kv.put(KEYS.goalEntry(month, "manual", id), JSON.stringify(entry), { metadata: entry });
    forgetGoalCache();
    return id;
}

/** Takes a hand-added entry back out; false if there's no such entry. Ko-fi's entries can't be removed here. */
export async function removeManualEntry(kv, month, id) {
    if (!/^[0-9a-f]{8}$/.test(String(id))) return false;
    const key = KEYS.goalEntry(month, "manual", id);
    if ((await kv.get(key)) === null) return false;
    await kv.delete(key);
    forgetGoalCache();
    return true;
}

/** "12.50" or "12" or "$12.50" in dollars to whole cents; null for anything else, zero or over $100,000. */
export function parseDollars(input) {
    const m = /^\$?\s*(\d{1,6})(?:\.(\d{1,2}))?$/.exec(String(input ?? "").trim());
    if (!m) return null;
    const cents = Number(m[1]) * 100 + Number((m[2] ?? "0").padEnd(2, "0"));
    return cents > 0 && cents <= 10_000_000 ? cents : null;
}

/** Whole dollars, rounded down, so the page never says more came in than did: 3799 -> "$37". */
export const wholeDollars = (cents) => `$${Math.floor(Math.max(0, cents) / 100).toLocaleString("en-US")}`;

/** Exact dollars for the admin page: 3799 -> "$37.99". */
export const exactDollars = (cents) => `${cents < 0 ? "-" : ""}$${(Math.abs(cents) / 100).toFixed(2)}`;

/** What the goal bar shows. */
export function goalView(goal, target) {
    const percent = Math.min(100, Math.floor((Math.max(0, goal.cents) / target) * 100));
    const raised = wholeDollars(goal.cents);
    const of = wholeDollars(target);
    return { raised, target: of, percent, text: `${raised} of ${of} this month` };
}
