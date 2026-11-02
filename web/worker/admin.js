/**
 * /admin: the owner's and Evo's page for access requests, the access list, the open window (open.js) and the goal. Cloudflare Access
 * guards it, and every request is checked again here (access-jwt.js). Every change is a form POST to /admin
 * with an `action`, answered with a redirect back to /admin?done=<what happened> (so a reload never repeats it).
 */
import { verifyAccess } from "./access-jwt.js";
import { clearCodes } from "./codes.js";
import { clock, hashEmail, normalizeEmail, validEmail } from "./crypto.js";
import { sendEmail, welcomeEmail } from "./email.js";
import { addManualEntry, exactDollars, goalTarget, goalView, monthKey, parseDollars, readGoal, removeManualEntry } from "./goal.js";
import { readForm, sameOrigin } from "./http.js";
import { HASHCHECK_EMAIL, KEYS } from "./keys.js";
import { isOpen, OPEN_SOURCE, openSignups, openUntilText } from "./open.js";
import { esc, gatePage } from "./pages.js";
import { rememberAccess } from "./session.js";

const REQUESTS_PER_PAGE = 25;
/** Pending requests are listed in pages of 1,000 keys; past this many, the count says "N+". */
const MAX_REQUEST_LIST_PAGES = 10;
const MAX_SOURCE = 60;
/** Open-window sign-ups handled per click (each is up to 3 KV calls; a Worker request may make 1,000). */
const OPEN_BATCH = 250;
/** How many of them run at once. */
const OPEN_CHUNK = 50;
/**
 * After "Keep them all" or "Remove them all", the other one is refused for this long. KV's key listings can lag
 * about a minute behind its writes, so an older listing could otherwise bring back people just removed, or remove
 * people just kept.
 */
const OPEN_ACTION_GAP_MS = 2 * 60 * 1000;
/**
 * Which bulk buttons undo each other, so one is refused for OPEN_ACTION_GAP_MS after the other. "Remove everyone
 * kept" and "Keep them all" clash (a lagging list could remove people just kept, or keep people just removed);
 * the two removals don't.
 */
const OPEN_CONFLICTS = {
    "keep-open": ["remove-open", "remove-kept-open"],
    "remove-open": ["keep-open"],
    "remove-kept-open": ["keep-open"],
};
/**
 * People kept from the open window removed per click. Each is up to ~4 KV calls (a read, the delete, and listing
 * and deleting their sign-in codes), plus the listing's own reads, so this stays well under 1,000 per request.
 */
const KEPT_BATCH = 150;
/**
 * Access entries without metadata (Ko-fi's, and keeps from before metadata was written) need their value read to
 * tell whether they came from the open window. At most this many are read per request; past it, counts say "N+".
 */
const MAX_KEPT_READS = 300;

/** What each ?done= says. Anything not listed shows nothing. */
const DONE = {
    approved: "Approved. They've been emailed.",
    "approved-noemail": "Approved, but the email didn't send",
    denied: "Request removed. No email was sent.",
    gone: "That request was already handled.",
    added: "Added to the list.",
    "added-emailed": "Added to the list, and emailed.",
    "added-noemail": "Added to the list, but the email didn't send",
    removed: "Access removed, with any Ko-fi amounts kept toward the $5 entry. Their sign-in stops working within a few minutes.",
    "not-listed": "That email wasn't on the list.",
    donation: "Donation added to this month.",
    "entry-removed": "Entry removed.",
    "bad-email": "That doesn't look like an email address.",
    "bad-amount": "Enter the amount in US dollars, like 5 or 12.50.",
    "bad-source": "Say where the donation came from, like \"GitHub Sponsors\".",
    "bad-form": "That form didn't come through. Try again.",
    "open-kept": "Kept: everyone who signed up in the open window is on the list for good. The count takes a minute to catch up.",
    "open-removed": "Removed everyone who signed up in the open window (Ko-fi supporters on the list among them keep their access). Their sign-in stops working within a few minutes; the count takes a minute to catch up.",
    "open-more": "Done with a batch. Some are left: wait a minute (the list takes that long to catch up), then press it again.",
    "kept-open-removed": "Removed everyone you kept from the open window, including anyone who has given less than $5 on Ko-fi. Ko-fi supporters on the list ($5 or more) and people you added yourself keep their access. Their sign-in stops working within a few minutes; the count takes a minute to catch up.",
    "open-wait": "The other button ran less than two minutes ago. Wait a couple of minutes, so the list has caught up, then try again.",
    "no-pepper": "EMAIL_PEPPER isn't set on this Worker, so emails can't be added or removed (they'd be hashed differently from the tip jar's).",
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
    const remove = e.kind === "manual"
        ? `<form method="post" action="/admin"><input type="hidden" name="action" value="remove-entry"><input type="hidden" name="id" value="${esc(e.id)}"><button type="submit" class="admin-link">Remove</button></form>`
        : "";
    return `<tr><td>${esc(fmtDate(e.at))}</td><td>${esc(e.source)}${orig}</td><td class="admin-amount">${esc(exactDollars(e.cents))}</td><td>${remove}</td></tr>`;
}

