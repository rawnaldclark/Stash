/**
 * Stash Lossless Relay — Cloudflare Worker
 *
 * Mints short-lived Qobuz FLAC URLs for the Stash app from a small pool of
 * operator-owned accounts. The device streams from Qobuz's CDN directly; audio
 * never transits here (spec §4). The wire contract in spec §1-2 is enforced by
 * the shipped client, so every status below means exactly one thing to it:
 *   200 mint · 404 not available (ends the track) · 503 busy (base cools 60 s)
 *   anything else: base cools 5 min. Never 200 with an error body. A 401 carries X-Stash-Server-Time.
 *
 * Endpoints:
 *   GET /v1/qobuz/file?track_id=&format_id=   signed (X-Stash-Install/-Ts/-Auth), X-Stash-Version: 1,
 *                                             optional X-Stash-Purpose: stream | download
 *   GET /v1/quota                             signed (X-Stash-Install/-Ts/-Auth over "<install>:quota:<ts>"):
 *                                             today's daily lossless count, not counted itself
 *   GET /v1/status                            reachability probe for the Settings "Test" button
 *
 * State: D1 (accounts' rotation state, the shared mint cache, daily quotas), plus QUOTA_DB: the daily
 * lossless counter shared with the Tidal relay (src/quota.js). A lossless mint answered (a cache HIT
 * included) costs the install one unit; at FREE_LOSSLESS_DAILY it is answered 429 daily_lossless_cap.
 * A valid supporter token (optional header X-Stash-Entitlement, on mints and /v1/quota) lifts that limit and
 * INSTALL_DAILY_CAP, up to SUPPORTER_DAILY_CAP a day per token; the network, /48 and global caps still apply.
 * Secrets: RELAY_KEY, RELAY_KEY_PREV (optional), QOBUZ_ACCOUNTS, QOBUZ_ACCOUNTS_EXTRA and
 *          QOBUZ_ACCOUNTS_RECOVERED, QOBUZ_ACCOUNTS_RECOVERED_2 and QOBUZ_ACCOUNTS_RECOVERED_3 (optional additive arrays).
 *          Deploy: see README.md.
 */
import { createHmac } from "node:crypto";
import { verifyMint, verifyQuota } from "./auth.js";
import { readStanding, spendLossless, losslessHeaders, capAnswer, quotaBody, pruneUsage } from "./quota.js";
import { mintFromQobuz } from "./qobuz.js";
import {
    getCached, putCachedStmt, readQuota, readQuotas, bumpQuotaStmt,
    selectAccount, ensureAccounts, coolAccountStmt, killAccount, prune, dayKey,
} from "./db.js";

/** The lossless formats the client asks for (LosslessQualityTier → 6 / 7 / 27). Data Saver's 5 (MP3 320) is answered 404 below. */
const LOSSLESS_FORMATS = new Set([6, 7, 27]);
/** A transiently failing account sits out this long. */
const TRANSIENT_COOL_S = 300;
/** Two 3 s upstream attempts plus the D1 round trips fit inside the client's 8 s budget (spec §1). */
const MAX_ATTEMPTS = 2;
/** An account that refused a track another account then served sits out this long (never killed: see below). */
const REFUSAL_COOL_S = 3600;

/**
 * A refusal that names the track (Qobuz's 404 catalog miss, or a Track…/Sample…/Format… restriction
 * such as SampleRestrictedByRightHolders) is about the track, whichever account asks. One with no
 * such code may be the account itself (an expired or limited subscription answering with previews),
 * so the relay asks one more account before answering 404.
 */
export function refusalNamesTrack(reason) {
    const r = reason || "";
    // A User… code (e.g. UserUncredentialed, seen on a weak account 2026-07-04) is about the account
    // even when a Format… code rides along with it.
    return r === "404" || (!/\bUser/.test(r) && /\b(Track|Sample|Format)/.test(r));
}
/** Per D1 binding: the account-secret values whose labels already have rows. One cheap batch per isolate. */
const ensured = new WeakMap();

