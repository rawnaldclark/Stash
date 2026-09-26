import { allowedCover } from "./validate.js";
const GET_STASH = "https://github.com/rawnaldclark/Stash/releases/latest";
const RELEASE_SHA256 = "8E:12:46:95:BE:83:58:C5:44:50:D9:F4:A2:B9:39:EF:77:2C:24:19:2E:C6:1C:FB:6B:33:08:7B:AB:08:A8:BA";
const DEBUG_SHA256 = "80:0F:72:0A:31:6B:07:F6:45:38:23:71:FE:F4:1D:FA:B6:4F:39:EF:DA:D7:04:D6:A5:05:02:65:F1:6C:70:AA";

export const esc = (s) => String(s ?? "")
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&#39;");

export function assetLinks() {
    const entry = (pkg, fp) => ({
        relation: ["delegate_permission/common.handle_all_urls"],
        target: { namespace: "android_app", package_name: pkg, sha256_cert_fingerprints: [fp] },
    });
    return [entry("com.stash.app", RELEASE_SHA256), entry("com.stash.app.debug", DEBUG_SHA256)];
}

/** An intent:// URL that opens Stash on Android, falling back to the https link in a browser. */
function openInStash(url) {
    const u = new URL(url);
    const fallback = encodeURIComponent(url);
    return `intent://${u.host}${u.pathname}${u.search}#Intent;scheme=https;package=com.stash.app;S.browser_fallback_url=${fallback};end`;
}

function shell({ title, description, image, body, pageUrl }) {
    return `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)}</title>
<meta property="og:title" content="${esc(title)}">
<meta property="og:description" content="${esc(description)}">
${image ? `<meta property="og:image" content="${esc(image)}">` : ""}
<style>body{font-family:system-ui,sans-serif;background:#0d0d12;color:#eee;max-width:560px;margin:0 auto;padding:24px}
a.btn{display:inline-block;padding:12px 18px;border-radius:12px;background:#8b5cf6;color:#fff;text-decoration:none;margin:6px 8px 6px 0}
a.alt{background:#2a2a35}li{margin:6px 0;color:#ccc}.muted{color:#999}</style></head>
<body>${body}
<p>${pageUrl ? `<a class="btn" href="${esc(openInStash(pageUrl))}">Open in Stash</a>` : ""}<a class="btn alt" href="${GET_STASH}">Get Stash</a></p>
</body></html>`;
}

export function mixPage(doc, pageUrl) {
    const items = doc.tracks.slice(0, 10).map((t) => `<li>${esc(t.t)} <span class="muted">· ${esc(t.a)}</span></li>`).join("");
    const more = doc.tracks.length > 10 ? `<p class="muted">…and ${doc.tracks.length - 10} more</p>` : "";
    const by = doc.sharedBy ? ` · shared by ${esc(doc.sharedBy)}` : "";
    return shell({
        title: doc.name,
        description: `${doc.tracks.length} tracks · shared on Stash`,
        image: doc.covers?.find(allowedCover), // docs stored before the allowlist may carry any host
        pageUrl,
        body: `<h1>${esc(doc.name)}</h1><p class="muted">${doc.tracks.length} tracks${by}</p><ol>${items}</ol>${more}`,
    });
}

export function trackPage(params, pageUrl) {
    const t = (params.get("t") || "Unknown song").slice(0, 500);
    const a = (params.get("a") || "").slice(0, 500);
    return shell({ title: a ? `${t} · ${a}` : t, description: "A song shared on Stash", pageUrl,
        body: `<h1>${esc(t)}</h1><p class="muted">${esc(a)}</p>` });
}

export function messagePage(title, text) {
    return shell({ title, description: text, body: `<h1>${esc(title)}</h1><p class="muted">${esc(text)}</p>` });
}

/** GET /l/{code}: the Listen Together invite page (spec 2026-09-24 §2), built like the mix page. */
export function roomPage(pv, pageUrl) {
    const host = pv.hostName || "A friend";
    const listening = `${pv.memberCount} listening${pv.full ? " · full" : ""}`;
    const now = pv.track ? `<p class="muted">Now playing: ${esc(pv.track.t)} · ${esc(pv.track.a)}</p>` : "";
    return shell({
        title: `Join ${host}'s session in Stash`,
        description: `${listening} · Listen Together on Stash`,
        pageUrl,
        body: `<h1>Join ${esc(host)}'s session in Stash</h1><p class="muted">${listening}</p>${now}`,
    });
}
