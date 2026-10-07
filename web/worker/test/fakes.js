/**
 * Stand-ins for the Workers runtime in plain Node (node --test): KV, the ratelimit and send_email
 * bindings, the static assets binding, ExecutionContext, and just enough of HTMLRewriter for the
 * attribute selectors worker/pages.js uses ([data-fill], [data-if], ...).
 */
import { clock, hashEmail } from "../crypto.js";

/**
 * Workers KV: get (text, "json", {type}), put with expirationTtl/expiration (honoured by clock.now()) and metadata,
 * delete, and list with prefix, limit, cursor and metadata (pages of at most `limit`, 1000 by default, like KV).
 * `holdPuts(match)` makes matching puts wait until `release()`, to prove a response doesn't wait on them.
 */
export function fakeKV(initial = {}) {
    const map = new Map(
        Object.entries(initial).map(([k, v]) => {
            if (v && typeof v === "object" && "metadata" in v) return [k, { value: v.value, opts: { metadata: v.metadata } }];
            return [k, { value: typeof v === "string" ? v : JSON.stringify(v), opts: {} }];
        }),
    );
    const live = (key) => {
        const e = map.get(key);
        if (!e) return null;
        const exp = e.opts.expiration ?? (e.opts.expirationTtl ? e.at / 1000 + e.opts.expirationTtl : null);
        if (exp !== null && exp * 1000 <= clock.now()) {
            map.delete(key);
            return null;
        }
        return e;
    };
    return {
        map,
        async get(key, type) {
            const e = live(key);
            if (!e) return null;
            const t = typeof type === "object" ? type?.type : type;
            return t === "json" ? JSON.parse(e.value) : e.value;
        },
        held: null,
        holdPuts(match) {
            let release;
            const gate = new Promise((r) => (release = r));
            this.held = { match, gate };
            return () => {
                this.held = null;
                release();
            };
        },
        async put(key, value, opts = {}) {
            if (typeof value !== "string") throw new Error("fake KV takes strings");
            if (opts.metadata && JSON.stringify(opts.metadata).length > 1024) throw new Error("KV: metadata over 1024 bytes");
            if (this.held && this.held.match(key)) await this.held.gate;
            if (opts.expirationTtl !== undefined && opts.expirationTtl < 60) throw new Error("KV: expirationTtl under 60");
            if (opts.expiration !== undefined && opts.expiration - clock.now() / 1000 < 60) throw new Error("KV: expiration under 60s away");
            map.set(key, { value, opts, at: clock.now() });
        },
        async delete(key) {
            if (this.held && this.held.match(key)) await this.held.gate;
            map.delete(key);
        },
        lists: 0,
        async list({ prefix = "", limit = 1000, cursor } = {}) {
            this.lists++;
            const names = [...map.keys()].filter((k) => k.startsWith(prefix) && live(k)).sort();
            const start = cursor ? Number(cursor) : 0;
            const page = names.slice(start, start + Math.min(limit, 1000));
            const done = start + page.length >= names.length;
            return { keys: page.map((name) => ({ name, metadata: map.get(name).opts.metadata })), list_complete: done, cursor: done ? undefined : String(start + page.length) };
        },
        /** Test helper: the parsed value, or null. */
        json(key) {
            const e = live(key);
            return e ? JSON.parse(e.value) : null;
        },
    };
}

/** A ratelimit binding that allows [limit] calls per key, then says no. */
export function fakeRL(limit = Infinity) {
    const counts = new Map();
    return {
        counts,
        async limit({ key }) {
            const n = (counts.get(key) ?? 0) + 1;
            counts.set(key, n);
            return { success: n <= limit };
        },
    };
}

/** The send_email binding: records messages, or throws an Error with .code like the real one. */
export function fakeEmail({ failWith } = {}) {
    return {
        sent: [],
        async send(message) {
            if (failWith) {
                const err = new Error(`send failed: ${failWith}`);
                err.code = failWith;
                throw err;
            }
            this.sent.push(message);
            return { messageId: `msg-${this.sent.length}` };
        },
    };
}