export default {
    fetch: (request, env) => handle(request, env, globalThis.fetch, Math.floor(Date.now() / 1000)),
    scheduled: async (event, env) => {
        const nowSec = Math.floor(Date.now() / 1000);
        await prune(env.DB, nowSec);
        await pruneUsage(env, nowSec); // never throws
    },
};

/**
 * The whole flow with fetch and the clock injectable, so it runs under node:test.
 * Any throw (a D1 error mid-request) is answered 503 busy: the base cools 60 s and the device tries
 * the next one. Uncaught, it became Cloudflare's bare 500 page, which the client reads as "broken"
 * and cools for 5 minutes.
 */
export async function handle(request, env, fetchImpl, nowSec) {
    const url = new URL(request.url);
    if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405);
    if (url.pathname === "/v1/status") return json({ ok: true }, 200);
    if (url.pathname !== "/v1/qobuz/file" && url.pathname !== "/v1/quota") return json({ error: "not_found" }, 404);
    try {
        if (url.pathname === "/v1/quota") return await quota(request, env, nowSec);
        return await mint(request, url, env, fetchImpl, nowSec);
    } catch (e) {
        console.error(e); // visible in `wrangler tail`
        return json({ error: "busy" }, 503, { "Retry-After": "60" });
    }
}

async function mint(request, url, env, fetchImpl, nowSec) {
    const h = (name) => request.headers.get(name) || "";
    if (h("X-Stash-Version") !== "1") return json({ error: "bad_version" }, 400);
    const trackRaw = url.searchParams.get("track_id") || "";
    const formatId = Number(url.searchParams.get("format_id"));
    if (!/^\d{1,12}$/.test(trackRaw) || !Number.isInteger(formatId) || formatId <= 0) return json({ error: "bad_request" }, 400);
    const trackId = Number(trackRaw);
    if (!env.RELAY_KEY) return json({ error: "server_misconfigured", message: "RELAY_KEY not set" }, 500);

    const install = h("X-Stash-Install");
    const signed = verifyMint({ install, ts: h("X-Stash-Ts"), auth: h("X-Stash-Auth") }, trackId, formatId, nowSec, [env.RELAY_KEY, env.RELAY_KEY_PREV]);
    // The server's clock rides on the 401 so a device whose clock drifted past ±MAX_SKEW_S can correct its
    // X-Stash-Ts and retry. It tells nothing a caller couldn't learn from the Date header; the window stays.
    if (!signed) return json({ error: "unauthorized" }, 401, { "X-Stash-Server-Time": String(nowSec) });

    // Data Saver asks for format 5 (MP3 320). The relay serves lossless only, and the client's
    // router discards any relay format below 6 anyway, so a well-formed non-lossless format is
    // answered 404: no cooldown, no Qobuz call, no quota, and the device falls to its lossy rung
    // (JioSaavn AAC 320) exactly as Save Data intends. 400 is reserved for malformed input.
    if (!LOSSLESS_FORMATS.has(formatId)) return json({ error: "not_available", message: "lossless only" }, 404);

    // Per-IP cap (spec §5.4), counted per network rather than per full address: see networkOf.
    // Guarded: some local-dev setups have no binding.
    const network = networkOf(h("CF-Connecting-IP"));
    if (env.MINT_RL) {
        const { success } = await env.MINT_RL.limit({ key: network });
        if (!success) return json({ error: "rate_limited" }, 429, { "Retry-After": "60" });
    }

    // The daily lossless counter, shared with the Tidal relay. Checked before any answer: a cache HIT
    // is still a lossless stream for the user, so it counts too. Off (null) without QUOTA_DB.
    const standing = await readStanding(request, env, "i:" + install, nowSec);
    if (standing?.over) {
        const cap = capAnswer(standing, nowSec);
        return json(cap.body, cap.status, cap.headers);
    }

    const cached = await getCached(env.DB, trackId, formatId, nowSec);
    if (cached) return ok(cached.url, cached.got_format_id, cached.bit_depth, cached.sample_rate, "HIT", losslessHeaders(await spendLossless(standing)));

    const day = dayKey(nowSec);
    const caps = vars(env);
    // Exhaustion is 503, never 404 (spec §5.5). Read-then-bump can overshoot by a few under
    // concurrency; that is harmless and saves a write on every rejected request.
    const usedGlobal = await readQuota(env.DB, day, "global");
    if (usedGlobal >= caps.global) {
        console.log(`503 global daily cap ${caps.global} reached`);
        return json({ error: "busy" }, 503, { "Retry-After": "600" });
    }
    // Streams first. A download can wait and a listener cannot, so a download is only served
    // while the day's usage is at or behind a straight pace line (plus a little headroom) —
    // the pool then lasts to midnight UTC for the people pressing play, and downloads take
    // whatever the pace leaves. Unlabelled requests (older apps) count as streams. 429 +
    // "paced" is only ever sent to a client that labelled the request, so it knows not to cool
    // the relay; it retries after Retry-After. No quota, no Qobuz call.
    if (h("X-Stash-Purpose") === "download") {
        const wait = pacedWaitSec(usedGlobal, caps, nowSec);
        if (wait > 0) return json({ error: "paced" }, 429, { "Retry-After": String(wait) });
    }
    // The install's daily cap, then its network's: the install id is client-chosen, so a script that
    // rotates it meets the network cap instead. An IPv6 caller also meets its /48's aggregate cap
    // (network48Of): one free tunnel-broker /48 holds 256 /56s, each with its own network cap.
    // Every counter, and the free-404 tallies (spendStmts), come back in one read. No IP_SALT, no
    // network tag: the network caps are off. A supporter (a valid token under its own daily ceiling, see
    // quota.js) skips only the install cap, which would otherwise stop "unlimited" at INSTALL_DAILY_CAP; the
    // network, /48 and global caps still apply to supporters (owner's decision, 2026-10-17).
    const tag = ipTag(env, network);
    const tag48 = ipTag(env, network48Of(h("CF-Connecting-IP")));
    const keys = {
        install: "i:" + install, network: tag && "n:" + tag, free404: tag && "nf:" + tag,
        network48: tag48 && "w:" + tag48, free404w: tag48 && "wf:" + tag48,
    };
    const used = await readQuotas(env.DB, day, Object.values(keys).filter(Boolean));
    if ((!standing?.supporter && used[keys.install] >= caps.install) || (tag && used[keys.network] >= caps.network) || (tag48 && used[keys.network48] >= caps.network48)) {
        return json({ error: "rate_limited" }, 429, { "Retry-After": "3600" });
    }

    const accounts = parseAccounts(env);
    // Every label needs a D1 row BEFORE selection, or the LRU never reaches an account added to the
    // secret while the old ones are still under their caps. Once per isolate per secret value.
    const accountSecretVersion = JSON.stringify([env.QOBUZ_ACCOUNTS || "", env.QOBUZ_ACCOUNTS_EXTRA || "", env.QOBUZ_ACCOUNTS_RECOVERED || "", env.QOBUZ_ACCOUNTS_RECOVERED_2 || "", env.QOBUZ_ACCOUNTS_RECOVERED_3 || ""]);
    if (ensured.get(env.DB) !== accountSecretVersion) {
        await ensureAccounts(env.DB, accounts.map((a) => a.label));
        ensured.set(env.DB, accountSecretVersion);
    }
    let refusedBy = null; // an account that refused this track without naming the track
    let noSecondOpinion = false; // refusedBy is set and no other account was free to cross-check it
    for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
        const label = await selectAccount(env.DB, nowSec, caps, refusedBy, accounts.map((a) => a.label), caps.uncappedLabels, caps.noCooldownLabels);
        const account = accounts.find((a) => a.label === label);
        if (!account) { noSecondOpinion = Boolean(refusedBy); break; } // nothing live under its caps → 503 below
        const r = await mintFromQobuz(fetchImpl, account, trackId, formatId, nowSec);
        // Country/colo: the Worker runs — and calls Qobuz from — the colo nearest the phone, so
        // if Qobuz applies rights by calling IP, refusals will follow the colo, not the account.
        const cf = request.cf || {};
        console.log(`mint track=${trackId} fmt=${formatId} acct=${label} install=${install.slice(0, 8)} purpose=${h("X-Stash-Purpose") || "-"} cc=${cf.country || "?"} colo=${cf.colo || "?"} -> ${r.kind}${r.reason ? " " + r.reason : ""}`);
        if (r.kind === "locked" && !refusedBy && attempt + 1 < MAX_ATTEMPTS && !refusalNamesTrack(r.reason)) {
            refusedBy = label;
            continue;
        }
        if (r.kind === "ok" || r.kind === "locked") {
            const free404 = r.kind === "locked" && r.reason === "404" && Boolean(tag) && used[keys.free404] < caps.free404
                && (!tag48 || used[keys.free404w] < caps.free404w);
            // A free 404 keeps the account slot selectAccount stamped: it was a real Qobuz call.
            const writes = spendStmts(env.DB, day, keys, tag, free404);
            if (r.kind === "ok" && refusedBy) {
                // Another account served what refusedBy would not: the refusal was about that account.
                // Cool it, never kill it (a per-track signal must not retire an account, 2026-09-05).
                console.log(`cross-check track=${trackId}: ${refusedBy} refused, ${label} served -> cooling ${refusedBy} ${REFUSAL_COOL_S}s`);
                writes.push(coolAccountStmt(env.DB, refusedBy, nowSec + REFUSAL_COOL_S));
            }
            if (r.kind === "ok" && r.etsp) writes.push(putCachedStmt(env.DB, trackId, formatId, r)); // no etsp → serve once, never cache
            await env.DB.batch(writes);
            if (r.kind !== "ok") return json({ error: "not_available" }, 404); // a 404 never counts as lossless
            return ok(r.url, r.formatId, r.bitDepth, r.sampleRateHz, "MISS", losslessHeaders(await spendLossless(standing)));
        }
        if (r.kind === "dead") await killAccount(env.DB, label, r.reason);
        // A transient reached Qobuz and cools an account, so it costs the caller's network one unit
        // (2026-10-17 audit: free, a network could keep the pool cooling at no cost to itself).
        else await env.DB.batch([coolAccountStmt(env.DB, label, nowSec + TRANSIENT_COOL_S), ...networkStmts(env.DB, day, keys)]);
    }
    if (noSecondOpinion && accounts.length > 1) {
        // The cross-check never ran: every other account was cooling, capped or dead. A 404 here is cached
        // on the device as "not available" for good, so answer busy and let it ask again later. The
        // refusal was a real Qobuz call, so it costs the network one unit, like a transient. (A one-account
        // pool has no second opinion to wait for: its refusal stands, below.)
        const charge = networkStmts(env.DB, day, keys);
        if (charge.length) await env.DB.batch(charge); // none without IP_SALT, and D1 refuses an empty batch
        return json({ error: "busy" }, 503, { "Retry-After": "60" });
    }
    if (refusedBy) {
        // The one refusal we got stands: answer it as before (404, quota spent) rather than 503.
        await env.DB.batch(spendStmts(env.DB, day, keys, tag, false));
        return json({ error: "not_available" }, 404);
    }
    return json({ error: "busy" }, 503, { "Retry-After": "60" });
}

