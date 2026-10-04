/**
 * Short song links (stashfm.app contract 2026-10-03): `t:{id}` in SHARE_KV → { v: 1, track, createdAt }, kept forever
 * like a mix. The id comes from the song itself, so sharing the same song twice gives the same link.
 */
import { sha256Hex } from "./store.js";

const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
/** What makes two shares the same song. Not art (a later share may add it) or duration (sources disagree by a second). */
const IDENTITY = ["t", "a", "al", "isrc", "sp", "yt"];
const kvKey = (id) => `t:${id}`;

export const trackIdentity = (track) => JSON.stringify(IDENTITY.map((k) => String(track?.[k] ?? "").trim().toLowerCase()));

/**
 * Five ids from SHA-256(identity) written in base62, 8 digits at a time: the song's id is the first,
 * and each next one is only used when another song already holds the one before. 62^40 < 2^256, so all 40 digits are real.
 */
export async function candidateIds(identity) {
    let n = BigInt(`0x${await sha256Hex(identity)}`);
    let digits = "";
    for (let i = 0; i < 40; i++) {
        digits += ALPHABET[Number(n % 62n)];
        n /= 62n;
    }
    return [0, 8, 16, 24, 32].map((i) => digits.slice(i, i + 8));
}

export const readTrack = (kv, id) => kv.get(kvKey(id), "json");
export const writeTrack = (kv, id, record) => kv.put(kvKey(id), JSON.stringify(record));

/** Stores a cleaned song under its id: { id, created }. A song already stored only gains art it lacked. */
export async function saveTrack(kv, track, now = Date.now()) {
    const identity = trackIdentity(track);
    for (const id of await candidateIds(identity)) {
        const record = await readTrack(kv, id);
        if (record === null) {
            await writeTrack(kv, id, { v: 1, track, createdAt: now });
            return { id, created: true };
        }
        if (trackIdentity(record.track) !== identity) continue; // another song's hash starts with the same digits
        if (!record.track.art && track.art) await writeTrack(kv, id, { ...record, track: { ...record.track, art: track.art } });
        return { id, created: false };
    }
    throw new Error("could not allocate a track id"); // five clashes in a row: the caller's 503 says try again
}

/**
 * The song in a legacy long link (`/t?t=&a=&al=&d=&isrc=&sp=&yt=`), with the app parser's caps (ShareLinks.trackFrom).
 * A missing title stays missing (the page shows "Unknown song"), so the rebuilt link doesn't invent one for the app.
 */
export function trackFromQuery(params) {
    const text = (k, max) => (params.get(k) ?? "").trim().slice(0, max);
    const d = Number(params.get("d"));
    const track = {
        t: text("t", 500), a: text("a", 500), al: text("al", 500),
        d: Number.isSafeInteger(d) && d > 0 ? d : "", isrc: text("isrc", 20), sp: text("sp", 40), yt: text("yt", 20),
    };
    return Object.fromEntries(Object.entries(track).filter(([, v]) => v !== ""));
}

/** A legacy link rebuilt from only the song's fields, in the app's order: tracking junk like `fbclid` drops off. */
export function legacyTrackUrl(origin, track) {
    const q = new URLSearchParams();
    for (const k of ["t", "a", "al", "d", "isrc", "sp", "yt"]) if (track[k] !== undefined) q.set(k, String(track[k]));
    return `${origin}/t?${q}`;
}
