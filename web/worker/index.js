/**
 * stashfm.app: the website's Worker. The site itself is static (Astro, ./dist, Workers Static Assets);
 * this adds the early-access gate in front of it, the sign-in flow and the admin page.
 *
 *   GET  /               signed in: the site's home page. Otherwise: the early-access page, with the goal.
 *   GET  /access         the early-access page (signed in: back to /)
 *   POST /access         email in -> a sign-in code by email, if that email has access. The same answer either way.
 *   POST /access/verify  email + code -> the stash_access cookie, then back to /
 *   GET  /request        the request-access form;  POST /request  stores the request (same answer either way)
 *   POST /signout        clears the cookie
 *   GET|POST /admin      requests, the access list and the goal (Cloudflare Access + access-jwt.js)
 *
 * Anything else goes to the static assets: files, /privacy and the 404 page are public, and any other HTML
 * page needs a session (so a page added later is private until it's listed in PUBLIC_PAGES). Which paths
 * reach this Worker at all is `assets.run_worker_first` in wrangler.jsonc; the rest (/_astro/*, images,
 * fonts, robots.txt, the sitemap, /privacy) Cloudflare serves directly.
 *
 * Nothing on these pages reveals who has access: sending a code, a wrong code and requesting access answer
 * the same whether or not the email is on the list, and the slow parts (writing a code, sending an email,
 * storing a request) run after the response.
 */
import { adminRoute } from "./admin.js";
import { checkCode, cleanCode, issueCode } from "./codes.js";
import { clock, hashEmail, normalizeEmail, validEmail } from "./crypto.js";
import { codeEmail, sendEmail } from "./email.js";
import { currentGoal, goalTarget, goalView } from "./goal.js";
import { clientKey, readForm, sameOrigin } from "./http.js";
import { KEYS } from "./keys.js";
import { fetchAsset, fill, gatePage, personal, withSecurityHeaders } from "./pages.js";
import { clearedCookie, currentSession, hasAccess, rememberAccess, sessionCookie, sessionSecret, signSession } from "./session.js";

/** HTML pages anyone can see. Files (CSS, images, fonts, XML) are always public. */
const PUBLIC_PAGES = new Set(["/privacy"]);
const REQUEST_TTL_S = 30 * 24 * 3600;
const MAX_NOTE = 500;

export default {
    async fetch(request, env, ctx) {
        try {
            return withSecurityHeaders(await handle(request, env, ctx));
        } catch (err) {
            console.error(err); // `wrangler tail`
            const url = new URL(request.url);
            try {
                return withSecurityHeaders(await problem(env, url, 503, "Something went wrong on our side. Try again in a minute."));
            } catch {
                return withSecurityHeaders(
                    new Response("Something went wrong. Try again in a minute.", { status: 503, headers: { "content-type": "text/plain; charset=utf-8", "Retry-After": "60" } }),
                );
            }
        }
    },
};

export async function handle(request, env, ctx, { fetchImpl } = {}) {
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method === "HEAD" ? "GET" : request.method;
    // Work that mustn't delay (or show in the timing of) the answer. Tests have no ctx, so there it finishes first.
    const later = (p) => (ctx ? ctx.waitUntil(p) : p);

    switch (path) {
        case "/":
            if (method !== "GET") return notAllowed("GET");
            return (await currentSession(request, env)) ? home(env, url) : front(env, url);
        case "/access":
            if (method === "GET") return (await currentSession(request, env)) ? seeOther("/") : front(env, url);
            if (method === "POST") return sendCode(request, env, url, later);
            return notAllowed("GET, POST");
        case "/access/verify":
            if (method === "GET") return seeOther("/");
            if (method === "POST") return verify(request, env, url);
            return notAllowed("GET, POST");
        case "/request":
            if (method === "GET") return gatePage(env, url, "request");
            if (method === "POST") return requestAccess(request, env, url, later);
            return notAllowed("GET, POST");
        case "/signout":
            if (method === "GET") return seeOther("/");
            if (method === "POST") return signOut(request, env, url);
            return notAllowed("GET, POST");
        case "/admin":
            return adminRoute(request, env, url, method, fetchImpl);
    }
    // The gate's own page templates are only ever served through the routes above (however the path is spelled).
    if (/^\/gate(?:[/.]|$)/.test(loosePath(path))) return env.ASSETS.fetch(new Request(new URL("/404-not-a-page", url.origin)));
    return staticSite(request, env, url);
}

/** Files, public pages, redirects and 404s pass through; any other page needs a session. */
async function staticSite(request, env, url) {
    const res = await env.ASSETS.fetch(request);
    const html = (res.headers.get("content-type") || "").includes("text/html");
    if (res.status !== 200 || !html || PUBLIC_PAGES.has(url.pathname)) return res;
    if (await currentSession(request, env)) return personal(res);
    return seeOther("/");
}

/** A path as an asset lookup might read it: percent-decoded, repeated slashes merged, lowercased. */
function loosePath(path) {
    let p = path;
    try {
        p = decodeURIComponent(path);
    } catch {
        // not valid percent-encoding: check it as it is
    }
    return p.replace(/[\\/]+/g, "/").toLowerCase();
}

