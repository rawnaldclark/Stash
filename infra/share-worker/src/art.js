/**
 * Album art for a song page's link preview (stashfm.app contract 2026-10-03), in order: the sharer's art, Spotify's
 * oEmbed, Deezer by ISRC, YouTube's hqdefault, else none. Every lookup is time-boxed and can only fail to "nothing",
 * so a slow or broken service never breaks a page. Art is `{ url, width?, height? }`; the size is set only when known.
 */
import { cleanCover, isrcCode, isSpotify640, isSpotifyId, isYouTubeId, spotifyCover } from "./validate.js";

export const LOOKUP_TIMEOUT_MS = 2000;
/** A lookup that found nothing isn't repeated for 6 h: every view of a song no service knows would ask again. */
export const RETRY_AFTER_MISS_MS = 6 * 3_600_000;
const FOUND_TTL_S = 86_400;
const MISS_TTL_S = 21_600;

/** True when there's an id a lookup can use; otherwise only the YouTube fallback is possible. */
export const canLookUp = (track) => isSpotifyId(track.sp) || isrcCode(track.isrc) !== null;

/**
 * Spotify and Deezer asked at once, so the wait is at most one timeout (~2 s) rather than two; Spotify's answer wins
 * when both have one. Null when neither does.
 */
export async function lookUpArt(track, { timeoutMs = LOOKUP_TIMEOUT_MS } = {}) {
    const spotify = spotifyArt(track.sp, timeoutMs);
    const deezer = deezerArt(track.isrc, timeoutMs);
    return (await spotify) ?? (await deezer);
}

/** hqdefault exists for every video (sddefault and maxresdefault often 404). It's 4:3 with letterbox bars. */
export const youtubeArt = (yt) => (isYouTubeId(yt) ? { url: `https://i.ytimg.com/vi/${yt}/hqdefault.jpg`, width: 480, height: 360 } : null);

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

/** oEmbed's thumbnail_url, moved to i.scdn.co at 640 px (validate.js spotifyCover). */
async function spotifyArt(sp, timeoutMs) {
    if (!isSpotifyId(sp)) return null;
    const track = `https://open.spotify.com/track/${sp}`;
    const url = spotifyCover((await lookupJson(`https://open.spotify.com/oembed?url=${encodeURIComponent(track)}`, timeoutMs))?.thumbnail_url);
    if (!url) return null;
    return isSpotify640(url) ? { url, width: 640, height: 640 } : { url };
}

/**
 * Deezer's album.cover_xl, sized from its path. An unknown ISRC is a 200 with `{ error }`, and an album with no art has
 * an empty image id (`/images/cover//1000x1000-…`, a placeholder), so a 32-hex id is required.
 */
const DEEZER_COVER = /^\/images\/cover\/[0-9a-f]{32}\/(\d+)x(\d+)-/;
async function deezerArt(isrc, timeoutMs) {
    const code = isrcCode(isrc);
    if (!code) return null;
    const url = cleanCover((await lookupJson(`https://api.deezer.com/track/isrc:${code}`, timeoutMs))?.album?.cover_xl);
    const size = url && DEEZER_COVER.exec(new URL(url).pathname);
    return size ? { url, width: Number(size[1]), height: Number(size[2]) } : null;
}

/**
 * A short link's art: { art, patch }. The sharer's art first (it's part of the link's identity), then art found before,
 * then a lookup, unless one found nothing in the last 6 h or [allowLookup] (ART_RL) says no, then hqdefault.
 * [patch] is what to write back into the KV doc: `found` (trusted, it came from Spotify or Deezer) or `artTriedAt`.
 */
export async function songLinkArt(record, { now = Date.now(), allowLookup = async () => true, timeoutMs } = {}) {
    const own = cleanCover(record.track.art);
    if (own) return { art: { url: own }, patch: null };
    const found = cleanCover(record.found?.url);
    if (found) return { art: { ...record.found, url: found }, patch: null };
    const fallback = youtubeArt(record.track.yt);
    const triedLately = record.artTriedAt > now - RETRY_AFTER_MISS_MS;
    if (!canLookUp(record.track) || triedLately || !(await allowLookup())) return { art: fallback, patch: null };
    const art = await lookUpArt(record.track, { timeoutMs });
    return art ? { art, patch: { found: art } } : { art: fallback, patch: { artTriedAt: now } };
}

/**
 * Art for a legacy long link, which has no stored doc: what a lookup gave is kept in the Cache API under the ids it came
 * from (any title, any parameter order), a find for a day and a miss for 6 h. The Cache API is absent in Node and does
 * nothing on workers.dev, so there every view looks up again (ART_RL still applies). [later] runs the write off the
 * response's path.
 */
export async function legacyArt(origin, track, { later, allowLookup = async () => true }) {
    const fallback = youtubeArt(track.yt);
    if (!canLookUp(track)) return fallback;
    const cache = globalThis.caches?.default;
    const ids = new URLSearchParams({ sp: isSpotifyId(track.sp) ? track.sp : "", isrc: isrcCode(track.isrc) ?? "" });
    const key = new Request(`${origin}/__art?${ids}`);
    const kept = cache ? await cache.match(key).then((r) => r?.json()).catch(() => undefined) : undefined;
    if (kept && typeof kept === "object") {
        const url = cleanCover(kept.url);
        if (url) return { ...kept, url };
        if (kept.url === undefined) return fallback; // a recent lookup found nothing
    }
    if (!(await allowLookup())) return fallback;
    const art = await lookUpArt(track);
    if (cache) {
        const res = new Response(JSON.stringify(art ?? {}), {
            headers: { "content-type": "application/json", "cache-control": `public, max-age=${art ? FOUND_TTL_S : MISS_TTL_S}` },
        });
        await later(cache.put(key, res).catch((e) => console.error(e)));
    }
    return art ?? fallback;
}
