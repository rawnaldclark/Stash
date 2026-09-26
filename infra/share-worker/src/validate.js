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
];

/** True for an https URL whose host is on [COVER_HOSTS] (exactly, or as a subdomain). */
export function allowedCover(url) {
    let host;
    try {
        const u = new URL(url);
        if (u.protocol !== "https:") return false;
        host = u.hostname.toLowerCase();
    } catch { return false; }
    return COVER_HOSTS.some((h) => host === h || host.endsWith(`.${h}`));
}

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
    return {
        v: doc.v,
        name: doc.name,
        ...pick(doc, ["sharedBy"]),
        // Dropped, not rejected: the app builds covers from local art, which can come from other hosts.
        ...(doc.covers?.some(allowedCover) ? { covers: doc.covers.filter(allowedCover) } : {}),
        tracks: doc.tracks.map((t) => pick(t, ["t", "a", "al", "d", "isrc", "sp", "yt"])),
    };
}

const pick = (o, keys) => Object.fromEntries(keys.filter((k) => o[k] !== undefined && o[k] !== null).map((k) => [k, o[k]]));

/** base64url of 32 random bytes = 43 chars. */
export const validEditKey = (k) => typeof k === "string" && /^[A-Za-z0-9_-]{43}$/.test(k);

/** One song descriptor (the same fields and limits as a mix track), cleaned; null when invalid. Used by Listen Together. */
export function cleanTrack(t) {
    if (!t || typeof t !== "object" || !str(t.t, 1, 500) || !str(t.a, 1, 500)) return null;
    if (!optStr(t.al, 500) || !optStr(t.isrc, 20) || !optStr(t.sp, 40) || !optStr(t.yt, 20) || !optStr(t.by, 16)) return null;
    // by: the member id of whoever added the song (Listen Together); the room stamps or overrides it.
    const clean = pick(t, ["t", "a", "al", "d", "isrc", "sp", "yt", "by"]);
    // art: the cover the adder's phone shows (Listen Together). Only from a known cover host, so no member
    // can make every phone in the room fetch from a server that logs IPs. Anything else is dropped, not fatal.
    if (typeof t.art === "string" && t.art.length <= 1000 && allowedCover(t.art)) clean.art = t.art;
    // A duration outside 1 ms..24 h is dropped, not fatal: positions are clamped to it.
    if (!(Number.isSafeInteger(clean.d) && clean.d >= 1 && clean.d <= 86_400_000)) delete clean.d;
    return clean;
}
