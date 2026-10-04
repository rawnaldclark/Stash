/**
 * /admin: the owner's and Evo's page for access requests, the access list and the goal. Cloudflare Access
 * guards it, and every request is checked again here (access-jwt.js). Every change is a form POST to /admin
 * with an `action`, answered with a redirect back to /admin?done=<what happened> (so a reload never repeats it).
 */
import { verifyAccess } from "./access-jwt.js";
import { clock, hashEmail, normalizeEmail, validEmail } from "./crypto.js";
import { sendEmail, welcomeEmail } from "./email.js";
import { addManualEntry, exactDollars, goalTarget, goalView, monthKey, parseDollars, readGoal, removeManualEntry } from "./goal.js";
import { readForm, sameOrigin } from "./http.js";
import { HASHCHECK_EMAIL, KEYS } from "./keys.js";
import { esc, gatePage } from "./pages.js";
import { rememberAccess } from "./session.js";

const MAX_REQUESTS_SHOWN = 100;
const MAX_SOURCE = 60;

/** What each ?done= says. Anything not listed shows nothing. */
const DONE = {
    approved: "Approved. They've been emailed.",
    "approved-noemail": "Approved, but the email didn't send",
    denied: "Request removed. No email was sent.",
    gone: "That request was already handled.",
    added: "Added to the list.",
    "added-emailed": "Added to the list, and emailed.",
    "added-noemail": "Added to the list, but the email didn't send",
    removed: "Access removed. Their sign-in stops working within a few minutes.",
    "not-listed": "That email wasn't on the list.",
    donation: "Donation added to this month.",
    "entry-removed": "Entry removed.",
    "bad-email": "That doesn't look like an email address.",
    "bad-amount": "Enter the amount in US dollars, like 5 or 12.50.",
    "bad-source": "Say where the donation came from, like \"GitHub Sponsors\".",
    "bad-form": "That form didn't come through. Try again.",
};

const fmtDate = (iso) => {
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? "" : d.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });
};

function requestItem(r) {
    const note = r.note ? `<p class="admin-note">${esc(r.note)}</p>` : "";
    const button = (action, label, kind) =>
        `<form method="post" action="/admin"><input type="hidden" name="action" value="${action}"><input type="hidden" name="h" value="${esc(r.h)}"><button type="submit" class="button button-small ${kind}">${label}</button></form>`;
    return `<li class="admin-item"><div class="admin-item-main"><p class="admin-email">${esc(r.email)}</p>${note}<p class="admin-meta">Asked ${esc(fmtDate(r.at))}</p></div><div class="admin-actions">${button("approve", "Approve", "button-primary")}${button("deny", "Deny", "button-secondary")}</div></li>`;
}

function entryRow(e) {
    const orig = e.orig ? ` <span class="admin-meta">(${esc(e.orig)})</span>` : "";
    const remove = e.manual
        ? `<form method="post" action="/admin"><input type="hidden" name="action" value="remove-entry"><input type="hidden" name="id" value="${esc(e.id)}"><button type="submit" class="admin-link">Remove</button></form>`
        : "";
    return `<tr><td>${esc(fmtDate(e.at))}</td><td>${esc(e.source)}${orig}</td><td class="admin-amount">${esc(exactDollars(e.cents))}</td><td>${remove}</td></tr>`;
}

async function pendingRequests(kv) {
    const list = await kv.list({ prefix: KEYS.requestPrefix, limit: MAX_REQUESTS_SHOWN });
    const rows = await Promise.all(
        list.keys.map(async ({ name }) => {
            const rec = await kv.get(name, "json");
            return rec ? { h: name.slice(KEYS.requestPrefix.length), ...rec } : null;
        }),
    );
    return rows.filter(Boolean).sort((a, b) => String(b.at).localeCompare(String(a.at)));
}