/**
 * GET /v1/quota: where the install stands today, for the app's cold start. Signed like a mint but over
 * "<install>:quota:<ts>", behind the same per-network limiter, and never counted. 404 quota_off when the
 * counter is off (no QUOTA_DB): the same status an older relay without the route gives.
 */
async function quota(request, env, nowSec) {
    const h = (name) => request.headers.get(name) || "";
    if (!env.RELAY_KEY) return json({ error: "server_misconfigured", message: "RELAY_KEY not set" }, 500);
    const install = h("X-Stash-Install");
    if (!verifyQuota({ install, ts: h("X-Stash-Ts"), auth: h("X-Stash-Auth") }, nowSec, [env.RELAY_KEY, env.RELAY_KEY_PREV])) {
        return json({ error: "unauthorized" }, 401, { "X-Stash-Server-Time": String(nowSec) });
    }
    if (env.MINT_RL) {
        const { success } = await env.MINT_RL.limit({ key: networkOf(h("CF-Connecting-IP")) });
        if (!success) return json({ error: "rate_limited" }, 429, { "Retry-After": "60" });
    }
    const standing = await readStanding(request, env, "i:" + install, nowSec);
    if (!standing) return json({ error: "quota_off" }, 404);
    return json(quotaBody(standing), 200, { ...losslessHeaders(standing), "Cache-Control": "no-store" });
}

