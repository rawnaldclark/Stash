/**
 * Who's on the admin page. Cloudflare Access (Zero Trust) guards stashfm.app/admin and adds a signed JWT
 * to every request it lets through, in the Cf-Access-Jwt-Assertion header. This checks it as Cloudflare's
 * docs say to (Access > Applications > Authorization cookie > Validate JWTs):
 *
 *   - RS256, signed by a key from https://<team>.cloudflareaccess.com/cdn-cgi/access/certs, matched by kid
 *     (Access rotates keys every 6 weeks, so they're fetched, cached for 10 minutes, and fetched again for an
 *     unknown kid at most once a minute);
 *   - aud contains ACCESS_AUD (the Access application's AUD tag), iss is ACCESS_TEAM_DOMAIN, exp is in the
 *     future and nbf isn't (a minute of leeway for clock drift);
 *   - and, as a second lock, the token's email is in the ADMIN_EMAILS secret.
 *
 * Anything missing or wrong, including unset or placeholder settings, means no admin. There's no fallback
 * to the plain Cf-Access-Authenticated-User-Email header, which anyone can send to the workers.dev address.
 * (ctx.access isn't an option either: Workers with static assets don't get it.)
 */
import { b64urlDecode, clock } from "./crypto.js";

const KEYS_TTL_MS = 10 * 60 * 1000;
const REFETCH_GAP_MS = 60 * 1000;
const LEEWAY_S = 60;
const TEAM = /^https:\/\/[a-z0-9][a-z0-9-]*\.cloudflareaccess\.com$/;

const jwks = new Map(); // team -> { keys: Map(kid -> CryptoKey), fetchedAt }

/** ACCESS_TEAM_DOMAIN as an origin (https://<team>.cloudflareaccess.com), or null while it's unset or a placeholder. */
export function teamDomain(env) {
    const team = String(env.ACCESS_TEAM_DOMAIN || "").trim().replace(/\/+$/, "").toLowerCase();
    return TEAM.test(team) && !/todo/.test(team) ? team : null;
}

export function accessSettings(env) {
    const team = teamDomain(env) ?? "";
    const aud = String(env.ACCESS_AUD || "").trim();
    const admins = String(env.ADMIN_EMAILS || "")
        .split(",")
        .map((e) => e.trim().toLowerCase())
        .filter(Boolean);
    const configured = Boolean(team) && aud.length >= 16 && !/todo/i.test(aud) && admins.length > 0;
    return configured ? { team, aud, admins } : null;
}

async function fetchKeys(team, fetchImpl) {
    const res = await fetchImpl(`${team}/cdn-cgi/access/certs`, { headers: { accept: "application/json" } });
    if (!res.ok) throw new Error(`Access certs: HTTP ${res.status}`);
    const body = await res.json();
    const keys = new Map();
    for (const jwk of body.keys ?? []) {
        if (jwk.kty !== "RSA" || !jwk.kid) continue;
        const key = await crypto.subtle.importKey("jwk", { kty: "RSA", n: jwk.n, e: jwk.e, alg: "RS256", ext: true }, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
        keys.set(jwk.kid, key);
    }
    jwks.set(team, { keys, fetchedAt: clock.now() });
    return keys;
}

async function keyFor(team, kid, fetchImpl) {
    const entry = jwks.get(team);
    const age = entry ? clock.now() - entry.fetchedAt : Infinity;
    if (entry && age < KEYS_TTL_MS && entry.keys.has(kid)) return entry.keys.get(kid);
    if (entry && age < REFETCH_GAP_MS) return entry.keys.get(kid) ?? null;
    return (await fetchKeys(team, fetchImpl)).get(kid) ?? null;
}

export const forgetAccessKeys = () => jwks.clear();

const json = (part) => JSON.parse(new TextDecoder().decode(b64urlDecode(part)));

/** { email } for a signed-in admin, or null. */
export async function verifyAccess(request, env, fetchImpl = fetch) {
    const settings = accessSettings(env);
    const token = request.headers.get("Cf-Access-Jwt-Assertion");
    if (!settings || !token) return null;
    try {
        const parts = token.split(".");
        if (parts.length !== 3) return null;
        const header = json(parts[0]);
        if (header.alg !== "RS256" || typeof header.kid !== "string") return null;
        const key = await keyFor(settings.team, header.kid, fetchImpl);
        if (!key) return null;
        const signed = new TextEncoder().encode(`${parts[0]}.${parts[1]}`);
        if (!(await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, b64urlDecode(parts[2]), signed))) return null;
        const claims = json(parts[1]);
        const now = Math.floor(clock.now() / 1000);
        const audiences = Array.isArray(claims.aud) ? claims.aud : [claims.aud];
        if (!audiences.includes(settings.aud)) return null;
        if (claims.iss !== settings.team) return null;
        if (!Number.isFinite(claims.exp) || claims.exp <= now - LEEWAY_S) return null;
        if (Number.isFinite(claims.nbf) && claims.nbf > now + LEEWAY_S) return null;
        const email = typeof claims.email === "string" ? claims.email.trim().toLowerCase() : "";
        if (!email || !settings.admins.includes(email)) return null;
        return { email };
    } catch (err) {
        console.error("admin sign-in check failed:", err && err.message);
        return null;
    }
}
