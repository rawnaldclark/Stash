/**
 * Stash Tip Jar — Cloudflare Worker
 *
 * Single endpoint, two methods:
 *   POST  → Ko-fi webhook receiver. Validates `verification_token`,
 *           appends the new supporter to KV, returns 200.
 *   GET   → Serves the current supporter list as JSON to the Stash
 *           Android app. Cached for 60s at the edge.
 *
 * Storage: Cloudflare KV. One key, `supporters`, holding the JSON the
 * app reads. Bounded to the most recent SUPPORTERS_LIMIT entries to
 * avoid unbounded growth.
 *
 * Why one Worker for both: Ko-fi webhook posts, app GETs. Routing by
 * HTTP method keeps it to one URL the user has to configure in two
 * places (Ko-fi dashboard + app's BuildConfig.SUPPORTERS_JSON_URL).
 *
 * Deploy: see ../README.md.
 */

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

    if (payload.verification_token !== env.KOFI_VERIFICATION_TOKEN) {
        return new Response("Invalid token", { status: 401 });
    }

    // Only count one-time donations and subscription payments. Skip
    // shop orders, commissions, etc. — those aren't tips.
    const allowedTypes = ["Donation", "Subscription"];
    if (!allowedTypes.includes(payload.type)) {
        return new Response("Ignored", { status: 200 });
    }

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

    return new Response("OK", { status: 200 });
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