/**
 * Seconds until a download may be served: 0 when `used` is at or under the pace line
 * `global × (fraction of the UTC day elapsed + headroom)`, else the time the line takes to
 * reach `used`, plus a minute. Never past midnight: the global cap (checked first) keeps
 * `used` under `global`, so the line always gets there today.
 */
export function pacedWaitSec(used, caps, nowSec) {
    const elapsed = nowSec % 86400;
    const reachedAt = (used / caps.global - caps.paceHeadroomPct / 100) * 86400;
    return reachedAt <= elapsed ? 0 : Math.ceil(reachedAt - elapsed) + 60;
}

/**
 * The quota writes for one answered mint (a 200, or a 404 Qobuz gave). Every answer counts against
 * its install, as it always has (the install row also carries the network tag for the report).
 * Normally it also spends the shared `global` cap and the network's `n:` cap.
 *
 * `free404`: Qobuz answered 404, i.e. it has never heard of the id, and the network still has some
 * of its daily allowance of those (`nf:`, and for IPv6 its /48's `wf:`). Such a lookup bumps those
 * tallies instead of the network's `n:`/`w:` caps: honest clients ask for dead ids, and that must
 * not eat their network's day. It still spends `global`, and keeps the account's hourly/daily slot,
 * because it was a real Qobuz call (2026-10-17 audit: refunding both left 404 traffic bounded by
 * nothing but the per-network allowance, so made-up ids hit Qobuz without limit, scaling with
 * networks). Past the allowance a 404 counts like any other answer, so the network's daily cap still
 * bounds what one caller takes.
 */