/**
 * One page of pending requests, newest first, and how many there are. Every request key is listed (its time
 * is in the key's metadata, so sorting needs no reads), then only the page shown is read.
 */
async function pendingRequests(kv, page) {
    const all = [];
    let cursor;
    let more = false;
    for (let i = 0; i < MAX_REQUEST_LIST_PAGES; i++) {
        const res = await kv.list({ prefix: KEYS.requestPrefix, cursor });
        for (const { name, metadata } of res.keys) all.push({ name, at: String(metadata?.at ?? "") });
        if (res.list_complete || !res.cursor) break;
        cursor = res.cursor;
        more = i === MAX_REQUEST_LIST_PAGES - 1;
    }
    all.sort((a, b) => b.at.localeCompare(a.at));
    const pages = Math.max(1, Math.ceil(all.length / REQUESTS_PER_PAGE));
    const current = Math.min(Math.max(1, page), pages);
    const shown = all.slice((current - 1) * REQUESTS_PER_PAGE, current * REQUESTS_PER_PAGE);
    const rows = await Promise.all(
        shown.map(async ({ name }) => {
            const rec = await kv.get(name, "json");
            return rec ? { h: name.slice(KEYS.requestPrefix.length), ...rec } : null;
        }),
    );
    return { total: all.length, more, page: current, pages, rows: rows.filter(Boolean) };
}

function pager({ page, pages }) {
    if (pages <= 1) return "";
    const link = (n, label) => `<a class="admin-page-link" href="/admin?page=${n}">${label}</a>`;
    const newer = page > 1 ? link(page - 1, "Newer") : "";
    const older = page < pages ? link(page + 1, "Older") : "";
    return `${newer}<span class="admin-meta">Page ${page} of ${pages}</span>${older}`;
}

