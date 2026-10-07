/**
 * GET /player: the door from stashfm.app to the Stash web player, which runs on its own domain (PLAYER_URL)
 * and lets in only visitors who bring a ticket from here.
 *
 * A signed-in visitor who still has early access gets a ticket and a 302 to `${PLAYER_URL}/auth#t=<ticket>`.
 * The ticket rides in the URL fragment, which the browser never sends to any server or writes to a log; the
 * player's /auth page trades it for its own session cookie. The format is the player's (stash-web
 * player/worker/gate.ts, verifyTicket), and test/player.test.js checks a ticket minted here against a copy of
 * that verifier:
 *
 *   ticket = b64url(JSON {sub, exp, aud}) + "." + b64url(Ed25519 signature over the first part's ASCII bytes)
 *
 *   sub  an opaque id for this person: HMAC-SHA256(SESSION_SECRET, "stash-player sub v1:" + <email hash>).
 *        Not the email and not the access list's hash of it, so the player never holds anything that could
 *        be matched to an email or to an access-list key. The same person always gets the same sub (until
 *        SESSION_SECRET changes, which signs everyone out of the site anyway).
 *   exp  now + 120 seconds. A ticket is a one-click handoff.
 *   aud  "stash-player".
 *
 * PLAYER_TICKET_PRIVATE_KEY (secret) is the private half of the player's key pair, an Ed25519 JWK (the player
 * repo's `npm run keys` prints it under that name); the player holds only the public half. PLAYER_URL (var)
 * is the player's origin, https only. Without either, or with either malformed, /player is a 503 page.
 *
 * Shared links: the player sends a visitor without a session to `/player?next=<a song or mix path>`. A
 * valid next is passed on as `${PLAYER_URL}/auth?next=...#t=...` and kept across signing in, as the
 * sign-in forms' hidden `next` field (playerReturn); the player checks it again before going there.
 */
import { b64urlEncode, hmacHex } from "./crypto.js";

export const TICKET_AUDIENCE = "stash-player";
export const TICKET_SECONDS = 120;
const SUB_LABEL = "stash-player sub v1:";
const MAX_NEXT = 2048;

/** PLAYER_URL as an origin ("https://player.example.net"), or null unless it's a plain https origin. */
export function playerOrigin(value) {
    if (typeof value !== "string" || !value.trim()) return null;
    let u;
    try {
        u = new URL(value.trim());
    } catch {
        return null;
    }
    if (u.protocol !== "https:" || u.username || u.password || u.search || u.hash || u.pathname !== "/") return null;
    // A real host name: no localhost, no bare single-label name.
    if (!u.hostname.includes(".") || u.hostname === "localhost" || u.hostname.endsWith(".localhost")) return null;
    return u.origin;
}

/** The signing key from PLAYER_TICKET_PRIVATE_KEY (a private Ed25519 JWK as JSON), or null if it isn't one. */
export async function ticketKey(jwkText) {
    if (typeof jwkText !== "string" || !jwkText.trim()) return null;
    try {
        const jwk = JSON.parse(jwkText);
        if (!jwk || jwk.kty !== "OKP" || jwk.crv !== "Ed25519" || typeof jwk.d !== "string" || !jwk.d) return null;
        return await crypto.subtle.importKey("jwk", jwk, { name: "Ed25519" }, false, ["sign"]);
    } catch {
        return null;
    }
}

/** The player's id for the person behind access-list hash [h]: opaque, stable, not reversible to the email. */
export const playerSub = (secret, h) => hmacHex(secret, SUB_LABEL + h);

/** A ticket for [sub], valid for TICKET_SECONDS from [nowSeconds]. */
export async function mintTicket(key, sub, nowSeconds) {
    const body = b64urlEncode(JSON.stringify({ sub, exp: nowSeconds + TICKET_SECONDS, aud: TICKET_AUDIENCE }));
    const sig = new Uint8Array(await crypto.subtle.sign({ name: "Ed25519" }, key, new TextEncoder().encode(body)));
    return `${body}.${b64urlEncode(sig)}`;
}

/** A player path worth coming back to (a shared song or mix page: /t..., /m/..., /play...), or null. */
export function playerNext(value) {
    if (typeof value !== "string" || value.length > MAX_NEXT || !/^\/(t|m|play)([/?]|$)/.test(value)) return null;
    try {
        return new URL(value, "https://player.invalid").origin === "https://player.invalid" ? value : null;
    } catch {
        return null;
    }
}

/** Where /player sends a visitor to sign in first: the early-access page, told to come back here. */
export const playerPath = (next) => (next ? `/player?next=${encodeURIComponent(next)}` : "/player");

/**
 * The sign-in forms' `next` field: "/player" or "/player?next=<a valid player path>", rebuilt from scratch so
 * it can only ever point at /player on this site. Anything else (missing, another page, another site) is null.
 */
export function playerReturn(value) {
    if (typeof value !== "string" || value.length > 3 * MAX_NEXT + 64 || !/^\/player(\?|$)/.test(value)) return null;
    let u;
    try {
        u = new URL(value, "https://stashfm.invalid");
    } catch {
        return null;
    }
    if (u.origin !== "https://stashfm.invalid" || u.pathname !== "/player" || u.hash) return null;
    const keys = [...u.searchParams.keys()];
    if (keys.length === 0) return "/player";
    if (keys.length !== 1 || keys[0] !== "next") return null;
    const next = playerNext(u.searchParams.get("next"));
    return next ? playerPath(next) : null;
}

/** The player's sign-in URL carrying [ticket] (and [next], already checked). */
export function authUrl(origin, ticket, next) {
    return next ? `${origin}/auth?next=${encodeURIComponent(next)}#t=${ticket}` : `${origin}/auth#t=${ticket}`;
}
