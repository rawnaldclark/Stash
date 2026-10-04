/**
 * Album art for a song page's link preview (stashfm.app contract 2026-10-03), in order: the stored art, Spotify's
 * oEmbed, Deezer by ISRC, YouTube's hqdefault, else none. Every lookup is time-boxed and can only fail to "nothing",
 * so a slow or broken service never breaks a page.
 */
import { allowedCover, isrcCode, isSpotifyId, isYouTubeId } from "./validate.js";

export const LOOKUP_TIMEOUT_MS = 2000;
const CACHE_TTL_S = 86_400;

/**
 * { url, looked }: [looked] is true when the URL came from a network lookup, the only kind worth keeping.
 * Stored art is already kept, and hqdefault costs nothing to rebuild; keeping it after a lookup timed out would
 * lock in a worse image than the real cover a later view may find.
 */
export async function findArt(track, { timeoutMs = LOOKUP_TIMEOUT_MS } = {}) {
    if (typeof track.art === "string" && allowedCover(track.art)) return { url: track.art, looked: false };
    const found = (await spotifyArt(track.sp, timeoutMs)) ?? (await deezerArt(track.isrc, timeoutMs));
    return found ? { url: found, looked: true } : { url: youtubeArt(track.yt), looked: false };
}

export const resolveArt = async (track, opts) => (await findArt(track, opts)).url;

/**
 * GET + JSON with a hard deadline, null on any failure. Aborting frees the connection; the race also covers
 * a fetch that ignores its signal.
 */
async function lookupJson(url, timeoutMs) {
    const abort = new AbortController();
    const timer = setTimeout(() => abort.abort(), timeoutMs);
    const deadline = new Promise((resolve) => abort.signal.addEventListener("abort", () => resolve(null), { once: true }));
    const lookup = (async () => {
        const r = await fetch(url, { signal: abort.signal });
        return r.ok ? await r.json() : null;
    })().catch(() => null);
    try {
        return await Promise.race([lookup, deadline]);
    } finally {
        clearTimeout(timer);
    }
}

/**
 * oEmbed's thumbnail_url is on image-cdn-*.spotifycdn.com (2026-10-03). The same image id is served by i.scdn.co,
 * which both allowlists already carry, so the URL moves there instead of a new host joining the lists. The id's
 * prefix names its size: ab67616d00001e02 is a 300 px album cover, ab67616d0000b273 the 640 px one previews want.
 */
export function spotifyCover(thumb) {
    let u;
    try { u = new URL(thumb); } catch { return null; }
    const id = /^\/image\/([0-9a-f]{24,64})$/.exec(u.pathname)?.[1];
    const fromSpotify = u.hostname === "i.scdn.co" || u.hostname.endsWith(".spotifycdn.com");
    if (u.protocol !== "https:" || !id || !fromSpotify) return null;
    return `https://i.scdn.co/image/${id.replace(/^ab67616d00001e02/, "ab67616d0000b273")}`;
}

async function spotifyArt(sp, timeoutMs) {
    if (!isSpotifyId(sp)) return null;
    const track = `https://open.spotify.com/track/${sp}`;
    return spotifyCover((await lookupJson(`https://open.spotify.com/oembed?url=${encodeURIComponent(track)}`, timeoutMs))?.thumbnail_url);
}

/** An unknown ISRC is a 200 with `{ error }`, so the shape is checked rather than the status. */
async function deezerArt(isrc, timeoutMs) {
    const code = isrcCode(isrc);
    if (!code) return null;
    const cover = (await lookupJson(`https://api.deezer.com/track/isrc:${code}`, timeoutMs))?.album?.cover_xl;
    return typeof cover === "string" && allowedCover(cover) ? cover : null;
}

/** hqdefault exists for every video; sddefault and maxresdefault often 404. */
const youtubeArt = (yt) => (isYouTubeId(yt) ? `https://i.ytimg.com/vi/${yt}/hqdefault.jpg` : null);

/**
 * Art for a legacy long link, which has no stored doc to write art back to: a looked-up URL is kept a day in the
 * Cache API under the ids it came from (any title, any parameter order). The Cache API is absent in Node and does
 * nothing on workers.dev, so there every view just looks up again. [later] runs the write off the response's path.
 */
export async function legacyArt(origin, track, later) {
    const sp = isSpotifyId(track.sp) ? track.sp : "";
    const isrc = isrcCode(track.isrc) ?? "";
    const cache = globalThis.caches?.default;
    if (!cache || !(sp || isrc)) return resolveArt(track);
    const key = new Request(`${origin}/__art?${new URLSearchParams({ sp, isrc })}`);
    const hit = await cache.match(key).catch(() => undefined);
    if (hit) {
        const url = await hit.text();
        if (allowedCover(url)) return url;
    }
    const { url, looked } = await findArt(track);
    if (looked) {
        const res = new Response(url, { headers: { "cache-control": `public, max-age=${CACHE_TTL_S}` } });
        await later(cache.put(key, res).catch((e) => console.error(e)));
    }
    return url;
}
