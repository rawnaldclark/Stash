/**
 * What Community's posts (src/community.js) and Sessions' stations (src/stations-api.js) share: who is calling, from
 * which network, the request body, the error answers, and the vote rules (spec 2026-09-26 §2, Sessions spec §3).
 * A phone is sha256(its key); a network is sha256(COMMUNITY_SALT | IPv4, or IPv6 /56). Every error body is {error: "<code>"}.
 */
import { ip, json, readCapped } from "./http.js";
import { validEditKey } from "./validate.js";
import { sha256Hex } from "./store.js";

export const HIDE_AT = -3; // up - down at or below this hides a post or a station, unless the owner vouched for it
export const VOTES_PER_NETWORK = 2; // counted votes per network per post (or station), in each direction
/**
 * Stored vote rows per network per post. A voter is sha256(any key), so without this a script behind one network
 * stored a fresh row on every vote (60/min), and since every vote recounts all of the post's rows, D1 reads grew
 * with the square of that. Well above VOTES_PER_NETWORK, so a counted vote taken back still leaves real ones to count.
 */
export const VOTE_ROWS_PER_NETWORK = 20;
export const MAX_REQUEST_BYTES = 256 * 1024;

export const KEY_HEADER = "X-Stash-Community-Key";
export const NO_STORE = { "cache-control": "no-store" };
export const rateLimited = () => json({ error: "rate_limited" }, 429, { "Retry-After": "60" });
export const notAllowed = () => json({ error: "method_not_allowed" }, 405);
export const gone = () => json({ error: "gone" }, 404);

/**
 * HEARD_REQUIRED = "1" (a [vars] entry in wrangler.toml): a +1 or -1 vote needs the voter's heard mark on the post
 * (contract 2026-11-03). Off by default, so app versions that never send heard can still vote until the owner turns it on.
 */
export const heardRequired = (env) => env.HEARD_REQUIRED === "1";
/** At or below HIDE_AT, unless the owner vouched for it (`restore`): votes never hide a vouched post. The feed's WHERE is the same rule in SQL. */
export const hiddenByVotes = (p) => p.up - p.down <= HIDE_AT && !p.vouched;

/** The caller's poster id: {id} with a valid key, {none} without one, {bad} with a malformed one. */
export async function identity(request) {
    const key = request.headers.get(KEY_HEADER);
    if (key === null) return { none: true };
    if (!validEditKey(key)) return { bad: true };
    return { id: await sha256Hex(key) };
}

/**
 * The caller's network (an IPv4 address, or an IPv6 /56): http.js's ip(), so a web listener behind the player's proxy
 * is their own network when the proxy vouches for them (SHARE_PROXY_KEY). The per-minute limits count by it too
 * (spec §2), so hopping /64s inside one /56 doesn't dodge them; the stored form is salted and hashed.
 */
export const network = (request, env) => ip(request, env);
export const networkHash = (request, env) => {
    // Unsalted, the stored IPv4 hashes could be reversed by trying every address (spec §2): refuse instead.
    if (!env.COMMUNITY_SALT) throw new Error("COMMUNITY_SALT is not set");
    return sha256Hex(`${env.COMMUNITY_SALT}|${network(request, env)}`);
};

export async function readJson(request) {
    if (Number(request.headers.get("content-length")) > MAX_REQUEST_BYTES) return { tooBig: true };
    const buf = await readCapped(request, MAX_REQUEST_BYTES); // streamed: a body without Content-Length isn't buffered whole
    if (buf === null) return { tooBig: true };
    try { return { body: JSON.parse(new TextDecoder().decode(buf)) }; } catch { return { body: null }; }
}