async function adminPage(env, url, admin) {
    const kv = env.STASH_KV;
    const month = monthKey(new Date(clock.now()));
    const [requests, goal, tipjarCheck, ourCheck] = await Promise.all([
        pendingRequests(kv),
        readGoal(kv, month),
        kv.get(KEYS.hashCheck),
        hashEmail(HASHCHECK_EMAIL, env.EMAIL_PEPPER),
    ]);
    const target = goalTarget(env);
    const done = url.searchParams.get("done");
    const code = url.searchParams.get("code");
    let flash = Object.prototype.hasOwnProperty.call(DONE, done) ? DONE[done] : "";
    if (flash && /-noemail$/.test(done)) flash += /^E_[A-Z_]{1,40}$/.test(code ?? "") ? ` (${code}).` : ".";
    const monthName = new Date(`${month}-01T00:00:00Z`).toLocaleDateString("en-GB", { month: "long", year: "numeric", timeZone: "UTC" });
    return gatePage(env, url, "admin", {
        text: {
            "admin-email": admin.email,
            flash,
            month: monthName,
            "goal-exact": `${exactDollars(goal.cents)} of ${exactDollars(target)}`,
            "request-count": requests.length === 1 ? "1 waiting" : `${requests.length} waiting`,
        },
        html: {
            requests: requests.map(requestItem).join(""),
            entries: [...goal.entries].reverse().map(entryRow).join(""),
        },
        flags: {
            flash: Boolean(flash),
            "hash-mismatch": tipjarCheck !== null && tipjarCheck !== ourCheck,
            "no-requests": requests.length === 0,
            "has-requests": requests.length > 0,
            "no-entries": goal.entries.length === 0,
            "has-entries": goal.entries.length > 0,
        },
        goal: goalView(goal, target),
    });
}

const back = (url, done, code) => {
    const to = new URL("/admin", url.origin);
    to.searchParams.set("done", done);
    if (code) to.searchParams.set("code", code);
    return new Response(null, { status: 303, headers: { Location: to.pathname + to.search, "Cache-Control": "no-store" } });
};

async function welcome(env, url, email) {
    const mail = welcomeEmail(url.origin);
    return sendEmail(env, { to: email, ...mail });
}

async function act(request, env, url, admin) {
    const form = await readForm(request);
    if (!form) return back(url, "bad-form");
    const kv = env.STASH_KV;
    const at = new Date(clock.now()).toISOString();
    const month = monthKey(new Date(clock.now()));
    const hashOf = (email) => hashEmail(email, env.EMAIL_PEPPER);

    switch (form.action) {
        case "approve": {
            const h = String(form.h || "");
            const rec = /^[0-9a-f]{64}$/.test(h) ? await kv.get(KEYS.request(h), "json") : null;
            if (!rec) return back(url, "gone");
            await kv.put(KEYS.access(h), JSON.stringify({ source: "approved", by: admin.email, at }));
            await kv.delete(KEYS.request(h));
            rememberAccess(h, true);
            const sent = await welcome(env, url, rec.email);
            return sent.ok ? back(url, "approved") : back(url, "approved-noemail", sent.code);
        }
        case "deny": {
            const h = String(form.h || "");
            if (!/^[0-9a-f]{64}$/.test(h) || (await kv.get(KEYS.request(h))) === null) return back(url, "gone");
            await kv.delete(KEYS.request(h));
            return back(url, "denied");
        }
        case "add": {
            const email = normalizeEmail(form.email);
            if (!validEmail(email)) return back(url, "bad-email");
            const h = await hashOf(email);
            await kv.put(KEYS.access(h), JSON.stringify({ source: "manual", by: admin.email, at }));
            await kv.delete(KEYS.request(h));
            rememberAccess(h, true);
            if (form.notify !== "yes") return back(url, "added");
            const sent = await welcome(env, url, email);
            return sent.ok ? back(url, "added-emailed") : back(url, "added-noemail", sent.code);
        }
        case "remove": {
            const email = normalizeEmail(form.email);
            if (!validEmail(email)) return back(url, "bad-email");
            const h = await hashOf(email);
            const listed = (await kv.get(KEYS.access(h))) !== null;
            await kv.delete(KEYS.access(h));
            await kv.delete(KEYS.code(h));
            rememberAccess(h, false);
            return back(url, listed ? "removed" : "not-listed");
        }
        case "donation": {
            const cents = parseDollars(form.amount);
            if (cents === null) return back(url, "bad-amount");
            const source = String(form.source || "").trim().replace(/\s+/g, " ").slice(0, MAX_SOURCE);
            if (!source) return back(url, "bad-source");
            await addManualEntry(kv, month, { cents, source, by: admin.email });
            return back(url, "donation");
        }
        case "remove-entry": {
            const removed = await removeManualEntry(kv, month, String(form.id || ""));
            return back(url, removed ? "entry-removed" : "gone");
        }
        default:
            return back(url, "bad-form");
    }
}

export async function adminRoute(request, env, url, method, fetchImpl) {
    const admin = await verifyAccess(request, env, fetchImpl);
    if (!admin) {
        return gatePage(env, url, "problem", {
            status: 403,
            text: { title: "Not for you, sorry", message: "This page is only for the people who run stashfm.app." },
        });
    }
    if (method === "GET") return adminPage(env, url, admin);
    if (method !== "POST") return new Response(null, { status: 405, headers: { Allow: "GET, POST" } });
    if (!sameOrigin(request, url)) return back(url, "bad-form");
    return act(request, env, url, admin);
}