const seeOther = (location, headers = {}) => new Response(null, { status: 303, headers: { Location: location, "Cache-Control": "no-store", ...headers } });
const notAllowed = (allow) => new Response(null, { status: 405, headers: { Allow: allow } });

/** The goal bar's numbers, or null (and no bar) if KV can't be read right now. */
async function goalFor(env) {
    try {
        return goalView(await currentGoal(env), goalTarget(env));
    } catch (err) {
        console.error("goal unavailable:", err && err.message);
        return null;
    }
}

async function front(env, url) {
    return gatePage(env, url, "front", { goal: await goalFor(env) });
}

/** The real home page, for a signed-in visitor. Its support section shows the goal too. */
async function home(env, url) {
    const res = await fetchAsset(env, url, "/");
    return personal(fill(res, { goal: await goalFor(env) }));
}

function problem(env, url, status, message, title = "Something went wrong") {
    return gatePage(env, url, "problem", { status, text: { title, message } });
}

const slowDown = (env, url) => gatePage(env, url, "slow-down", { status: 429, headers: { "Retry-After": "60" } });

/** Checks shared by every visitor form: same origin, a small form body. Returns the form, or a response. */
async function formOf(request, env, url) {
    if (!sameOrigin(request, url)) return { response: await problem(env, url, 403, "That form came from somewhere else, so it was ignored. Go back to stashfm.app and try again.") };
    const form = await readForm(request);
    if (!form) return { response: await problem(env, url, 400, "That form didn't come through. Go back and try again.") };
    return { form };
}

/** POST /access: sends a code if the email has access; the page says the same thing either way. */
async function sendCode(request, env, url, later) {
    const { form, response } = await formOf(request, env, url);
    if (response) return response;
    const email = normalizeEmail(form.email);
    if (!validEmail(email)) return problem(env, url, 400, "That doesn't look like an email address. Go back and check it.", "Check that email");
    if (!(await env.SEND_IP_RL.limit({ key: clientKey(request) })).success) return slowDown(env, url);
    const h = await hashEmail(email, env.EMAIL_PEPPER);
    if (!(await env.SEND_EMAIL_RL.limit({ key: h })).success) return slowDown(env, url);
    const secret = sessionSecret(env);
    if (!secret) {
        console.error("SESSION_SECRET is missing or shorter than 32 characters: sign-in is off");
        return problem(env, url, 503, "Signing in isn't working right now. Try again later.");
    }
    if (await env.STASH_KV.get(KEYS.access(h))) {
        later(
            (async () => {
                const code = await issueCode(env, secret, h);
                if (code) await sendEmail(env, { to: email, ...codeEmail(code) });
            })().catch((err) => console.error("code not sent:", err && err.message)),
        );
    }
    return gatePage(env, url, "sent", { text: { email } });
}

/** POST /access/verify: the right code signs you in; anything else gets one answer for every kind of failure. */
async function verify(request, env, url) {
    const { form, response } = await formOf(request, env, url);
    if (response) return response;
    if (!(await env.VERIFY_IP_RL.limit({ key: clientKey(request) })).success) return slowDown(env, url);
    const email = normalizeEmail(form.email);
    const code = cleanCode(form.code);
    const secret = sessionSecret(env);
    const failed = () => gatePage(env, url, "code-failed", { status: 400, text: { email } });
    if (!secret || !validEmail(email) || !code) return failed();
    const h = await hashEmail(email, env.EMAIL_PEPPER);
    if (!(await checkCode(env, secret, h, code))) return failed();
    rememberAccess(h, undefined);
    if (!(await hasAccess(env, h))) return failed(); // removed while the code was on its way
    return seeOther("/", { "Set-Cookie": sessionCookie(await signSession(secret, h)) });
}

/** POST /request: keeps {email, note} until someone handles it (or 30 days). The same answer whoever asks. */
async function requestAccess(request, env, url, later) {
    const { form, response } = await formOf(request, env, url);
    if (response) return response;
    const email = normalizeEmail(form.email);
    if (!validEmail(email)) return problem(env, url, 400, "That doesn't look like an email address. Go back and check it.", "Check that email");
    if (!(await env.REQUEST_IP_RL.limit({ key: clientKey(request) })).success) return slowDown(env, url);
    const note = String(form.note ?? "").replace(/\r\n?/g, "\n").trim().slice(0, MAX_NOTE);
    later(
        (async () => {
            const h = await hashEmail(email, env.EMAIL_PEPPER);
            if (await env.STASH_KV.get(KEYS.access(h))) return; // already in: nothing to ask for
            await env.STASH_KV.put(KEYS.request(h), JSON.stringify({ email, note, at: new Date(clock.now()).toISOString() }), { expirationTtl: REQUEST_TTL_S });
        })().catch((err) => console.error("request not stored:", err && err.message)),
    );
    return gatePage(env, url, "requested");
}

async function signOut(request, env, url) {
    if (!sameOrigin(request, url)) return problem(env, url, 403, "That form came from somewhere else, so it was ignored.");
    return gatePage(env, url, "signed-out", { headers: { "Set-Cookie": clearedCookie } });
}
