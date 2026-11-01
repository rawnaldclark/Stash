/**
 * Per-network daily budgets for new shared mixes and song links. The per-minute limits alone let one IPv4 address
 * store 7,200 mixes of up to 1 MB a day (about 7 GB of KV), which costs money every month on a paid plan and runs
 * into the free plan's storage and write caps for everyone within hours. A network is Community's (an IPv4 address,
 * or an IPv6 /56), salted and hashed with COMMUNITY_SALT; counts live in D1 (KV has no atomic increment), one row
 * per network, kind and UTC day, pruned by the daily cron.
 */
import { ip } from "./http.js";
import { sha256Hex } from "./store.js";

/** New records a network may create per UTC day. Far above one person's sharing, so only a shared NAT nears them. */
export const DAILY_NEW = { mix: 50, track: 300 };
const DAY_MS = 86_400_000;

/**
 * Takes one of [kind]'s daily budget for the request's network: true when there was one left. The check and the
 * increment are one statement, so creates at the same moment can't both slip under the cap.
 */
export async function takeDaily(request, env, kind, now = Date.now()) {
    // Unsalted, the stored IPv4 hashes could be reversed by trying every address: refuse instead (as Community does).
    if (!env.COMMUNITY_SALT) throw new Error("COMMUNITY_SALT is not set");
    const net = await sha256Hex(`${env.COMMUNITY_SALT}|${ip(request, env)}`);
    const row = await env.COMMUNITY_DB.prepare(
        `INSERT INTO share_quota (network, kind, day, n) VALUES (?1, ?2, ?3, 1)
         ON CONFLICT (network, kind, day) DO UPDATE SET n = n + 1 WHERE n < ?4
         RETURNING n`,
    ).bind(net, kind, Math.floor(now / DAY_MS), DAILY_NEW[kind]).first();
    return row !== null;
}

/** The daily cron: drops every count from before today. */
export const pruneDaily = (env, now) =>
    env.COMMUNITY_DB.prepare("DELETE FROM share_quota WHERE day < ?1").bind(Math.floor(now / DAY_MS)).run();

/** Seconds until the next UTC day, when the budget comes back: the Retry-After of a refused create. */
export const untilTomorrow = (now = Date.now()) => String(Math.ceil((DAY_MS - (now % DAY_MS)) / 1000));
