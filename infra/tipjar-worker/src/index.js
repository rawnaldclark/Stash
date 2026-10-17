/**
 * Stash Tip Jar — Cloudflare Worker
 *
 * Single endpoint, two methods:
 *   POST  → Ko-fi webhook receiver. Validates `verification_token`,
 *           stores the new supporter in KV, returns 200.
 *   GET   → Serves the current supporter list as JSON to the Stash
 *           Android app. Cached for 60s at the edge.
 *   GET /lossless.json and /lossless.json.sig → the signed lossless relay
 *           config, byte-for-byte from KV (see infra/lossless-relay).
 *   POST /claim/email/start and /claim/email/verify → the supporter claim:
 *           a Ko-fi donor proves their email and gets a signed supporter
 *           token (src/claim.js, src/entitlement.js). Every other POST path
 *           is still the Ko-fi webhook, as before.
 * And a daily cron (wrangler.toml) that rebuilds the supporter list.
 *
 * Storage: Cloudflare KV. Each supporter has a key of their own,
 * supporter:<time>:<id>, and `supporters`, the JSON the app reads, is
 * rebuilt from those keys (the most recent SUPPORTERS_LIMIT of them), so
 * two donations at once can't lose either donor (src/supporters.js).
 *
 * Since 2026-10 a Donation or Subscription also gives the donor early access
 * to stashfm.app and counts toward the site's monthly goal (src/access.js,
 * in the separate ACCESS_KV namespace). The webhook URL and the GET JSON are
 * unchanged; the webhook answers 500 when that early-access step fails, so
 * Ko-fi retries it, and a retry never lists the same supporter twice.
 * A donor who wasn't on the early-access list yet also gets the welcome email,
 * sent by the stash-mailer Worker (service binding MAILER, src/welcome.js)
 * after the webhook has answered: it never slows or fails the webhook.
 *
 * Why one Worker for both: Ko-fi webhook posts, app GETs. Routing by
 * HTTP method keeps it to one URL the user has to configure in two
 * places (Ko-fi dashboard + app's BuildConfig.SUPPORTERS_JSON_URL).
 *
 * Deploy: see ../README.md.
 */

import { dedupeId, KEYS, KOFI_TXN_TTL_S, recordKofiSupport, sameSecret } from "./access.js";
import { handleClaimStart, handleClaimVerify } from "./claim.js";
import { addSupporter, LIST_KEY, rebuildSupporters, storedSupporter } from "./supporters.js";
import { sendWelcome } from "./welcome.js";

export default {
    async fetch(request, env, ctx) {
        if (request.method === "POST") {
            const path = new URL(request.url).pathname;
            if (path === "/claim/email/start") return handleClaimStart(request, env, ctx);
            if (path === "/claim/email/verify") return handleClaimVerify(request, env);
            return handleKofiWebhook(request, env, ctx);
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

    /**
     * The daily cron (wrangler.toml [triggers]): rebuilds the supporter list from the supporter:* keys, so a
     * donor that a webhook's rebuild missed (two at once, or KV's list() lagging) is listed within a day. Also
     * moves the list from before 2026-10 to keys of its own if no donation has done it yet. A failure shows as a
     * failed cron run in the dashboard and changes nothing.
     */
    async scheduled(controller, env) {
        const listed = await rebuildSupporters(env);
        console.log(`supporters list rebuilt: ${listed} listed`);
    },
};

/**
 * Ko-fi webhook handler. Ko-fi sends `application/x-www-form-urlencoded`
 * with a `data` field containing JSON. The JSON's `verification_token`
 * MUST match `KOFI_VERIFICATION_TOKEN` (set as a Worker secret) — see
 * https://ko-fi.com/manage/webhooks for where to find that token.
 */
async function handleKofiWebhook(request, env, ctx) {
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
    // kofitxn:<id> is "listed" once the supporter's own key is written and "done"
    // once the list, early access and the goal are written too, so a retry
    // repeats only what's left.
    const id = dedupeId(payload);
    const state = id ? await env.STASH_KV.get(KEYS.kofiTxn(id)) : null;
    if (state === "done") {
        return new Response("OK", { status: 200 });
    }
    // The supporter's own key first (src/supporters.js): from then on they can't be lost. If this write fails,
    // nothing is marked and the whole webhook fails, so Ko-fi retries it.
    const fresh = [];
    if (state !== "listed") {
        fresh.push(await addSupporter(env, payload, id));
        if (id) await env.STASH_KV.put(KEYS.kofiTxn(id), "listed", { expirationTtl: KOFI_TXN_TTL_S });
    } else {
        // A retry: an earlier try stored the supporter. Their key, worked out again (not written again), goes to the
        // rebuild too, so it doesn't depend on list() having caught up.
        const again = storedSupporter(payload, id);
        if (again) fresh.push(again);
    }
    // Then the list the app reads. If the rebuild fails, the supporter is still stored: early access goes ahead,
    // Ko-fi gets a 500 and retries, and the retry (already "listed") only rebuilds. The daily cron rebuilds too.
    let listed = true;
    try {
        await rebuildSupporters(env, fresh);
    } catch (err) {
        console.error("supporters list not rebuilt:", err && err.message);
        listed = false;
    }

    // Early access and the monthly goal (src/access.js). If this fails, Ko-fi
    // gets a 500 and retries; the supporter above is already stored and won't
    // be added again. A donor new to the list is welcomed by email once the
    // response is out (waitUntil); sendWelcome never throws.
    const onNewAccess = (email) => {
        const welcome = sendWelcome(env, email);
        if (ctx && typeof ctx.waitUntil === "function") ctx.waitUntil(welcome);
    };
    try {
        await recordKofiSupport(env, payload, new Date(), { onNewAccess });
    } catch (err) {
        console.error("early access / goal update failed:", err && err.message);
        return new Response("Try again", { status: 500 });
    }
    if (!listed) return new Response("Try again", { status: 500 });
    if (id) await env.STASH_KV.put(KEYS.kofiTxn(id), "done", { expirationTtl: KOFI_TXN_TTL_S });

    return new Response("OK", { status: 200 });
}

/**
 * GET handler — returns the current supporter list. Strips the
 * internal `timestamp` and `key` fields (the app doesn't need them). 60-second
 * edge cache so a thousand cold-start app launches don't all hit KV.
 */
async function serveSupporters(env) {
    const existingRaw = await env.STASH_KV.get(LIST_KEY);
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
