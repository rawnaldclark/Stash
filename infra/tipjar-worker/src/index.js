/**
 * Stash Tip Jar — Cloudflare Worker
 *
 * Single endpoint, two methods:
 *   POST  → Ko-fi webhook receiver. Validates `verification_token`,
 *           appends the new supporter to KV, returns 200.
 *   GET   → Serves the current supporter list as JSON to the Stash
 *           Android app. Cached for 60s at the edge.
 *   GET /lossless.json and /lossless.json.sig → the signed lossless relay
 *           config, byte-for-byte from KV (see infra/lossless-relay).
 *
 * Storage: Cloudflare KV. One key, `supporters`, holding the JSON the
 * app reads. Bounded to the most recent SUPPORTERS_LIMIT entries to
 * avoid unbounded growth.
 *
 * Since 2026-10 a Donation or Subscription also gives the donor early access
 * to stashfm.app and counts toward the site's monthly goal (src/access.js,
 * in the separate ACCESS_KV namespace). The webhook URL and the GET JSON are
 * unchanged; the webhook answers 500 when that early-access step fails, so
 * Ko-fi retries it, and a retry never lists the same supporter twice.
 *
 * Why one Worker for both: Ko-fi webhook posts, app GETs. Routing by
 * HTTP method keeps it to one URL the user has to configure in two
 * places (Ko-fi dashboard + app's BuildConfig.SUPPORTERS_JSON_URL).
 *
 * Deploy: see ../README.md.
 */

import { dedupeId, KEYS, KOFI_TXN_TTL_S, recordKofiSupport, sameSecret } from "./access.js";

// 2026-07-19: 20 was destructively truncating — donors pushed past the
// cap were deleted from KV forever, which is why older donations vanished
// from the app's ticker. 500 entries is still a trivially small KV value.
const SUPPORTERS_LIMIT = 500;
const KV_KEY = "supporters";

export default {
    async fetch(request, env) {
        if (request.method === "POST") {
            return handleKofiWebhook(request, env);
        }
        if (request.method === "GET") {
            const path = new URL(request.url).pathname;
            if (path === "/lossless.json" || path === "/lossless.json.sig") {
                return serveLosslessConfig(path, env);
            }
            return serveSupporters(env);
        }
        return new Response("Method not allowed", { status: 405 });
    },
};

/**
 * Ko-fi webhook handler. Ko-fi sends `application/x-www-form-urlencoded`
 * with a `data` field containing JSON. The JSON's `verification_token`
 * MUST match `KOFI_VERIFICATION_TOKEN` (set as a Worker secret) — see
 * https://ko-fi.com/manage/webhooks for where to find that token.
 */
async function handleKofiWebhook(request, env) {
    // Without the secret every token would be "valid" (undefined === undefined).
    if (!env.KOFI_VERIFICATION_TOKEN) {
        console.error("KOFI_VERIFICATION_TOKEN is not set: refusing webhooks");
        return new Response("Not configured", { status: 500 });
    }
    let payload;
    try {
        const contentType = request.headers.get("content-type") || "";
        if (contentType.includes("application/x-www-form-urlencoded")) {
            const form = await request.formData();
            const raw = form.get("data");
            payload = JSON.parse(raw);
        } else {
            payload = await request.json();
        }
    } catch (err) {
        return new Response("Bad payload", { status: 400 });
    }

    if (!(await sameSecret(payload.verification_token, env.KOFI_VERIFICATION_TOKEN))) {
        return new Response("Invalid token", { status: 401 });
    }

    // Only count one-time donations and subscription payments. Skip
    // shop orders, commissions, etc. — those aren't tips.
    const allowedTypes = ["Donation", "Subscription"];
    if (!allowedTypes.includes(payload.type)) {
        return new Response("Ignored", { status: 200 });
    }

    // Ko-fi retries a webhook that didn't get a 200, with the same ids.
    // kofitxn:<id> is "listed" once the supporter is on the list and "done" once
    // early access and the goal are written too, so a retry repeats only what's left.
    const id = dedupeId(payload);
    const state = id ? await env.STASH_KV.get(KEYS.kofiTxn(id)) : null;
    if (state === "done") {
        return new Response("OK", { status: 200 });
    }
    if (state !== "listed") {
        await addSupporter(env, payload);
        if (id) await env.STASH_KV.put(KEYS.kofiTxn(id), "listed", { expirationTtl: KOFI_TXN_TTL_S });
    }

    // Early access and the monthly goal (src/access.js). If this fails, Ko-fi
    // gets a 500 and retries; the supporter above is already listed and won't
    // be added again.
    try {
        await recordKofiSupport(env, payload);
    } catch (err) {
        console.error("early access / goal update failed:", err && err.message);
        return new Response("Try again", { status: 500 });
    }
    if (id) await env.STASH_KV.put(KEYS.kofiTxn(id), "done", { expirationTtl: KOFI_TXN_TTL_S });

    return new Response("OK", { status: 200 });
}

/** Prepends the supporter to the list the app reads, exactly as before 2026-10. */
async function addSupporter(env, payload) {
    const newSupporter = {
        name: (payload.from_name || "Anonymous").slice(0, 40),
        amountUsd: Math.max(0, Math.floor(parseFloat(payload.amount) || 0)),
        message: (payload.message || "").slice(0, 280),
        timestamp: payload.timestamp || new Date().toISOString(),
    };

    // Read existing list, prepend new entry, cap at SUPPORTERS_LIMIT.
    const existingRaw = await env.STASH_KV.get(KV_KEY);
    const existing = existingRaw ? JSON.parse(existingRaw) : { supporters: [] };
    existing.supporters.unshift(newSupporter);
    existing.supporters = existing.supporters.slice(0, SUPPORTERS_LIMIT);

    await env.STASH_KV.put(KV_KEY, JSON.stringify(existing));
}

/**
 * GET handler — returns the current supporter list. Strips the
 * internal `timestamp` field (the app doesn't need it). 60-second
 * edge cache so a thousand cold-start app launches don't all hit KV.
 */
async function serveSupporters(env) {
    const existingRaw = await env.STASH_KV.get(KV_KEY);
    const data = existingRaw ? JSON.parse(existingRaw) : { supporters: [] };
    const stripped = {
        supporters: data.supporters.map((s) => ({
            name: s.name,
            amountUsd: s.amountUsd,
            message: s.message,
        })),
    };
    return new Response(JSON.stringify(stripped), {
        headers: {
            "Content-Type": "application/json",
            "Cache-Control": "public, max-age=60",
            "Access-Control-Allow-Origin": "*",
        },
    });
}

/**
 * The Stash lossless relay config (Plan B contract §3): the signed `lossless.json`
 * and its base64 ECDSA signature, written to KV by
 * infra/lossless-relay/scripts/publish-config.mjs. Served byte-for-byte — the device
 * verifies the signature over the exact bytes, so nothing here may re-encode them.
 * 404 until the first publish; a device then keeps whatever it has cached.
 */
async function serveLosslessConfig(path, env) {
    const isJson = path === "/lossless.json";
    const bytes = await env.STASH_KV.get(isJson ? "lossless_config" : "lossless_config_sig", "arrayBuffer");
    if (!bytes) return new Response("Not found", { status: 404 });
    return new Response(bytes, {
        headers: {
            "Content-Type": isJson ? "application/json" : "text/plain",
            "Cache-Control": "public, max-age=300",
        },
    });
}