async function adminPage(env, url, admin) {
    const kv = env.ACCESS_KV;
    const month = monthKey(new Date(clock.now()));
    const page = Number.parseInt(url.searchParams.get("page") ?? "1", 10) || 1;
    const [requests, goal, tipjarCheck, ourCheck, openKeys, kept] = await Promise.all([
        pendingRequests(kv, page),
        readGoal(kv, month),
        kv.get(KEYS.hashCheck),
        hashEmail(HASHCHECK_EMAIL, env.EMAIL_PEPPER),
        openSignups(kv),
        keptFromOpen(kv),
    ]);
    const until = openUntilText(env);
    const openStatus = isOpen(env) ? `Open to everyone until ${until}.` : until ? `Closed (it ran until ${until}).` : "Not set (OPEN_UNTIL in wrangler.jsonc).";
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
            "request-count": `${requests.total}${requests.more ? "+" : ""} waiting`,
            "open-status": openStatus,
            "open-count": `${openKeys.length} signed up in the open window`,
            "kept-open-count": `${kept.hs.length}${kept.more ? "+" : ""} kept from the open window`,
        },
        html: {
            requests: requests.rows.map(requestItem).join(""),
            pager: pager(requests),
            entries: [...goal.entries].reverse().map(entryRow).join(""),
        },
        flags: {
            flash: Boolean(flash),
            "hash-mismatch": tipjarCheck !== null && tipjarCheck !== ourCheck,
            "no-requests": requests.total === 0,
            "has-requests": requests.total > 0,
            "no-entries": goal.entries.length === 0,
            "has-entries": goal.entries.length > 0,
            "has-open-signups": openKeys.length > 0,
            "has-kept-open": kept.hs.length > 0,
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

/**
 * Keeps one open-window sign-up: it joins the access list as approved (via "open"), unless it's already on it (a
 * supporter keeps their own entry). Each key is read again first: a listing can be a minute out of date.
 */
async function keepSignup(kv, { h, legacy }, by, at) {
    const kept = JSON.stringify({ source: "approved", by, at, via: "open" });
    // The metadata lets keptFromOpen find them from key listings alone, without reading every value.
    const opts = { metadata: { source: "approved", via: "open", at } };
    if (legacy) {
        const { metadata } = await kv.getWithMetadata(KEYS.access(h));
        if (metadata?.source === OPEN_SOURCE) await kv.put(KEYS.access(h), kept, opts);
        return;
    }
    if ((await kv.get(KEYS.open(h))) === null) return; // already kept or removed
    if ((await kv.get(KEYS.access(h))) === null) await kv.put(KEYS.access(h), kept, opts);
    await kv.delete(KEYS.open(h));
}

/**
 * Removes one open-window sign-up. Only the sign-up goes: a supporter who also signed up keeps their access:<h>.
 * The cached answer is forgotten, not set to "no", for the same reason.
 */
async function removeSignup(kv, { h, legacy }) {
    if (legacy) {
        const { metadata } = await kv.getWithMetadata(KEYS.access(h));
        if (metadata?.source === OPEN_SOURCE) await kv.delete(KEYS.access(h));
    } else {
        await kv.delete(KEYS.open(h));
    }
    rememberAccess(h, undefined);
}

/** True for an access entry's value written by keepSignup: approved, via the open window. */
const isKeptFromOpen = (rec) => rec?.source === "approved" && rec?.via === "open";

/**
 * Everyone kept from the open window, as hashes: access:<h> entries tagged via "open" in their metadata, plus
 * entries without metadata whose value says so (keeps from before the metadata was written). Entries with other
 * metadata (a pending older-format sign-up, tagged source "open") are never read or counted. Returns at most
 * `limit` hashes; `more` is true when there were more, or it ran out of reads (MAX_KEPT_READS).
 */
async function keptFromOpen(kv, limit = Infinity) {
    const hs = [];
    let reads = 0;
    let cursor;
    for (;;) {
        const res = await kv.list({ prefix: KEYS.accessPrefix, cursor });
        const unknown = [];
        for (const { name, metadata } of res.keys) {
            const h = name.slice(KEYS.accessPrefix.length);
            if (!metadata) unknown.push({ name, h });
            else if (metadata.source === "approved" && metadata.via === "open") hs.push(h);
        }
        for (let i = 0; i < unknown.length && hs.length <= limit; i += OPEN_CHUNK) {
            const chunk = unknown.slice(i, i + OPEN_CHUNK);
            if (reads + chunk.length > MAX_KEPT_READS) return { hs: hs.slice(0, limit), more: true };
            reads += chunk.length;
            const recs = await Promise.all(chunk.map(({ name }) => kv.get(name, "json").catch(() => null)));
            chunk.forEach(({ h }, j) => isKeptFromOpen(recs[j]) && hs.push(h));
        }
        if (hs.length > limit) return { hs: hs.slice(0, limit), more: true };
        if (res.list_complete || !res.cursor) return { hs, more: false };
        cursor = res.cursor;
    }
}

/**
 * Removes one person kept from the open window, after reading their entry again (a listing can be a minute out of
 * date): if it has since become a supporter's or a hand-added entry, it stays. Sign-in stops like Remove access.
 */
async function removeKept(env, h) {
    const kv = env.ACCESS_KV;
    if (!isKeptFromOpen(await kv.get(KEYS.access(h), "json").catch(() => null))) return;
    await kv.delete(KEYS.access(h));
    await clearCodes(env, h);
    rememberAccess(h, false);
}

/**
 * Remove access also deletes the Ko-fi amounts the tip jar keeps for [h] while it's under the $5 entry
 * (KEYS.kofiPayPrefix), so a removed email starts again from $0 and the privacy page's "taken off the list" holds for
 * them too. A payment written in the last minute may not be listed yet (KV's list() lag) and stays.
 */
async function clearKofiPayments(kv, h) {
    let cursor;
    do {
        const page = await kv.list({ prefix: KEYS.kofiPayPrefix(h), cursor });
        await Promise.all(page.keys.map((k) => kv.delete(k.name)));
        cursor = page.list_complete ? undefined : page.cursor;
    } while (cursor);
}

/** Refuses a bulk button that would undo one pressed less than OPEN_ACTION_GAP_MS ago; otherwise records it. */
async function bulkGuard(kv, action, at) {
    const last = await kv.get(KEYS.openAction, "json");
    if (last && OPEN_CONFLICTS[action]?.includes(last.action) && clock.now() - Date.parse(last.at) < OPEN_ACTION_GAP_MS) return false;
    await kv.put(KEYS.openAction, JSON.stringify({ action, at }), { expirationTtl: 600 });
    return true;
}

async function act(request, env, url, admin) {
    const form = await readForm(request);
    if (!form) return back(url, "bad-form");
    const kv = env.ACCESS_KV;
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
            if (!env.EMAIL_PEPPER) return back(url, "no-pepper");
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
            if (!env.EMAIL_PEPPER) return back(url, "no-pepper");
            const email = normalizeEmail(form.email);
            if (!validEmail(email)) return back(url, "bad-email");
            const h = await hashOf(email);
            const [onList, signedUp] = await Promise.all([kv.get(KEYS.access(h)), kv.get(KEYS.open(h))]);
            const listed = onList !== null || signedUp !== null;
            await Promise.all([kv.delete(KEYS.access(h)), kv.delete(KEYS.open(h))]);
            await clearCodes(env, h);
            await clearKofiPayments(kv, h);
            rememberAccess(h, false);
            return back(url, listed ? "removed" : "not-listed");
        }
        case "keep-open":
        case "remove-open": {
            const keep = form.action === "keep-open";
            if (!(await bulkGuard(kv, form.action, at))) return back(url, "open-wait");
            const all = await openSignups(kv);
            const batch = all.slice(0, OPEN_BATCH);
            for (let i = 0; i < batch.length; i += OPEN_CHUNK) {
                await Promise.all(batch.slice(i, i + OPEN_CHUNK).map((s) => (keep ? keepSignup(kv, s, admin.email, at) : removeSignup(kv, s))));
            }
            if (all.length > OPEN_BATCH) return back(url, "open-more");
            return back(url, keep ? "open-kept" : "open-removed");
        }
        case "remove-kept-open": {
            if (!(await bulkGuard(kv, form.action, at))) return back(url, "open-wait");
            const { hs, more } = await keptFromOpen(kv, KEPT_BATCH);
            for (let i = 0; i < hs.length; i += OPEN_CHUNK) await Promise.all(hs.slice(i, i + OPEN_CHUNK).map((h) => removeKept(env, h)));
            return back(url, more ? "open-more" : "kept-open-removed");
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
    // No ACCESS_KV (a Preview) means nothing to manage: refused like anyone without Access.
    const admin = env.ACCESS_KV ? await verifyAccess(request, env, fetchImpl) : null;
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
