/**
 * The monthly donation goal: goal:<YYYY-MM> (UTC month) = { cents, entries: [{ cents, source, at, ... }], updatedAt },
 * against GOAL_CENTS. The tip jar adds Ko-fi entries (infra/tipjar-worker/src/access.js); the admin page adds
 * GitHub Sponsors, PayPal and anything else by hand, and can take those back out.
 *
 * Pages read it through currentGoal(), which keeps the answer for 60 seconds per Worker instance.
 */
import { clock } from "./crypto.js";
import { KEYS } from "./keys.js";

const CACHE_MS = 60 * 1000;
const DEFAULT_TARGET = 10000;
let cached = null;

export const monthKey = (date) => date.toISOString().slice(0, 7);

export function goalTarget(env) {
    const n = Number.parseInt(env.GOAL_CENTS, 10);
    return Number.isInteger(n) && n > 0 ? n : DEFAULT_TARGET;
}

const total = (entries) => entries.reduce((sum, e) => sum + (Number.isInteger(e.cents) ? e.cents : 0), 0);

export async function readGoal(kv, month) {
    const goal = await kv.get(KEYS.goal(month), "json");
    const entries = Array.isArray(goal?.entries) ? goal.entries : [];
    return { month, cents: total(entries), entries, updatedAt: goal?.updatedAt ?? null };
}

/** This month's goal, cached for a minute. */
export async function currentGoal(env) {
    const month = monthKey(new Date(clock.now()));
    if (cached && cached.month === month && cached.until > clock.now()) return cached.goal;
    const goal = await readGoal(env.STASH_KV, month);
    cached = { month, goal, until: clock.now() + CACHE_MS };
    return goal;
}

export const forgetGoalCache = () => {
    cached = null;
};

async function write(kv, month, entries) {
    const goal = { cents: total(entries), entries, updatedAt: new Date(clock.now()).toISOString() };
    await kv.put(KEYS.goal(month), JSON.stringify(goal));
    forgetGoalCache();
    return goal;
}

/** A donation added by hand: { cents, source, at, by, id, manual: true }. */
export async function addManualEntry(kv, month, { cents, source, by }) {
    const goal = await readGoal(kv, month);
    const id = crypto.randomUUID().slice(0, 8);
    const entry = { cents, source, at: new Date(clock.now()).toISOString(), by, id, manual: true };
    return write(kv, month, [...goal.entries, entry]);
}

/** Takes a hand-added entry back out. Ko-fi's entries can't be removed here. */
export async function removeManualEntry(kv, month, id) {
    const goal = await readGoal(kv, month);
    const entries = goal.entries.filter((e) => !(e.manual && e.id === id));
    if (entries.length === goal.entries.length) return null;
    return write(kv, month, entries);
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
