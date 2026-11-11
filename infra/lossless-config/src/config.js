/**
 * Builds the exact bytes of lossless.json (spec §3). Pure so the publishing rules are
 * testable: `updated_at` strictly above whatever is live (the device keeps a rollback
 * floor), relays normalised to what the client's LosslessSourcePreferences.normaliseEndpoint
 * produces (https only, no trailing slash) and sorted by priority. Stricter on input than the
 * client, on purpose: it STRIPS a query or fragment, this REJECTS one so a typo fails at publish time;
 * `v: 1`, the optional relay_key, optional BitChord/Tidal relay settings, `default_addons` and `addon_index`. Callers sign the returned string's UTF-8 bytes as-is.
 */
export function buildConfig(input, liveUpdatedAt, nowSec) {
    const relays = (input.relays || []).map((r, i) => {
        const base = normaliseBase(r.base);
        if (!base) throw new Error(`relays[${i}].base must be an https URL with no query or fragment: ${r.base}`);
        return { base, priority: Number.isInteger(r.priority) ? r.priority : 1 };
    }).sort((a, b) => a.priority - b.priority);
    const cfg = { v: 1, relays, updated_at: Math.max(nowSec, (liveUpdatedAt || 0) + 1) };
    if (input.relay_key !== undefined) {
        if (typeof input.relay_key !== "string" || input.relay_key.length < 32) throw new Error("relay_key must be a string of at least 32 characters");
        cfg.relay_key = input.relay_key;
    }
    if (input.tidal_relay_url !== undefined) {
        const base = normaliseTidalOrigin(input.tidal_relay_url);
        if (!base) throw new Error("tidal_relay_url must be an https origin with no path, query, or fragment");
        cfg.tidal_relay_url = base;
    }
    if (input.tidal_relay_token !== undefined) {
        if (typeof input.tidal_relay_token !== "string" || !input.tidal_relay_token.trim()) {
            throw new Error("tidal_relay_token must be a non-empty string when provided");
        }
        if (input.tidal_relay_url === undefined) throw new Error("tidal_relay_token requires tidal_relay_url");
        cfg.tidal_relay_token = input.tidal_relay_token;
    }
    if (input.default_addons !== undefined) cfg.default_addons = buildDefaultAddons(input.default_addons);
    if (input.addon_index !== undefined) {
        const url = normaliseIndexUrl(input.addon_index);
        if (!url) throw new Error("addon_index must be an https URL ending in .json, with no credentials, query or fragment, and not a github.com page (use raw.githubusercontent.com)");
        cfg.addon_index = url;
    }
    return JSON.stringify(cfg);
}

/**
 * `addon_index` (add-ons phase 4, plan section 1.4): where the app fetches the signed add-on index (`<url>` and
 * `<url>.sig`). Public, so it is printed. https, a path ending in `.json`, no credentials, query or fragment, at most
 * 2048 characters. A github.com page is refused: it serves HTML, and the file is on raw.githubusercontent.com.
 * Released apps ignore the key.
 */
export function normaliseIndexUrl(raw) {
    if (typeof raw !== "string") return null;
    const text = raw.trim();
    if (!text || text.length > 2048 || /[\s\\]/.test(text)) return null;
    let u;
    try { u = new URL(text); } catch { return null; }
    if (u.protocol !== "https:" || !u.hostname || u.username || u.password || u.search || u.hash || text.includes("?") || text.includes("#")) return null;
    if (!u.pathname.endsWith(".json") || u.hostname === "github.com" || u.hostname === "www.github.com") return null;
    return u.href;
}

/** At most this many `default_addons` entries (the app reads no more). */
export const MAX_DEFAULT_ADDONS = 16;

/**
 * `default_addons` (add-ons phase 2, plan section 5.3): the add-ons every install turns on, in play order.
 * Each entry is `{ manifest, enabled, first_party }`, written in that order with both booleans explicit:
 *  - `manifest`: an https URL ending in `/manifest.json`, with no user, password, query or fragment, at most
 *    2048 characters. It may carry a path token (`/v1/<token>/manifest.json`), so it is never printed.
 *  - `enabled` (default true) and `first_party` (default false): booleans when given. A `first_party` add-on gets
 *    the install signature; the Tidal one is the app's switch to the v1 wire (decision D9).
 * Anything else in an entry, a duplicate manifest, or more than MAX_DEFAULT_ADDONS entries is an error, so a typo
 * fails here and never reaches a phone. An empty list is allowed: it is the same as leaving the key out.
 */
export function buildDefaultAddons(raw) {
    if (!Array.isArray(raw)) throw new Error("default_addons must be an array");
    if (raw.length > MAX_DEFAULT_ADDONS) throw new Error(`default_addons holds at most ${MAX_DEFAULT_ADDONS} entries`);
    const seen = new Set();
    return raw.map((e, i) => {
        const at = `default_addons[${i}]`;
        if (!e || typeof e !== "object" || Array.isArray(e)) throw new Error(`${at} must be an object`);
        const extra = Object.keys(e).filter((k) => !["manifest", "enabled", "first_party"].includes(k));
        if (extra.length) throw new Error(`${at} has unknown keys: ${extra.join(", ")}`);
        const manifest = normaliseManifestUrl(e.manifest);
        // The URL can carry the add-on's token: never echo it.
        if (!manifest) throw new Error(`${at}.manifest must be an https URL ending in /manifest.json, with no credentials, query or fragment`);
        if (seen.has(manifest)) throw new Error(`${at}.manifest is listed twice`);
        seen.add(manifest);
        for (const k of ["enabled", "first_party"]) {
            if (e[k] !== undefined && typeof e[k] !== "boolean") throw new Error(`${at}.${k} must be true or false`);
        }
        return { manifest, enabled: e.enabled ?? true, first_party: e.first_party ?? false };
    });
}

/** An add-on manifest URL as the app takes it (SPEC.md 1.1 and 3), or null. */
export function normaliseManifestUrl(raw) {
    if (typeof raw !== "string") return null;
    const text = raw.trim();
    if (!text || text.length > 2048 || /[\s\\]/.test(text)) return null;
    let u;
    try { u = new URL(text); } catch { return null; }
    if (u.protocol !== "https:" || !u.hostname || u.username || u.password || u.search || u.hash || text.includes("?") || text.includes("#")) return null;
    if (!u.pathname.endsWith("/manifest.json")) return null;
    return u.href;
}

/** What may be printed about `default_addons`: a count, and each entry's origin and flags (never its path). */
export function describeDefaultAddons(list) {
    if (!Array.isArray(list)) return "(unset)";
    if (!list.length) return "[] (none)";
    return list.map((e) => {
        let origin = "(bad url)";
        try { origin = new URL(e.manifest).origin; } catch { /* keep the placeholder */ }
        return `${origin}${e.enabled ? "" : " (off)"}${e.first_party ? " first_party" : ""}`;
    }).join(", ");
}

export function normaliseTidalOrigin(raw) {
    let u;
    try { u = new URL(String(raw || "").trim()); } catch { return null; }
    if (u.protocol !== "https:" || u.username || u.password || u.search || u.hash || (u.pathname !== "/" && u.pathname !== "")) return null;
    return u.origin;
}

export function normaliseBase(raw) {
    let u;
    try { u = new URL(String(raw || "").trim()); } catch { return null; }
    if (u.protocol !== "https:" || u.search || u.hash) return null;
    return (u.origin + u.pathname).replace(/\/+$/, "");
}