function spendStmts(db, day, keys, tag, free404) {
    const writes = [bumpQuotaStmt(db, day, keys.install, tag), bumpQuotaStmt(db, day, "global")];
    if (free404) return [...writes, ...[keys.free404, keys.free404w].filter(Boolean).map((k) => bumpQuotaStmt(db, day, k))];
    return [...writes, ...networkStmts(db, day, keys)];
}

/** The caller's network counters: its `n:` cap and, for IPv6, its /48's `w:` aggregate. Empty without IP_SALT. */
function networkStmts(db, day, keys) {
    return [keys.network, keys.network48].filter(Boolean).map((k) => bumpQuotaStmt(db, day, k));
}

/**
 * The network a caller's address belongs to, which the per-IP limiter, the per-network caps and the
 * abuse report all count in: an IPv4 address as is (IPv4-mapped IPv6 too), an IPv6 address by its
 * /56. Homes, VPSes and tunnel brokers routinely give one customer a /56 or a /48, so keying on the
 * full address (as before 2026-10-11) let one host rotate source addresses and get a fresh 40/min
 * bucket, and a fresh report row, for each. The same /56 as the share Worker's Community `networkOf`;
 * this Worker deploys on its own, so it keeps its own copy. Not standard prefix notation
 * (`2001:db8:aa:bb/56`): changing the form re-keys every network counter.
 */
export function networkOf(addr) {
    if (!addr) return "?";
    if (!addr.includes(":") || addr.includes(".")) return addr; // IPv4, or IPv4-mapped IPv6
    const [head, tail] = addr.toLowerCase().split("::");
    const left = head ? head.split(":") : [];
    const right = tail ? tail.split(":") : [];
    const groups = tail === undefined ? left : [...left, ...Array(Math.max(0, 8 - left.length - right.length)).fill("0"), ...right];
    const g = [0, 1, 2, 3].map((i) => parseInt(groups[i] || "0", 16) || 0);
    return `${g[0].toString(16)}:${g[1].toString(16)}:${g[2].toString(16)}:${(g[3] >> 8).toString(16)}/56`;
}

/**
 * The /48 an IPv6 caller's /56 sits in, for the aggregate `w:` cap, or null for IPv4 (and IPv4-mapped
 * IPv6) and a missing address: an IPv4 caller has no /48 and is capped by its address alone. Same
 * normalisation as networkOf, so every way of writing an address lands on one key.
 */
