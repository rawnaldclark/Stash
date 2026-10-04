/**
 * The gate's pages are ordinary Astro pages in src/pages/gate/, built with the rest of the site, so they
 * look like it. The Worker fetches one from the static assets and fills in the blanks with HTMLRewriter:
 *
 *   data-fill="key"    the element's text becomes fill[key] (escaped)
 *   data-value="key"   an <input>'s value becomes fill[key]
 *   data-html="key"    the element's contents become html[key] (already-escaped HTML the Worker built)
 *   data-if="flag"     the element is removed unless flags[flag]
 *   data-goal="..."    the goal bar: "raised", "target" (text), "bar" (ARIA values), "fill" (width)
 *
 * /gate/* itself is never served as is (index.js answers 404 there).
 */

/** Same policy as public/_headers, which covers the pages Cloudflare serves without the Worker. Keep them equal. */
export const CSP =
    "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'none'; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

/**
 * The CSP with more places forms may go. Only /admin uses it: when the Access session has expired, Cloudflare
 * Access answers an admin form post with a redirect to the team's sign-in page, which form-action 'self' blocks.
 */
export const cspWithFormAction = (origin) => (origin ? CSP.replace("form-action 'self'", `form-action 'self' ${origin}`) : CSP);

export const SECURITY_HEADERS = {
    "Content-Security-Policy": CSP,
    "Referrer-Policy": "strict-origin-when-cross-origin",
    "X-Content-Type-Options": "nosniff",
    "Permissions-Policy": "camera=(), microphone=(), geolocation=(), browsing-topics=()",
};

/**
 * Every response the Worker returns gets the site's security headers (_headers doesn't apply to them).
 * [formAction] adds one origin to form-action (see cspWithFormAction).
 */
export function withSecurityHeaders(response, { formAction } = {}) {
    const out = new Response(response.body, response);
    for (const [k, v] of Object.entries(SECURITY_HEADERS)) out.headers.set(k, v);
    if (formAction) out.headers.set("Content-Security-Policy", cspWithFormAction(formAction));
    return out;
}

/** A response that depends on who's asking, or on live numbers: never cached, never revalidated with an old ETag. */
export function personal(response, status = response.status) {
    const out = new Response(response.body, { status, headers: response.headers });
    out.headers.set("Cache-Control", "no-store");
    out.headers.set("Vary", "Cookie");
    out.headers.delete("ETag");
    out.headers.delete("Last-Modified");
    return out;
}

/** Fetches a built page from the static assets, without the visitor's conditional headers (a 304 can't be filled in). */
export function fetchAsset(env, url, path) {
    return env.ASSETS.fetch(new Request(new URL(path, url.origin)));
}

const FALLBACK = (title) =>
    `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>${title} · Stash</title></head><body style="font-family:system-ui,sans-serif;max-width:560px;margin:0 auto;padding:24px"><h1>${title}</h1><p><a href="/">Back to stashfm.app</a></p></body></html>`;

/**
 * Fills a page. [goal] is goalView()'s result (or null to leave the bar out: its block carries data-if="goal").
 */
export function fill(response, { text = {}, html = {}, flags = {}, goal = null } = {}) {
    const has = (obj, key) => Object.prototype.hasOwnProperty.call(obj, key);
    const allFlags = { ...flags, goal: Boolean(goal) };
    return new HTMLRewriter()
        .on("[data-if]", {
            element(el) {
                if (!allFlags[el.getAttribute("data-if")]) el.remove();
            },
        })
        .on("[data-fill]", {
            element(el) {
                const key = el.getAttribute("data-fill");
                if (has(text, key)) el.setInnerContent(String(text[key]));
            },
        })
        .on("[data-value]", {
            element(el) {
                const key = el.getAttribute("data-value");
                if (has(text, key)) el.setAttribute("value", String(text[key]));
            },
        })
        .on("[data-html]", {
            element(el) {
                const key = el.getAttribute("data-html");
                if (has(html, key)) el.setInnerContent(html[key], { html: true });
            },
        })
        .on("[data-goal]", {
            element(el) {
                if (!goal) return;
                const part = el.getAttribute("data-goal");
                if (part === "raised") el.setInnerContent(goal.raised);
                else if (part === "target") el.setInnerContent(goal.target);
                else if (part === "bar") {
                    el.setAttribute("aria-valuenow", String(goal.percent));
                    el.setAttribute("aria-valuetext", goal.text);
                } else if (part === "fill") el.setAttribute("style", `width:${goal.percent}%`);
            },
        })
        .transform(response);
}

/** A gate page (src/pages/gate/<name>.astro), filled, with [status] and no caching. */
export async function gatePage(env, url, name, { status = 200, headers = {}, ...fills } = {}) {
    let res = await fetchAsset(env, url, `/gate/${name}`);
    if (res.status !== 200) {
        // Only if the build is missing a page; never expected in production.
        console.error(`gate page missing: /gate/${name} (${res.status})`);
        res = new Response(FALLBACK(fills.text?.title || "Stash"), { headers: { "content-type": "text/html; charset=utf-8" } });
    }
    const out = personal(fill(res, fills), status);
    for (const [k, v] of Object.entries(headers)) out.headers.append(k, v);
    return out;
}

export const esc = (s) =>
    String(s ?? "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
