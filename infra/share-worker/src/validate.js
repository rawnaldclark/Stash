export const MAX_BODY_BYTES = 1_000_000;
export const MAX_TRACKS = 2000;

/**
 * Album-art CDNs Stash itself loads art from. Covers on any other host are dropped, so a sharer
 * can't point recipients' apps (or link previews) at a server that logs their IPs.
 * Keep in sync with ShareConfig.COVER_HOSTS in core/model (ShareLinks.kt).
 */
export const COVER_HOSTS = [
    "i.scdn.co", "mosaic.scdn.co", // Spotify
    "i.ytimg.com", "lh3.googleusercontent.com", "yt3.googleusercontent.com", "yt3.ggpht.com", // YouTube
    "lastfm.freetls.fastly.net", "lastfm-img.freetls.fastly.net", // Last.fm
    "static.qobuz.com", "c.saavncdn.com", // Qobuz, JioSaavn
    // Deezer: api.deezer.com's album.cover_xl is on cdn-images (checked 2026-10-03); e-cdns-images is its older name,
    // still serving the same paths. Song-link previews fall back to Deezer by ISRC (src/art.js).
    "cdn-images.dzcdn.net", "e-cdns-images.dzcdn.net",
];

/**
 * A cover URL as the parsed URL (`href`) when it is https on a [COVER_HOSTS] host (exactly, or a subdomain), else null.
 * The check reads the parsed host, so the parsed form is what gets stored and shown: a raw `https://i.scdn.co\@evil/x`
 * or a tab in the host reads differently to other parsers (crawlers, phones). Credentials and ports are refused outright.
 */
export function cleanCover(url) {
    if (typeof url !== "string" || url.length > 1000) return null;
    let u;
    try { u = new URL(url); } catch { return null; }
    if (u.protocol !== "https:" || u.username || u.password || u.port) return null;
    return COVER_HOSTS.some((h) => u.hostname === h || u.hostname.endsWith(`.${h}`)) ? u.href : null;
}

const SPOTIFY_ALBUM_300 = "ab67616d00001e02";
const SPOTIFY_ALBUM_640 = "ab67616d0000b273";

/**
 * A Spotify image URL as i.scdn.co, already on both allowlists, at 640 px when it's a 300 px album cover; null when it
 * isn't one. oEmbed answers on image-cdn-*.spotifycdn.com (2026-10-03), which serves the same image ids. The id's
 * prefix names its size: ab67616d00001e02 is a 300 px album cover, ab67616d0000b273 the 640 px one.
 */
export function spotifyCover(url) {
    let u;
    try { u = new URL(url); } catch { return null; }
    const id = /^\/image\/([0-9a-f]{24,64})$/.exec(u.pathname)?.[1];
    const fromSpotify = u.hostname === "i.scdn.co" || u.hostname.endsWith(".spotifycdn.com");
    if (u.protocol !== "https:" || u.username || u.password || u.port || !id || !fromSpotify) return null;
    return `https://i.scdn.co/image/${id.startsWith(SPOTIFY_ALBUM_300) ? SPOTIFY_ALBUM_640 + id.slice(SPOTIFY_ALBUM_300.length) : id}`;
}

export const isSpotify640 = (url) => url.startsWith(`https://i.scdn.co/image/${SPOTIFY_ALBUM_640}`);

const str = (v, min, max) => typeof v === "string" && v.trim().length >= min && v.length <= max;
const optStr = (v, max) => v === undefined || v === null || (typeof v === "string" && v.length <= max);

/** Spec §3 limits. Returns an error string, or null when the document is valid. */
export function validateDoc(doc) {
    if (!doc || typeof doc !== "object") return "doc missing";
    if (doc.v !== 1) return "unsupported v";
    if (!str(doc.name, 1, 100)) return "bad name";
    if (!optStr(doc.sharedBy, 40)) return "bad sharedBy";
    if (doc.covers !== undefined) {
        if (!Array.isArray(doc.covers) || doc.covers.length > 4) return "bad covers";
        if (!doc.covers.every((c) => typeof c === "string" && c.startsWith("https://") && c.length <= 1000)) return "bad cover url";
    }
    if (!Array.isArray(doc.tracks) || doc.tracks.length < 1 || doc.tracks.length > MAX_TRACKS) return "bad tracks";
    for (const t of doc.tracks) {
        if (!t || !str(t.t, 1, 500) || !str(t.a, 1, 500)) return "bad track";
        if (!optStr(t.al, 500) || !optStr(t.isrc, 20) || !optStr(t.sp, 40) || !optStr(t.yt, 20)) return "bad track field";
        if (t.d !== undefined && t.d !== null && !(Number.isInteger(t.d) && t.d > 0)) return "bad duration";
    }
    return null;
}