const html = (body) => `<!doctype html><html><head><title>t</title></head><body>${body}</body></html>`;

/** Small stand-ins for the built pages, with the same data-* blanks as src/pages/gate/*.astro. */
export const PAGES = {
    "/": html('<main id="home">HOME<div class="goal compact" data-if="goal" data-astro-cid-x><strong data-goal="raised">$0</strong> of <span data-goal="target">$100</span><div class="track" data-goal="bar" aria-valuenow="0"><span class="fill" data-goal="fill" style="width:0%"></span></div></div></main><footer><form class="signout" method="post" action="/signout"><button>Sign out</button></form></footer>'),
    "/privacy": html("<main>PRIVACY</main>"),
    "/404": html("<main>NOT FOUND PAGE</main>"),
    "/gate/front": html('<main id="front">FRONT<div class="goal" data-if="goal"><strong data-goal="raised">$0</strong> of <span data-goal="target">$100</span> this month<div class="track" role="progressbar" data-goal="bar" aria-valuenow="0" aria-valuetext="$0 of $100 this month"><span class="fill" data-goal="fill" style="width:0%"></span></div></div><form method="post" action="/access"><input type="hidden" name="next" value="" data-value="next"><input name="email"></form></main>'),
    "/gate/sent": html('<main id="sent">SENT If <strong data-fill="email">that email</strong> has early access, a code is on its way.<form method="post" action="/access/verify"><input type="hidden" name="email" value="" data-value="email" data-astro-cid-c><input type="hidden" name="next" value="" data-value="next"><input name="code"></form></main>'),
    "/gate/code-failed": html('<main id="code-failed">FAILED<form><input type="hidden" name="email" value="" data-value="email"><input type="hidden" name="next" value="" data-value="next"></form><form action="/access"><input type="hidden" name="email" value="" data-value="email"><input type="hidden" name="next" value="" data-value="next"></form></main>'),
    "/gate/request": html('<main id="request">REQUEST<form method="post" action="/request"></form></main>'),
    "/gate/requested": html('<main id="requested">REQUESTED</main>'),
    "/gate/signed-out": html('<main id="signed-out">SIGNED OUT</main>'),
    "/gate/slow-down": html('<main id="slow-down">SLOW DOWN</main>'),
    "/gate/problem": html('<main id="problem"><h1 data-fill="title">Something went wrong</h1><p data-fill="message">Go back and try again.</p></main>'),
    "/gate/admin": html(
        '<main id="admin">ADMIN <strong data-fill="admin-email">an admin</strong><p class="notice" data-if="flash" data-fill="flash"></p><p data-if="hash-mismatch">HASH MISMATCH</p><span data-fill="request-count">0 waiting</span><p data-if="no-requests">NOBODY WAITING</p><ul data-if="has-requests" data-html="requests"></ul><nav data-html="pager"></nav><span data-fill="month">this month</span><p data-fill="goal-exact">$0.00 of $100.00</p><div data-if="goal"><strong data-goal="raised">$0</strong></div><table data-if="has-entries"><tbody data-html="entries"></tbody></table><p data-if="no-entries">NO ENTRIES</p></main>',
    ),
};

/**
 * The static assets binding with html_handling "auto-trailing-slash" and not_found_handling "404-page":
 * known pages are 200 text/html, a file or two, /index.html and /privacy.html redirect, the rest is the 404 page.
 */
