/**
 * Builds the exact bytes of lossless.json (spec §3). Pure so the publishing rules are
 * testable: `updated_at` strictly above whatever is live (the device keeps a rollback
 * floor), relays normalised to what the client's LosslessSourcePreferences.normaliseEndpoint
 * produces (https only, no trailing slash) and sorted by priority. Stricter on input than the
 * client, on purpose: it STRIPS a query or fragment, this REJECTS one so a typo fails at publish time;
 * `v: 1`, the optional relay_key, and optional BitChord/Tidal relay settings. Callers sign the returned string's UTF-8 bytes as-is.
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
    return JSON.stringify(cfg);
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
