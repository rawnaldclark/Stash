import { cleanCover, isSpotifyId, isYouTubeId } from "./validate.js";
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

/**
 * The host every Stash version's App Links filter knows. Versions from before stashfm.app ignore stashfm.app links, so
 * "Open in Stash" always opens this host's link (newer versions handle both).
 */
export const APP_LINK_ORIGIN = "https://stash-share.rawnaldclark.workers.dev";

/**
 * An intent:// URL that opens [appUrl] in Stash on Android, or [pageUrl] in the browser when Stash isn't installed.
 * [appUrl] defaults to [pageUrl] moved to APP_LINK_ORIGIN.
 */
function openInStash(pageUrl, appUrl) {
    const page = new URL(pageUrl);
    const u = new URL(appUrl ?? `${page.pathname}${page.search}`, APP_LINK_ORIGIN);
    const fallback = encodeURIComponent(pageUrl);
    return `intent://${u.host}${u.pathname}${u.search}#Intent;scheme=https;package=com.stash.app;S.browser_fallback_url=${fallback};end`;
}

/** Dark with one purple accent, as before. The song page adds a big cover over a blurred copy of itself. */
const STYLE = `:root{color-scheme:dark}
body{font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;background:#0d0d12;color:#eee;max-width:560px;margin:0 auto;padding:24px;line-height:1.4}
a.btn{display:inline-block;padding:12px 18px;border-radius:12px;background:#8b5cf6;color:#fff;text-decoration:none;font-weight:600;margin:6px 8px 6px 0}
a.alt{background:#2a2a35}li{margin:6px 0;color:#ccc}.muted{color:#999}.foot{font-size:.85rem;margin-top:28px}
.song{text-align:center}.song .actions{margin-top:20px}.song a.btn{margin:6px 4px}
.cover{display:block;width:min(320px,78vw);height:min(320px,78vw);object-fit:cover;margin:16px auto 28px;border-radius:16px;background:#1b1b24;box-shadow:0 16px 48px rgba(0,0,0,.55)}
.blank{display:flex;align-items:center;justify-content:center;font-size:96px;color:#8b5cf6}
.crop{overflow:hidden}.crop img{display:block;width:100%;height:100%;object-fit:cover;transform:scale(1.34)}
.bg{position:fixed;inset:0;width:100%;height:100%;object-fit:cover;filter:blur(60px) brightness(.35) saturate(1.4);transform:scale(1.25);z-index:-1}
.song h1{font-size:1.75rem;line-height:1.2;margin:0 0 8px;overflow-wrap:anywhere}.artist{font-size:1.15rem;color:#ddd;margin:0 0 4px}.song .muted{margin:0}`;

/**
 * Every page's frame and its link-preview tags (contract 2026-10-03): Open Graph for Discord, WhatsApp, Telegram,
 * iMessage and Facebook, twitter:* for X. [title] is the preview's title, [docTitle] the browser tab's.
 * [image] is `{ url, width?, height? }`. [open] adds "Open in Stash" ([appUrl] is the link the app gets, see
 * openInStash); [actions] are more {label, href} buttons before "Get Stash".
 */
function shell({ title, docTitle = title, description, type = "website", image, imageAlt, card, pageUrl, open, appUrl, actions = [], layout, body, foot }) {
    const sized = image && Number.isInteger(image.width) && Number.isInteger(image.height);
    const tag = (attr, key, value) => `<meta ${attr}="${key}" content="${esc(value)}">`;
    const og = (key, value) => tag("property", key, value);
    const tw = (key, value) => tag("name", key, value);
    const head = [
        `<title>${esc(docTitle)}</title>`,
        tag("name", "description", description),
        pageUrl && `<link rel="canonical" href="${esc(pageUrl)}">`,
        og("og:site_name", "Stash"), og("og:type", type), pageUrl && og("og:url", pageUrl),
        og("og:title", title), og("og:description", description),
        image && og("og:image", image.url),
        sized && og("og:image:width", image.width), sized && og("og:image:height", image.height),
        image && og("og:image:alt", imageAlt),
        // A large card shows the cover big in X and Discord; without an image there is nothing to make large.
        tw("twitter:card", card ?? (image ? "summary_large_image" : "summary")),
        tw("twitter:title", title), tw("twitter:description", description),
        image && tw("twitter:image", image.url), image && tw("twitter:image:alt", imageAlt),
    ].filter(Boolean).join("\n");
    const buttons = [
        open ? `<a class="btn" href="${esc(openInStash(pageUrl, appUrl))}">Open in Stash</a>` : "",
        ...actions.map((b) => `<a class="btn alt" href="${esc(b.href)}">${esc(b.label)}</a>`),
        `<a class="${open ? "btn alt" : "btn"}" href="${GET_STASH}">Get Stash</a>`,
    ].join("");
    return `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="theme-color" content="#0d0d12">
${head}
<style>${STYLE}</style></head>
<body><main${layout ? ` class="${layout}"` : ""}>${body}
<p class="actions">${buttons}</p>${foot ? `<p class="muted foot">${esc(foot)}</p>` : ""}
</main></body></html>`;
}