export function fakeAssets(pages = PAGES) {
    return {
        requests: [],
        async fetch(input) {
            const req = input instanceof Request ? input : new Request(input);
            const path = new URL(req.url).pathname;
            this.requests.push({ path, headers: Object.fromEntries(req.headers) });
            if (path === "/index.html" || path === "/index") return new Response(null, { status: 307, headers: { Location: "/" } });
            if (path === "/privacy.html") return new Response(null, { status: 307, headers: { Location: "/privacy" } });
            if (path === "/sitemap-index.xml") return new Response("<xml/>", { headers: { "content-type": "application/xml", etag: '"x"' } });
            if (Object.prototype.hasOwnProperty.call(pages, path) && path !== "/404") {
                return new Response(pages[path], { headers: { "content-type": "text/html; charset=utf-8", etag: '"abc"', "cache-control": "public, max-age=0, must-revalidate" } });
            }
            if (path === "/404") return new Response(pages["/404"], { headers: { "content-type": "text/html; charset=utf-8" } });
            return new Response(pages["/404"], { status: 404, headers: { "content-type": "text/html; charset=utf-8" } });
        },
    };
}

/** ExecutionContext: collects waitUntil work so a test can wait for it. */
export function fakeCtx() {
    const work = [];
    return {
        work,
        waitUntil(p) {
            work.push(p);
        },
        async drain() {
            await Promise.all(work.splice(0));
        },
    };
}

export const SECRET = "test-session-secret-0123456789abcdef";
/** The gate requires EMAIL_PEPPER, so tests hash with this one (the same test pepper as the tip jar's vectors). */
export const PEPPER = "test-pepper";
export const hashFor = (email) => hashEmail(email, PEPPER);

export function env(over = {}) {
    return {
        ASSETS: fakeAssets(),
        ACCESS_KV: fakeKV(),
        EMAIL: fakeEmail(),
        SEND_IP_RL: fakeRL(),
        SEND_EMAIL_RL: fakeRL(),
        CODE_SEND_RL: fakeRL(),
        VERIFY_IP_RL: fakeRL(),
        VERIFY_EMAIL_RL: fakeRL(),
        REQUEST_IP_RL: fakeRL(),
        SESSION_SECRET: SECRET,
        EMAIL_PEPPER: PEPPER,
        GOAL_CENTS: "10000",
        EMAIL_FROM: "access@stashfm.app",
        ACCESS_TEAM_DOMAIN: "https://stash-test.cloudflareaccess.com",
        ACCESS_AUD: "aud-0123456789abcdef0123456789abcdef",
        ADMIN_EMAILS: "owner@example.com, Evo@Example.com",
        ...over,
    };
}

export const BASE = "https://stashfm.app";

/** A goal entry as the tip jar or the admin page writes it: goal:<month>:<kind>:<id>, amount in metadata. */
export function goalEntry(month, kind, id, cents, source = kind === "kofi" ? "Ko-fi" : "GitHub Sponsors") {
    const meta = { cents, source, at: `${month}-02T10:00:00.000Z` };
    return { [`goal:${month}:${kind}:${id}`]: { value: JSON.stringify(meta), metadata: meta } };
}

/** A browser-like form POST from this site. */
export function post(path, fields, { origin = BASE, headers = {}, ip = "203.0.113.7" } = {}) {
    const h = { "content-type": "application/x-www-form-urlencoded", "CF-Connecting-IP": ip, ...headers };
    if (origin) h.Origin = origin;
    return new Request(`${BASE}${path}`, { method: "POST", headers: h, body: new URLSearchParams(fields).toString() });
}

export const get = (path, headers = {}) => new Request(`${BASE}${path}`, { headers: { "CF-Connecting-IP": "203.0.113.7", ...headers } });

/** Moves the Worker's clock. Returns a function that puts it back. */
export function freezeClock(ms) {
    const real = clock.now;
    let now = ms;
    clock.now = () => now;
    return {
        advance(byMs) {
            now += byMs;
        },
        restore() {
            clock.now = real;
        },
    };
}

// ---------------------------------------------------------------------------------------------
// HTMLRewriter, enough for worker/pages.js: attribute-presence selectors ([data-x]), and on each
// matched element getAttribute, setAttribute, setInnerContent (text, or { html: true }) and remove.
// Matching close tags are found by counting same-name tags, which holds for the site's markup.
// ---------------------------------------------------------------------------------------------