export function network48Of(addr) {
    const net = networkOf(addr);
    if (!net.endsWith("/56")) return null;
    return net.split(":").slice(0, 3).join(":") + "/48";
}

/**
 * A short salted hash of the caller's network (networkOf) for the per-network caps and the abuse
 * report (many installs behind one network is how a scraper rotating install ids shows up), or null
 * when IP_SALT is unset, which turns the per-network caps off. The salt is a Worker secret, never the
 * relay key: that key is public, and with it the whole IPv4 space would reverse in minutes.
 */
function ipTag(env, network) {
    if (!env.IP_SALT) {
        // Off by design (local dev), but in production it silently drops every network cap: say so once per isolate.
        if (!warnedNoSalt) { warnedNoSalt = true; console.warn("IP_SALT not set: per-network caps are OFF"); }
        return null;
    }
    return network && network !== "?" ? createHmac("sha256", env.IP_SALT).update(network).digest("hex").slice(0, 16) : null;
}
let warnedNoSalt = false;

function vars(env) {
    const n = (v, d) => (Number.parseInt(v, 10) > 0 ? Number.parseInt(v, 10) : d);
    const c = {
        global: n(env.GLOBAL_DAILY_CAP, 600),
        install: n(env.INSTALL_DAILY_CAP, 100),
        // Per network (only with IP_SALT): a few installs' worth, so a household or a carrier NAT still fits.
        network: n(env.NETWORK_DAILY_CAP, 400),
        // Qobuz 404s (ids it doesn't know) a network gets each day without spending its own network cap.
        free404: n(env.NETWORK_FREE_404_DAILY, 50),
    };
    return {
        ...c,
        // An IPv6 /48 together (256 /56s): four networks' worth, so a site with a few subnets still fits.
        network48: n(env.NETWORK48_DAILY_CAP, 4 * c.network),
        free404w: n(env.NETWORK48_FREE_404_DAILY, 4 * c.free404),
        hourly: n(env.ACCOUNT_HOURLY_CAP, 20),
        daily: n(env.ACCOUNT_DAILY_CAP, 200),
        // These account labels bypass only the per-account hourly/daily selection limits.
        // Global, install, network, and request-rate caps continue to apply.
        uncappedLabels: String(env.ACCOUNT_UNCAPPED_LABELS || "").split(",").map((label) => label.trim()).filter(Boolean),
        // Normally transient upstream errors cool an account for five minutes; these labels skip that delay.
        noCooldownLabels: String(env.ACCOUNT_NO_COOLDOWN_LABELS || "").split(",").map((label) => label.trim()).filter(Boolean),
        // "0" is a legitimate headroom, so read it without n()'s > 0 rule.
        paceHeadroomPct: Number.isFinite(Number.parseInt(env.DOWNLOAD_PACE_HEADROOM_PCT, 10)) ? Number.parseInt(env.DOWNLOAD_PACE_HEADROOM_PCT, 10) : 10,
    };
}

/** Account secrets are operator-written JSON arrays; malformed values degrade to no entries, not a 500 loop. */
function parseAccounts(env) {
    return [env.QOBUZ_ACCOUNTS, env.QOBUZ_ACCOUNTS_EXTRA, env.QOBUZ_ACCOUNTS_RECOVERED, env.QOBUZ_ACCOUNTS_RECOVERED_2, env.QOBUZ_ACCOUNTS_RECOVERED_3].flatMap((raw) => {
        try {
            const a = JSON.parse(raw || "[]");
            return Array.isArray(a) ? a.filter((x) => x && x.label && x.token && x.app_id && x.app_secret) : [];
        } catch {
            return [];
        }
    });
}

/**
 * The spec §1 success shape. sample_rate is already Hz. `no-store`: the URL is short-lived and per-request headers gate it.
 * `extra`: the daily lossless counter headers (none with the counter off).
 */
function ok(url, formatId, bitDepth, sampleRateHz, cache, extra = {}) {
    return json({ url, format_id: formatId, bit_depth: bitDepth, sample_rate: sampleRateHz }, 200, { "X-Stash-Cache": cache, "Cache-Control": "no-store", ...extra });
}

function json(obj, status, extra = {}) {
    return new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json", ...extra } });
}