const cover = (url) => (url ? { url } : undefined);
/** A YouTube hqdefault: 4:3 with letterbox bars round a 16:9 frame. */
const HQDEFAULT = /^https:\/\/i\.ytimg\.com\/vi\/[A-Za-z0-9_-]{11}\/hqdefault\.jpg$/;
const count = (n) => `${n} ${n === 1 ? "song" : "songs"}`;

export function mixPage(doc, pageUrl) {
    const items = doc.tracks.slice(0, 10).map((t) => `<li>${esc(t.t)} <span class="muted">· ${esc(t.a)}</span></li>`).join("");
    const more = doc.tracks.length > 10 ? `<p class="muted">…and ${doc.tracks.length - 10} more</p>` : "";
    const by = doc.sharedBy ? ` · shared by ${esc(doc.sharedBy)}` : "";
    return shell({
        title: doc.name,
        description: `${count(doc.tracks.length)} · shared on Stash${doc.sharedBy ? ` by ${doc.sharedBy}` : ""}`,
        type: "music.playlist",
        image: cover(doc.covers?.map(cleanCover).find(Boolean)), // docs stored before the allowlist may carry any host
        imageAlt: `Cover of ${doc.name}`,
        pageUrl,
        open: true,
        body: `<h1>${esc(doc.name)}</h1><p class="muted">${count(doc.tracks.length)}${by}</p><ol>${items}</ol>${more}`,
    });
}

/**
 * A shared song, from a short link or a legacy long one: what friends without Stash see, so it can be played
 * from Spotify or YouTube Music too. [image] is the preview art (src/art.js), or null for the ♪ tile. [appUrl] is the
 * link "Open in Stash" hands the app (a short link's long form, which every app version reads).
 */
export function songPage(track, { pageUrl, appUrl, image }) {
    const { a, al } = track;
    const t = track.t || "Unknown song"; // only a hand-edited legacy link has no title
    const alt = a ? `Album art for ${t} by ${a}` : `Album art for ${t}`;
    const actions = [];
    if (isSpotifyId(track.sp)) actions.push({ label: "Listen on Spotify", href: `https://open.spotify.com/track/${track.sp}` });
    if (isYouTubeId(track.yt)) actions.push({ label: "Listen on YouTube Music", href: `https://music.youtube.com/watch?v=${track.yt}` });
    // A hqdefault is zoomed 4/3 inside a square so its letterbox bars fall outside; for a YouTube Music art track that
    // square is the album cover itself. og:image stays the plain URL.
    const shown = !image ? `<div class="cover blank" aria-hidden="true">♪</div>`
        : HQDEFAULT.test(image.url) ? `<div class="cover crop"><img src="${esc(image.url)}" alt="${esc(alt)}"></div>`
            : `<img class="cover" src="${esc(image.url)}" alt="${esc(alt)}">`;
    const backdrop = image ? `<img class="bg" src="${esc(image.url)}" alt="">` : "";
    return shell({
        title: t,
        docTitle: a ? `${t} · ${a}` : t,
        description: [a, al].filter(Boolean).join(" · ") || "A song shared on Stash",
        type: "music.song",
        card: "summary_large_image",
        image,
        imageAlt: alt,
        pageUrl,
        open: true,
        appUrl,
        actions,
        layout: "song",
        body: `${backdrop}${shown}<h1>${esc(t)}</h1>${a ? `<p class="artist">${esc(a)}</p>` : ""}${al ? `<p class="muted">${esc(al)}</p>` : ""}`,
        foot: "Shared from Stash, a music player for Android.",
    });
}

export function messagePage(title, text, pageUrl) {
    return shell({ title, description: text, pageUrl, body: `<h1>${esc(title)}</h1><p class="muted">${esc(text)}</p>` });
}

/** GET /l/{code}: the Listen Together invite page (spec 2026-09-24 §2), built like the mix page. */
export function roomPage(pv, pageUrl) {
    const host = pv.hostName || "A friend";
    const listening = `${pv.memberCount} listening${pv.full ? " · full" : ""}`;
    const now = pv.track ? `<p class="muted">Now playing: ${esc(pv.track.t)} · ${esc(pv.track.a)}</p>` : "";
    return shell({
        title: `Join ${host}'s session in Stash`,
        description: `${listening} · Listen Together on Stash`,
        image: cover(cleanCover(pv.track?.art)),
        imageAlt: pv.track ? `Album art for ${pv.track.t} by ${pv.track.a}` : undefined,
        pageUrl,
        open: true,
        body: `<h1>Join ${esc(host)}'s session in Stash</h1><p class="muted">${listening}</p>${now}`,
    });
}

/** GET / on stashfm.app: a placeholder until the website exists. */
export function landingPage(pageUrl) {
    const line = "A music player for Android: your Spotify and YouTube Music library on your phone. No account, no subscription, no ads.";
    return shell({ title: "Stash", description: line, pageUrl, body: `<h1>Stash</h1><p class="muted">${esc(line)}</p>` });
}