const VOID = new Set(["area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr"]);
const escText = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
const escAttr = (s) => s.replace(/&/g, "&amp;").replace(/"/g, "&quot;");

function parseAttrs(src) {
    const attrs = [];
    const re = /([^\s=\/>]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+)))?/g;
    let m;
    while ((m = re.exec(src))) attrs.push([m[1].toLowerCase(), m[2] ?? m[3] ?? m[4] ?? null]);
    return attrs;
}

function closeIndex(htmlText, tag, from) {
    const re = new RegExp(`<(/?)${tag}(?=[\\s>/])[^>]*>`, "gi");
    re.lastIndex = from;
    let depth = 1;
    let m;
    while ((m = re.exec(htmlText))) {
        depth += m[1] ? -1 : 1;
        if (depth === 0) return { start: m.index, end: re.lastIndex };
    }
    throw new Error(`fake HTMLRewriter: no </${tag}>`);
}

export class FakeHTMLRewriter {
    constructor() {
        this.handlers = [];
    }

    on(selector, handler) {
        const m = /^\[([\w-]+)\]$/.exec(selector);
        if (!m) throw new Error(`fake HTMLRewriter: unsupported selector ${selector}`);
        this.handlers.push({ attr: m[1], handler });
        return this;
    }

    rewrite(src) {
        let out = "";
        let i = 0;
        const tagRe = /<([a-zA-Z][\w-]*)((?:\s+[^>]*?)?)(\/?)>/g;
        while (i < src.length) {
            tagRe.lastIndex = i;
            const m = tagRe.exec(src);
            if (!m) {
                out += src.slice(i);
                break;
            }
            out += src.slice(i, m.index);
            const tag = m[1].toLowerCase();
            const attrs = parseAttrs(m[2]);
            const matching = this.handlers.filter((h) => attrs.some(([n]) => n === h.attr));
            if (!matching.length) {
                out += m[0];
                i = tagRe.lastIndex;
                continue;
            }
            let removed = false;
            let inner = null;
            const el = {
                tagName: tag,
                getAttribute: (name) => attrs.find(([n]) => n === name)?.[1] ?? null,
                hasAttribute: (name) => attrs.some(([n]) => n === name),
                setAttribute: (name, value) => {
                    const at = attrs.findIndex(([n]) => n === name);
                    if (at === -1) attrs.push([name, String(value)]);
                    else attrs[at] = [name, String(value)];
                },
                setInnerContent: (content, opts = {}) => {
                    inner = opts.html ? String(content) : escText(String(content));
                },
                remove: () => {
                    removed = true;
                },
            };
            for (const { handler } of matching) handler.element?.(el);
            const open = `<${m[1]}${attrs.map(([n, v]) => (v === null ? ` ${n}` : ` ${n}="${escAttr(v)}"`)).join("")}${m[3] ? "/" : ""}>`;
            const isVoid = VOID.has(tag) || m[3];
            if (removed) {
                i = isVoid ? tagRe.lastIndex : closeIndex(src, tag, tagRe.lastIndex).end;
                continue;
            }
            if (inner !== null && !isVoid) {
                const close = closeIndex(src, tag, tagRe.lastIndex);
                out += open + inner + src.slice(close.start, close.end);
                i = close.end;
                continue;
            }
            out += open;
            i = tagRe.lastIndex;
        }
        return out;
    }

    transform(response) {
        const rewrite = (text) => this.rewrite(text);
        const body = new ReadableStream({
            async start(controller) {
                controller.enqueue(new TextEncoder().encode(rewrite(await response.text())));
                controller.close();
            },
        });
        return new Response(body, { status: response.status, statusText: response.statusText, headers: response.headers });
    }
}

globalThis.HTMLRewriter ??= FakeHTMLRewriter;
