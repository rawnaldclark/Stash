import { beforeEach, test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import worker, { handle } from "../index.js";
import { hashFor as hashEmail } from "./fakes.js";
import { forgetGoalCache } from "../goal.js";
import { CSP, SECURITY_HEADERS } from "../pages.js";
import { forgetAllAccess, signSession } from "../session.js";
import { assertSignedOutHome, env, fakeKV, get, goalEntry, post, SECRET } from "./fakes.js";

const EMAIL = "supporter@example.com";
const month = () => new Date().toISOString().slice(0, 7);

beforeEach(() => {
    forgetAllAccess();
    forgetGoalCache();
});

async function signedIn(extra = {}) {
    const h = await hashEmail(EMAIL);
    const e = env({ ACCESS_KV: fakeKV({ [`access:${h}`]: { source: "kofi" }, ...extra }) });
    return { e, cookie: `stash_access=${await signSession(SECRET, h)}` };
}

test("/ for a visitor who isn't signed in: the home page with the sign-in form, and this month's goal filled in", async () => {
    const e = env({ ACCESS_KV: fakeKV({ ...goalEntry(month(), "kofi", "tx1", 2599), ...goalEntry(month(), "manual", "m1", 1200) }) });
    const res = await handle(get("/"), e);
    assert.equal(res.status, 200);
    const page = await res.text();
    assertSignedOutHome(page);
    assert.match(page, /<strong data-goal="raised">\$37<\/strong> of <span data-goal="target">\$100<\/span>/);
    assert.match(page, /aria-valuenow="37"/);
    assert.match(page, /aria-valuetext="\$37 of \$100 this month"/);
    assert.match(page, /style="width:37%"/);
    assert.equal(res.headers.get("cache-control"), "no-store");
    assert.equal(res.headers.get("vary"), "Cookie");
    assert.equal(res.headers.get("etag"), null);
});

test("the goal bar stops at 100% and the figure keeps counting", async () => {
    const e = env({ ACCESS_KV: fakeKV(goalEntry(month(), "kofi", "big", 12550)) });
    const page = await (await handle(get("/"), e)).text();
    assert.match(page, /\$125<\/strong>/);
    assert.match(page, /width:100%/);
});

test("if the goal can't be read, the page still works, without the bar", async () => {
    const kv = fakeKV();
    kv.get = kv.list = async () => {
        throw new Error("KV down");
    };
    const res = await handle(get("/"), env({ ACCESS_KV: kv }));
    assert.equal(res.status, 200);
    const page = await res.text();
    assertSignedOutHome(page);
    assert.doesNotMatch(page, /data-goal/);
});

test("/ signed in: the home page with a button to /player instead of the sign-in form, Sign out, and the goal", async () => {
    const { e, cookie } = await signedIn(goalEntry(month(), "manual", "m1", 5000));
    const res = await handle(get("/", { Cookie: cookie }), e);
    const page = await res.text();
    assert.match(page, /id="home"/);
    assert.match(page, /href="\/player"/);
    assert.match(page, /action="\/signout"/);
    assert.doesNotMatch(page, /action="\/access"/);
    assert.match(page, /\$50<\/strong>/);
    assert.equal(res.headers.get("cache-control"), "no-store");
    assert.equal(res.headers.get("vary"), "Cookie");
});

test("a forged or stale cookie gets the home page as a visitor who isn't signed in sees it", async () => {
    const e = env();
    for (const cookie of ["stash_access=abc.def", `stash_access=${await signSession(SECRET, await hashEmail(EMAIL))}`]) {
        assertSignedOutHome(await (await handle(get("/", { Cookie: cookie }), e)).text());
    }
});

test("GET /access is still the early-access sign-in page, and signed in it goes back to /", async () => {
    const page = await (await handle(get("/access"), env())).text();
    assert.match(page, /id="front"/);
    const { e, cookie } = await signedIn();
    const res = await handle(get("/access", { Cookie: cookie }), e);
    assert.equal(res.status, 303);
    assert.equal(res.headers.get("location"), "/");
});

test("the gate's templates are never served as they are", async () => {
    const { e, cookie } = await signedIn();
    for (const path of ["/gate/front", "/gate/admin", "/gate/sent", "/gate", "/gate/", "/gate/front.html", "//gate/admin", "/GATE/admin", "/gate%2Fadmin", "/%67ate/admin"]) {
        const res = await handle(get(path, { Cookie: cookie }), e);
        assert.equal(res.status, 404, path);
        assert.match(await res.text(), /NOT FOUND PAGE/);
    }
});

test("public pages, files, redirects and 404s pass straight through", async () => {
    const e = env();
    const privacy = await handle(get("/privacy"), e);
    assert.equal(privacy.status, 200);
    assert.match(await privacy.text(), /PRIVACY/);
    assert.equal((await handle(get("/sitemap-index.xml"), e)).status, 200);
    const redirect = await handle(get("/index.html"), e);
    assert.equal(redirect.status, 307);
    assert.equal(redirect.headers.get("location"), "/");
    const missing = await handle(get("/no-such-page"), e);
    assert.equal(missing.status, 404);
    assert.match(await missing.text(), /NOT FOUND PAGE/);
});

test("any other page is private by default: signed out it sends you to /, signed in it's served privately", async () => {
    const out = await handle(get("/404"), env());
    assert.equal(out.status, 303);
    assert.equal(out.headers.get("location"), "/");
    const { e, cookie } = await signedIn();
    const inside = await handle(get("/404", { Cookie: cookie }), e);
    assert.equal(inside.status, 200);
    assert.equal(inside.headers.get("cache-control"), "no-store");
});

test("signing out clears the cookie and shows the signed-out page; it needs a same-site POST", async () => {
    const { e, cookie } = await signedIn();
    const res = await handle(post("/signout", {}, { headers: { Cookie: cookie } }), e);
    assert.equal(res.status, 200);
    assert.match(res.headers.get("set-cookie"), /^stash_access=; .*Max-Age=0$/);
    assert.match(await res.text(), /id="signed-out"/);
    const forged = await handle(post("/signout", {}, { origin: "https://evil.example", headers: { Cookie: cookie } }), e);
    assert.equal(forged.status, 403);
    assert.equal(forged.headers.get("set-cookie"), null);
});

test("wrong methods are 405", async () => {
    const e = env();
    for (const [path, method] of [["/", "POST"], ["/access", "PUT"], ["/request", "DELETE"], ["/signout", "PATCH"]]) {
        const res = await handle(new Request(`https://stashfm.app${path}`, { method, headers: { Origin: "https://stashfm.app" } }), e);
        assert.equal(res.status, 405, `${method} ${path}`);
    }
});

test("every response from the Worker carries the site's security headers, errors included", async () => {
    const e = env();
    for (const req of [get("/"), get("/privacy"), get("/no-such-page"), post("/access", { email: "x@example.com" })]) {
        const res = await worker.fetch(req, e, { waitUntil() {} });
        for (const [k, v] of Object.entries(SECURITY_HEADERS)) assert.equal(res.headers.get(k), v, `${k} on ${req.url}`);
    }
    const broken = env();
    broken.ASSETS = {
        async fetch() {
            throw new Error("assets down");
        },
    };
    const res = await worker.fetch(get("/privacy"), broken, { waitUntil() {} });
    assert.equal(res.status, 503);
    assert.equal(res.headers.get("content-security-policy"), CSP);
});

test("on /admin only, form-action also allows the Access team domain (an expired Access session redirects a form post there)", async () => {
    const e = env();
    const admin = await worker.fetch(get("/admin"), e, { waitUntil() {} });
    assert.equal(admin.headers.get("content-security-policy"), CSP.replace("form-action 'self'", "form-action 'self' https://stash-test.cloudflareaccess.com"));
    const unset = await worker.fetch(get("/admin"), env({ ACCESS_TEAM_DOMAIN: "https://TODO-team-name.cloudflareaccess.com" }), { waitUntil() {} });
    assert.equal(unset.headers.get("content-security-policy"), CSP, "no team domain allowed while it's a placeholder");
    const front = await worker.fetch(get("/"), e, { waitUntil() {} });
    assert.equal(front.headers.get("content-security-policy"), CSP);
});

test("the Worker's CSP is public/_headers' CSP, with form-action 'self' and no scripts", () => {
    const headers = readFileSync(new URL("../../public/_headers", import.meta.url), "utf8");
    const line = headers.split(/\r?\n/).find((l) => l.trim().startsWith("Content-Security-Policy:"));
    assert.equal(line.trim().slice("Content-Security-Policy:".length).trim(), CSP);
    assert.match(CSP, /script-src 'none'/);
    assert.match(CSP, /form-action 'self'/);
});

test("HEAD / works like GET", async () => {
    const res = await handle(new Request("https://stashfm.app/", { method: "HEAD" }), env());
    assert.equal(res.status, 200);
});

test("pages are fetched from the assets without the visitor's conditional headers (a 304 can't be filled in)", async () => {
    const e = env();
    await handle(get("/", { "If-None-Match": '"abc"' }), e);
    const asked = e.ASSETS.requests.find((r) => r.path === "/");
    assert.equal(asked.headers["if-none-match"], undefined);
});