/** Copies only the §3 fields of a validated doc; unknown client fields are dropped. */
export function cleanDoc(doc) {
    const covers = doc.covers?.map(cleanCover).filter(Boolean);
    return {
        v: doc.v,
        name: doc.name,
        ...pick(doc, ["sharedBy"]),
        // Dropped, not rejected: the app builds covers from local art, which can come from other hosts.
        ...(covers?.length ? { covers } : {}),
        tracks: doc.tracks.map((t) => pick(t, ["t", "a", "al", "d", "isrc", "sp", "yt"])),
    };
}

const pick = (o, keys) => Object.fromEntries(keys.filter((k) => o[k] !== undefined && o[k] !== null).map((k) => [k, o[k]]));

/** base64url of 32 random bytes = 43 chars. */
export const validEditKey = (k) => typeof k === "string" && /^[A-Za-z0-9_-]{43}$/.test(k);

/** Ids that build lookup and listen URLs (src/art.js, the song page): anything else would be spliced into a URL as is. */
export const isSpotifyId = (v) => typeof v === "string" && /^[A-Za-z0-9]{22}$/.test(v);
export const isYouTubeId = (v) => typeof v === "string" && /^[A-Za-z0-9_-]{11}$/.test(v);
/** An ISRC in the 12-character form Deezer looks up (hyphens and case forgiven), or null. */
export function isrcCode(v) {
    const code = typeof v === "string" ? v.replace(/-/g, "").toUpperCase() : "";
    return /^[A-Z0-9]{12}$/.test(code) ? code : null;
}

/** Biggest POST /v1/tracks body: three 500-char fields fully escaped as \uXXXX, plus art, is about 15 KB. */
export const MAX_TRACK_BODY_BYTES = 32_768;
const TRACK_TEXT = { al: 500, isrc: 20, sp: 40, yt: 20 };
const absent = (v) => v === undefined || v === null;

/** POST /v1/tracks (contract 2026-10-03), with a mix track's limits. Returns an error string, or null when valid. */
export function validateTrackLink(b) {
    if (!b || typeof b !== "object" || Array.isArray(b)) return "body missing";
    if (!str(b.t, 1, 500) || !str(b.a, 1, 500)) return "bad title or artist";
    for (const [k, max] of Object.entries(TRACK_TEXT)) if (!optStr(b[k], max)) return `bad ${k}`;
    if (!absent(b.d) && !(Number.isSafeInteger(b.d) && b.d > 0)) return "bad duration";
    return null;
}

/**
 * The song as stored: trimmed text, blank and unknown fields gone. Art is kept only from a known cover host, in its
 * parsed form (a Spotify cover at 640 px), like a mix's covers: dropped, not rejected, since the app's local art can come
 * from anywhere. The art is part of the song's identity (src/tracks.js), so it can never change once stored.
 */
export function cleanTrackLink(b) {
    const track = { t: b.t.trim(), a: b.a.trim() };
    for (const k of ["al", "d", "isrc", "sp", "yt"]) {
        const v = typeof b[k] === "string" ? b[k].trim() : b[k];
        if (!absent(v) && v !== "") track[k] = v;
    }
    const art = cleanCover(b.art);
    if (art) track.art = spotifyCover(art) ?? art;
    return track;
}

/** One song descriptor (the same fields and limits as a mix track), cleaned; null when invalid. Used by Listen Together. */
export function cleanTrack(t) {
    if (!t || typeof t !== "object" || !str(t.t, 1, 500) || !str(t.a, 1, 500)) return null;
    if (!optStr(t.al, 500) || !optStr(t.isrc, 20) || !optStr(t.sp, 40) || !optStr(t.yt, 20) || !optStr(t.by, 16)) return null;
    // by: the member id of whoever added the song (Listen Together); the room stamps or overrides it.
    const clean = pick(t, ["t", "a", "al", "d", "isrc", "sp", "yt", "by"]);
    // art: the cover the adder's phone shows (Listen Together). Only from a known cover host, so no member
    // can make every phone in the room fetch from a server that logs IPs. Anything else is dropped, not fatal.
    const art = cleanCover(t.art);
    if (art) clean.art = art;
    // A duration outside 1 ms..24 h is dropped, not fatal: positions are clamped to it.
    if (!(Number.isSafeInteger(clean.d) && clean.d >= 1 && clean.d <= 86_400_000)) delete clean.d;
    return clean;
}
